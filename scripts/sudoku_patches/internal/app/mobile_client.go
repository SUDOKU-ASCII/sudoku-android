package app

import (
	"context"
	"fmt"
	"log"
	"net"

	"github.com/SUDOKU-ASCII/sudoku/internal/config"
	"github.com/SUDOKU-ASCII/sudoku/internal/tunnel"
	"github.com/SUDOKU-ASCII/sudoku/pkg/dnsutil"
	"github.com/SUDOKU-ASCII/sudoku/pkg/obfs/sudoku"
)

type MobileInstance struct {
	ln        net.Listener
	cancel    context.CancelFunc
	done      chan struct{}
	muxDialer *tunnel.MuxDialer
	muxCancel context.CancelFunc
	muxDone   chan struct{}
}

func (m *MobileInstance) Stop() {
	if m.cancel != nil {
		m.cancel()
	}
	if m.ln != nil {
		m.ln.Close()
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

func StartMobileClient(cfg *config.Config) (*MobileInstance, error) {
	// 1. Normalize key (may derive public key).
	privateKeyBytes, changed, err := normalizeClientKey(cfg)
	if err != nil {
		return nil, fmt.Errorf("process key: %w", err)
	}
	if changed {
		log.Printf("Derived Public Key: %s", cfg.Key)
	}

	// 2. Build one or more tables (supports custom_tables rotation).
	tables, err := BuildTables(cfg)
	if err != nil {
		return nil, fmt.Errorf("build table(s): %w", err)
	}

	baseDialer := tunnel.BaseDialer{
		Config:     cfg,
		Tables:     tables,
		PrivateKey: privateKeyBytes,
	}

	var dialer tunnel.Dialer
	var muxDialer *tunnel.MuxDialer
	if cfg.SessionMuxEnabled() {
		muxDialer = &tunnel.MuxDialer{BaseDialer: baseDialer}
		dialer = muxDialer
		log.Printf("Enabled session mux (single tunnel, multi-target)")
	} else {
		dialer = &tunnel.StandardDialer{BaseDialer: baseDialer}
	}
	resolver, err := dnsutil.NewResolver(dnsutil.RecommendedClientOptions())
	if err != nil {
		return nil, fmt.Errorf("build DNS resolver: %w", err)
	}

	// 3. Routing / PAC managers
	routeMgrs := buildRouteManagers(cfg)

	// 4. Listen
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", cfg.LocalPort))
	if err != nil {
		return nil, fmt.Errorf("listen: %w", err)
	}
	log.Printf("Mobile Client on :%d -> %s | Mode: %s", cfg.LocalPort, cfg.ServerAddress, cfg.ProxyMode)

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	var muxCancel context.CancelFunc
	var muxDone chan struct{}
	if muxDialer != nil {
		var muxCtx context.Context
		muxCtx, muxCancel = context.WithCancel(context.Background())
		muxDone = make(chan struct{})
		go func() {
			defer close(muxDone)
			muxDialer.Maintain(muxCtx, func(err error) {
				if err != nil {
					log.Printf("Mux warm session unavailable: %v", err)
				}
			})
		}()
	}

	var primaryTable *sudoku.Table
	if len(tables) > 0 {
		primaryTable = tables[0]
	}

	go func() {
		defer close(done)
		defer ln.Close()
		for {
			c, err := ln.Accept()
			if err != nil {
				select {
				case <-ctx.Done():
					return
				default:
					continue
				}
			}
			go func(conn net.Conn) {
				defer func() {
					if r := recover(); r != nil {
						log.Printf("PANIC in handleMixedConn: %v", r)
					}
				}()
				log.Printf("Accepted connection from %s", conn.RemoteAddr())
				// handleMixedConn takes a primary table for legacy helpers;
				// the dialer itself performs per-connection table rotation.
				handleMixedConn(conn, cfg, primaryTable, routeMgrs, dialer, resolver)
			}(c)
		}
	}()

	return &MobileInstance{
		ln:        ln,
		cancel:    cancel,
		done:      done,
		muxDialer: muxDialer,
		muxCancel: muxCancel,
		muxDone:   muxDone,
	}, nil
}
