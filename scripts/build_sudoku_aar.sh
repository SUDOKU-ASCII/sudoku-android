#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.."; pwd)"
WORK_DIR="${ROOT}/build_work"
SUDOKU_REPO="https://github.com/SUDOKU-ASCII/sudoku.git"
SUDOKU_REF="${SUDOKU_REF:-v0.1.1}"
SUDOKU_DIR="${WORK_DIR}/sudoku"
OUT_AAR="${ROOT}/app/libs/sudoku.aar"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-21}"
GOMOBILE_BIN="${GOMOBILE_BIN:-gomobile}"

# Ensure gomobile is installed
if ! command -v "${GOMOBILE_BIN}" >/dev/null 2>&1; then
  fallback="$(go env GOPATH 2>/dev/null)/bin/gomobile"
  if [[ -x "${fallback}" ]]; then
    GOMOBILE_BIN="${fallback}"
  else
    echo "gomobile not found. Please install it first (or set GOMOBILE_BIN)."
    exit 1
  fi
fi

# Cleanup and Prep
rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}"

# Clone sudoku
echo "Cloning sudoku (${SUDOKU_REF})..."
git clone --depth 1 --branch "${SUDOKU_REF}" "${SUDOKU_REPO}" "${SUDOKU_DIR}"

# Inject Mobile Client Implementation into internal/app
# This allows access to unexported functions like normalizeClientKey and handleMixedConn
echo "Injecting mobile client implementation..."
cat <<EOF > "${SUDOKU_DIR}/internal/app/mobile_client.go"
package app

import (
	"context"
	"fmt"
	"log"
	"net"

	"github.com/saba-futai/sudoku/internal/config"
	"github.com/saba-futai/sudoku/internal/tunnel"
	"github.com/saba-futai/sudoku/pkg/geodata"
	"github.com/saba-futai/sudoku/pkg/obfs/sudoku"
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
	tables, err := buildTablesFromConfig(cfg)
	if err != nil {
		return nil, fmt.Errorf("build table(s): %w", err)
	}

	baseDialer := tunnel.BaseDialer{
		Config:     cfg,
		Tables:     tables,
		PrivateKey: privateKeyBytes,
	}

	dialer := &tunnel.StandardDialer{
		BaseDialer: baseDialer,
	}

	// 3. GeoIP/PAC
	var geoMgr *geodata.Manager
	if cfg.ProxyMode == "pac" {
		geoMgr = geodata.GetInstance(cfg.RuleURLs)
	}

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
				handleMixedConn(conn, cfg, primaryTable, geoMgr, dialer)
			}(c)
		}
	}()

	return &MobileInstance{ln: ln, cancel: cancel, done: done}, nil
}
EOF

# Inject Mobile Wrapper Package
echo "Injecting mobile wrapper..."
mkdir -p "${SUDOKU_DIR}/pkg/mobile"
cat <<EOF > "${SUDOKU_DIR}/pkg/mobile/mobile.go"
package mobile

import (
	"encoding/json"
	"fmt"
	"sync"

	"github.com/saba-futai/sudoku/internal/app"
	"github.com/saba-futai/sudoku/internal/config"
)

var (
	mu       sync.Mutex
	instance *app.MobileInstance
)

func Start(jsonConfig string) error {
	mu.Lock()
	defer mu.Unlock()

	if instance != nil {
		instance.Stop()
		instance = nil
	}

	var cfg config.Config
	if err := json.Unmarshal([]byte(jsonConfig), &cfg); err != nil {
		return fmt.Errorf("parse config: %w", err)
	}

	// Apply defaults similar to config.Load.
	if cfg.Transport == "" {
		cfg.Transport = "tcp"
	}
	if cfg.ASCII == "" {
		cfg.ASCII = "prefer_entropy"
	}
	if cfg.HTTPMaskMode == "" {
		cfg.HTTPMaskMode = "legacy"
	}
	if !cfg.EnablePureDownlink && cfg.AEAD == "none" {
		return fmt.Errorf("enable_pure_downlink=false requires AEAD to be enabled")
	}

	// Proxy Mode Logic
	if len(cfg.RuleURLs) > 0 && (cfg.RuleURLs[0] == "global" || cfg.RuleURLs[0] == "direct") {
		cfg.ProxyMode = cfg.RuleURLs[0]
		cfg.RuleURLs = nil
	} else if len(cfg.RuleURLs) > 0 {
		cfg.ProxyMode = "pac"
	} else {
		if cfg.ProxyMode == "" {
			cfg.ProxyMode = "global"
		}
	}

	inst, err := app.StartMobileClient(&cfg)
	if err != nil {
		return err
	}
	instance = inst
	return nil
}

func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if instance != nil {
		instance.Stop()
		instance = nil
	}
}
EOF

# Build AAR
echo "Building AAR..."
mkdir -p "$(dirname "${OUT_AAR}")"
pushd "${SUDOKU_DIR}" >/dev/null
go get -d golang.org/x/mobile/bind
"${GOMOBILE_BIN}" bind \
  -target=android/arm64,android/amd64 \
  -androidapi "${ANDROID_API_LEVEL}" \
  -javapkg com.futaiii.sudoku \
  -o "${OUT_AAR}" \
  ./pkg/mobile
popd >/dev/null

# Cleanup
rm -rf "${WORK_DIR}"
echo "Generated ${OUT_AAR}"
