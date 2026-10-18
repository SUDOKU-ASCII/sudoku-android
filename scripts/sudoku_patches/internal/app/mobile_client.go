package app

import (
	"context"
	"fmt"
	"net"
	"time"

	"github.com/SUDOKU-ASCII/sudoku/internal/config"
	"github.com/SUDOKU-ASCII/sudoku/internal/controller"
	"github.com/SUDOKU-ASCII/sudoku/internal/tunnel"
	"github.com/SUDOKU-ASCII/sudoku/pkg/dnsutil"
	"github.com/SUDOKU-ASCII/sudoku/pkg/logx"
)

const (
	mobileMuxIdleTimeout       = 60 * time.Second
	mobileMuxIdleCheckInterval = 20 * time.Second
)

// MobileInstance is one running mixed-proxy client plus its optional external controller.
type MobileInstance struct {
	ln         net.Listener
	done       chan struct{}
	stopAPI    func()
	muxDialer  *tunnel.MuxDialer
	muxCancel  context.CancelFunc
	muxDone    chan struct{}
	stopCalled bool
}

func (m *MobileInstance) Stop() {
	if m.stopCalled {
		return
	}
	m.stopCalled = true
	if m.ln != nil {
		_ = m.ln.Close()
	}
	if m.stopAPI != nil {
		m.stopAPI()
	}
	if m.muxCancel != nil {
		m.muxCancel()
	}
	if m.muxDialer != nil {
		_ = m.muxDialer.Close()
	}
	if m.done != nil {
		<-m.done
	}
	if m.muxDone != nil {
		<-m.muxDone
	}
}

// StartMobileClient runs the upstream client stack (routing, outbounds, tracker, controller) inside
// the app process. Routing mode is switched live through the controller's PATCH /configs.
func StartMobileClient(cfg *config.Config) (*MobileInstance, error) {
	rt, err := buildClientRuntime(cfg, nil)
	if err != nil {
		return nil, err
	}
	resolver, err := dnsutil.NewResolver(dnsutil.RecommendedClientOptions())
	if err != nil {
		return nil, fmt.Errorf("build DNS resolver: %w", err)
	}
	out, err := buildOutbounds([]*clientRuntime{rt})
	if err != nil {
		return nil, fmt.Errorf("build outbound dialer: %w", err)
	}

	cl := newClient(cfg, out, resolver)
	var stopAPI func()
	if addr := cfg.ExternalController; addr != "" {
		cl.tracker = controller.NewTracker()
		stopAPI, err = controller.Start(controller.Options{
			Addr: addr, Secret: cfg.Secret, Client: cl, Tracker: cl.tracker,
		})
		if err != nil {
			return nil, fmt.Errorf("external controller: %w", err)
		}
	}

	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", cfg.LocalPort))
	if err != nil {
		if stopAPI != nil {
			stopAPI()
		}
		return nil, fmt.Errorf("listen: %w", err)
	}
	logx.Infof("Client", "Mobile client on :%d -> %s | Mode: %s | Rules: %d",
		cfg.LocalPort, cfg.ServerAddress, cfg.ProxyMode, len(cfg.RuleURLs))

	inst := &MobileInstance{ln: ln, done: make(chan struct{}), stopAPI: stopAPI}

	if muxDialer, ok := rt.Dialer.(*tunnel.MuxDialer); ok {
		var muxCtx context.Context
		muxCtx, inst.muxCancel = context.WithCancel(context.Background())
		inst.muxDialer = muxDialer
		inst.muxDone = make(chan struct{})
		go func() {
			defer close(inst.muxDone)
			// Warm once instead of Maintain: a permanently warm session exchanges
			// keepalives every 15s and drains the battery while the phone is idle.
			// Dial re-creates the session on demand after the idle closer drops it.
			if err := muxDialer.Warm(muxCtx); err != nil && muxCtx.Err() == nil {
				logx.Warnf("Mux", "Warm session unavailable: %v", err)
			}
			idle := tunnel.MuxIdleCloser{Dialer: muxDialer, Timeout: mobileMuxIdleTimeout}
			ticker := time.NewTicker(mobileMuxIdleCheckInterval)
			defer ticker.Stop()
			for {
				select {
				case <-muxCtx.Done():
					return
				case now := <-ticker.C:
					idle.Check(now)
				}
			}
		}()
	}

	go func() {
		defer close(inst.done)
		acceptLoop("Client", ln, func(conn net.Conn) {
			defer func() {
				if r := recover(); r != nil {
					logx.Errorf("Client", "panic in serveMixed: %v", r)
				}
			}()
			cl.serveMixed(conn)
		})
	}()

	return inst, nil
}
