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
	ln     net.Listener
	cancel context.CancelFunc
	done   chan struct{}
}

func (m *MobileInstance) Stop() {
	if m.cancel != nil {
		m.cancel()
	}
	if m.ln != nil {
		m.ln.Close()
	}
	if m.done != nil {
		<-m.done
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
	if cfg.HTTPMaskSessionMuxEnabled() {
		dialer = &tunnel.MuxDialer{BaseDialer: baseDialer}
		log.Printf("Enabled HTTPMask session mux (single tunnel, multi-target)")
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

	return &MobileInstance{ln: ln, cancel: cancel, done: done}, nil
}
