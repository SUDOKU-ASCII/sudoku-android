#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.."; pwd)"
WORK_DIR="${ROOT}/build_work"
SUDOKU_REPO="https://github.com/saba-futai/sudoku"
SUDOKU_DIR="${WORK_DIR}/sudoku"
OUT_AAR="${ROOT}/app/libs/sudoku.aar"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-21}"

# Ensure gomobile is installed
if ! command -v gomobile >/dev/null 2>&1; then
  echo "gomobile not found. Please install it first."
  exit 1
fi

# Cleanup and Prep
rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}"

# Clone sudoku
echo "Cloning sudoku..."
git clone "${SUDOKU_REPO}" "${SUDOKU_DIR}"

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
	"github.com/saba-futai/sudoku/internal/hybrid"
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
	<-m.done
}

func StartMobileClient(cfg *config.Config, table *sudoku.Table) (*MobileInstance, error) {
	// 1. Initialize Dialer
	privateKeyBytes, updatedTable, changed, err := normalizeClientKey(cfg, table)
	if err != nil {
		return nil, fmt.Errorf("process key: %w", err)
	}
	if changed {
		table = updatedTable
		log.Printf("Derived Public Key: %s", cfg.Key)
	}

	baseDialer := tunnel.BaseDialer{
		Config:     cfg,
		Table:      table,
		PrivateKey: privateKeyBytes,
	}

	var dialer tunnel.Dialer
	if cfg.EnableMieru {
		mgr := hybrid.GetInstance(cfg)
		if err := mgr.StartMieruClient(); err != nil {
			return nil, fmt.Errorf("start mieru: %w", err)
		}
		dialer = &tunnel.HybridDialer{
			BaseDialer: baseDialer,
			Manager:    mgr,
		}
	} else {
		dialer = &tunnel.StandardDialer{
			BaseDialer: baseDialer,
		}
	}

	// 2. GeoIP/PAC
	var geoMgr *geodata.Manager
	if cfg.ProxyMode == "pac" {
		geoMgr = geodata.GetInstance(cfg.RuleURLs)
	}

	// 3. Listen
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", cfg.LocalPort))
	if err != nil {
		return nil, fmt.Errorf("listen: %w", err)
	}
	log.Printf("Mobile Client on :%d -> %s | Mode: %s", cfg.LocalPort, cfg.ServerAddress, cfg.ProxyMode)

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	
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
			go func() {
				defer func() {
					if r := recover(); r != nil {
						log.Printf("PANIC in handleMixedConn: %v", r)
					}
				}()
				log.Printf("Accepted connection from %s", c.RemoteAddr())
				handleMixedConn(c, cfg, table, geoMgr, dialer)
			}()
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
	"github.com/saba-futai/sudoku/pkg/obfs/sudoku"
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

	// Apply defaults
	if cfg.Transport == "" { cfg.Transport = "tcp" }
	if cfg.ASCII == "" { cfg.ASCII = "prefer_entropy" }

	// Proxy Mode Logic
	if len(cfg.RuleURLs) > 0 && (cfg.RuleURLs[0] == "global" || cfg.RuleURLs[0] == "direct") {
		cfg.ProxyMode = cfg.RuleURLs[0]
		cfg.RuleURLs = nil
	} else if len(cfg.RuleURLs) > 0 {
		cfg.ProxyMode = "pac"
	} else if cfg.ProxyMode == "" {
		cfg.ProxyMode = "global"
	}

	// Normalize Mieru config exactly like CLI loader.
	config.InitMieruconfig(&cfg)

	table := sudoku.NewTable(cfg.Key, cfg.ASCII)
	inst, err := app.StartMobileClient(&cfg, table)
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
gomobile bind \
  -target=android/arm64,android/amd64 \
  -androidapi "${ANDROID_API_LEVEL}" \
  -javapkg com.futaiii.sudoku \
  -o "${OUT_AAR}" \
  ./pkg/mobile
popd >/dev/null

# Cleanup
rm -rf "${WORK_DIR}"
echo "Generated ${OUT_AAR}"
