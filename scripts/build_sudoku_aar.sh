#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.."; pwd)"
WORK_DIR="${ROOT}/build_work"
SUDOKU_REPO="https://github.com/SUDOKU-ASCII/sudoku.git"
SUDOKU_REF="${SUDOKU_REF:-v0.1.10}"
SUDOKU_DIR="${WORK_DIR}/sudoku"
OUT_AAR="${ROOT}/app/libs/sudoku.aar"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-21}"
GOMOBILE_BIN="${GOMOBILE_BIN:-gomobile}"
GOMOBILE_TARGETS="${GOMOBILE_TARGETS:-android/arm,android/arm64}"

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
rm -r "${WORK_DIR}" 2>/dev/null || true
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
	"strings"

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
	tables, err := BuildTables(cfg)
	if err != nil {
		return nil, fmt.Errorf("build table(s): %w", err)
	}

	baseDialer := tunnel.BaseDialer{
		Config:     cfg,
		Tables:     tables,
		PrivateKey: privateKeyBytes,
	}

	httpMaskMode := strings.ToLower(strings.TrimSpace(cfg.HTTPMaskMode))
	httpMaskMux := strings.ToLower(strings.TrimSpace(cfg.HTTPMaskMultiplex))
	var dialer tunnel.Dialer
	if !cfg.DisableHTTPMask && (httpMaskMode == "stream" || httpMaskMode == "poll" || httpMaskMode == "auto") && httpMaskMux == "on" {
		dialer = &tunnel.MuxDialer{BaseDialer: baseDialer}
		log.Printf("Enabled HTTPMask session mux (single tunnel, multi-target)")
	} else {
		dialer = &tunnel.AdaptiveDialer{
			BaseDialer: baseDialer,
		}
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

# Inject mobile traffic stats helpers (direct vs proxy).
echo "Injecting mobile traffic stats..."
cat <<'EOF' > "${SUDOKU_DIR}/internal/app/mobile_traffic.go"
package app

import (
	"net"
	"sync/atomic"
)

type TrafficStats struct {
	DirectTx uint64 `json:"direct_tx"`
	DirectRx uint64 `json:"direct_rx"`
	ProxyTx  uint64 `json:"proxy_tx"`
	ProxyRx  uint64 `json:"proxy_rx"`
}

var (
	trafficDirectTx uint64
	trafficDirectRx uint64
	trafficProxyTx  uint64
	trafficProxyRx  uint64
)

const (
	trafficKindDirect = 0
	trafficKindProxy  = 1
)

type countingConn struct {
	net.Conn
	kind int
}

func (c *countingConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	if n > 0 {
		if c.kind == trafficKindProxy {
			atomic.AddUint64(&trafficProxyRx, uint64(n))
		} else {
			atomic.AddUint64(&trafficDirectRx, uint64(n))
		}
	}
	return n, err
}

func (c *countingConn) Write(p []byte) (int, error) {
	n, err := c.Conn.Write(p)
	if n > 0 {
		if c.kind == trafficKindProxy {
			atomic.AddUint64(&trafficProxyTx, uint64(n))
		} else {
			atomic.AddUint64(&trafficDirectTx, uint64(n))
		}
	}
	return n, err
}

func wrapConnForTrafficStats(conn net.Conn, shouldProxy bool) net.Conn {
	if conn == nil {
		return conn
	}
	kind := trafficKindDirect
	if shouldProxy {
		kind = trafficKindProxy
	}
	return &countingConn{Conn: conn, kind: kind}
}

func SnapshotTrafficStats() TrafficStats {
	return TrafficStats{
		DirectTx: atomic.LoadUint64(&trafficDirectTx),
		DirectRx: atomic.LoadUint64(&trafficDirectRx),
		ProxyTx:  atomic.LoadUint64(&trafficProxyTx),
		ProxyRx:  atomic.LoadUint64(&trafficProxyRx),
	}
}

func ResetTrafficStats() {
	atomic.StoreUint64(&trafficDirectTx, 0)
	atomic.StoreUint64(&trafficDirectRx, 0)
	atomic.StoreUint64(&trafficProxyTx, 0)
	atomic.StoreUint64(&trafficProxyRx, 0)
}
EOF

# Patch upstream dialTarget() to wrap direct/proxy sockets so we can attribute traffic.
echo "Patching dialTarget for traffic stats..."
python3 - <<PY
from __future__ import annotations

import pathlib

path = pathlib.Path("${SUDOKU_DIR}") / "internal/app/client.go"
data = path.read_text(encoding="utf-8")

needle = "func dialTarget("
start = data.find(needle)
if start == -1:
    raise SystemExit("dialTarget not found in internal/app/client.go (upstream changed?)")

brace_start = data.find("{", start)
if brace_start == -1:
    raise SystemExit("dialTarget brace not found")

level = 0
end = None
for i in range(brace_start, len(data)):
    ch = data[i]
    if ch == "{":
        level += 1
    elif ch == "}":
        level -= 1
        if level == 0:
            end = i + 1
            break

if end is None:
    raise SystemExit("dialTarget function end not found")

func_text = data[start:end]
if "wrapConnForTrafficStats" in func_text:
    raise SystemExit(0)

before_proxy = "return conn, true"
after_proxy = "return wrapConnForTrafficStats(conn, true), true"
before_direct = "return dConn, true"
after_direct = "return wrapConnForTrafficStats(dConn, false), true"

if before_proxy not in func_text or before_direct not in func_text:
    raise SystemExit("dialTarget returns not found (upstream changed?)")

func_text = func_text.replace(before_proxy, after_proxy, 1)
func_text = func_text.replace(before_direct, after_direct, 1)

path.write_text(data[:start] + func_text + data[end:], encoding="utf-8")
print("Patched", path)
PY

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
	app.ResetTrafficStats()

	var cfg config.Config
	if err := json.Unmarshal([]byte(jsonConfig), &cfg); err != nil {
		return fmt.Errorf("parse config: %w", err)
	}

	// Backward compatibility for legacy names.
	switch cfg.HTTPMaskMode {
	case "xhttp":
		cfg.HTTPMaskMode = "stream"
	case "pht":
		cfg.HTTPMaskMode = "poll"
	}
	if err := cfg.Finalize(); err != nil {
		return err
	}

	inst, err := app.StartMobileClient(&cfg)
	if err != nil {
		return err
	}
	instance = inst
	return nil
}

func GetTrafficStatsJson() string {
	stats := app.SnapshotTrafficStats()
	b, err := json.Marshal(stats)
	if err != nil {
		return "{}"
	}
	return string(b)
}

func ResetTrafficStats() {
	app.ResetTrafficStats()
}

func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if instance != nil {
		instance.Stop()
		instance = nil
	}
	app.ResetTrafficStats()
}
EOF

# Build AAR
echo "Building AAR..."
mkdir -p "$(dirname "${OUT_AAR}")"
pushd "${SUDOKU_DIR}" >/dev/null
go get -d golang.org/x/mobile/bind
"${GOMOBILE_BIN}" bind \
  -target="${GOMOBILE_TARGETS}" \
  -androidapi "${ANDROID_API_LEVEL}" \
  -javapkg com.futaiii.sudoku \
  -o "${OUT_AAR}" \
  ./pkg/mobile
popd >/dev/null

# Cleanup
rm -r "${WORK_DIR}" 2>/dev/null || true
echo "Generated ${OUT_AAR}"
