#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.."; pwd)"
WORK_DIR="${ROOT}/build_work"
SUDOKU_REPO="https://github.com/SUDOKU-ASCII/sudoku.git"
SUDOKU_REF="${SUDOKU_REF:-v0.2.5}"
SUDOKU_DIR="${WORK_DIR}/sudoku"
OUT_AAR="${ROOT}/app/libs/sudoku.aar"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-21}"
GOMOBILE_BIN="${GOMOBILE_BIN:-gomobile}"
GOMOBILE_TARGETS="${GOMOBILE_TARGETS:-android/arm,android/arm64}"
KEEP_WORK_DIR="${KEEP_WORK_DIR:-0}"
SKIP_GOMOBILE_BIND="${SKIP_GOMOBILE_BIND:-0}"

# Ensure gomobile is installed (unless we're explicitly skipping bind)
if [[ "${SKIP_GOMOBILE_BIND}" != "1" ]]; then
  if ! command -v "${GOMOBILE_BIN}" >/dev/null 2>&1; then
    fallback="$(go env GOPATH 2>/dev/null)/bin/gomobile"
    if [[ -x "${fallback}" ]]; then
      GOMOBILE_BIN="${fallback}"
    else
      echo "gomobile not found. Please install it first (or set GOMOBILE_BIN)."
      exit 1
    fi
  fi
fi

# Cleanup and Prep
rm -r "${WORK_DIR}" 2>/dev/null || true
mkdir -p "${WORK_DIR}"

# Fetch sudoku
echo "Fetching sudoku (${SUDOKU_REF})..."
if command -v git >/dev/null 2>&1; then
  if ! git clone --depth 1 --branch "${SUDOKU_REF}" "${SUDOKU_REPO}" "${SUDOKU_DIR}"; then
    echo "git clone failed; falling back to tarball download..."
    mkdir -p "${SUDOKU_DIR}"
    curl -fsSL "https://codeload.github.com/SUDOKU-ASCII/sudoku/tar.gz/${SUDOKU_REF}" \
      | tar -xz -C "${SUDOKU_DIR}" --strip-components=1
  fi
else
  echo "git not found; downloading tarball..."
  mkdir -p "${SUDOKU_DIR}"
  curl -fsSL "https://codeload.github.com/SUDOKU-ASCII/sudoku/tar.gz/${SUDOKU_REF}" \
    | tar -xz -C "${SUDOKU_DIR}" --strip-components=1
fi

# Patch upstream to support ip_mode for DNS resolution (IPv4/IPv6 preference).
echo "Patching upstream DNS ip_mode preference..."
SUDOKU_DIR="${SUDOKU_DIR}" python3 - <<'PY'
from __future__ import annotations

import os
import pathlib
import re

root = pathlib.Path(os.environ["SUDOKU_DIR"])

def patch_config_struct() -> None:
    path = root / "internal/config/config.go"
    data = path.read_text(encoding="utf-8")
    if 'json:"ip_mode"' in data:
        return

    # Insert after ProxyMode (or RuleURLs) to keep it near network settings.
    needle = 'ProxyMode          string       `json:"proxy_mode"`'
    insert = (
        needle
        + "\n"
        + '\tIPMode             string       `json:"ip_mode"`             // "default", "ipv4_only", "ipv6_preferred"'
    )
    if needle in data:
        data = data.replace(needle, insert, 1)
    else:
        # Fallback: insert after RuleURLs.
        needle2 = 'RuleURLs           []string     `json:"rule_urls"`'
        if needle2 not in data:
            raise SystemExit(f"Failed to patch {path}: Config struct shape changed")
        data = data.replace(
            needle2,
            needle2
            + '\n\tIPMode             string       `json:"ip_mode"`             // "default", "ipv4_only", "ipv6_preferred"',
            1,
        )

    path.write_text(data, encoding="utf-8")
    print("Patched", path)

def patch_finalize() -> None:
    path = root / "internal/config/finalize.go"
    data = path.read_text(encoding="utf-8")
    if "normalizeIPMode(" not in data:
        # Add helper near other normalizers (right after normalizeProxyMode).
        start = data.find("func normalizeProxyMode(")
        if start == -1:
            raise SystemExit("normalizeProxyMode not found (upstream changed?)")
        brace_start = data.find("{", start)
        if brace_start == -1:
            raise SystemExit("normalizeProxyMode brace not found")
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
            raise SystemExit("normalizeProxyMode end not found")
        helper = (
            "\n"
            "func normalizeIPMode(mode string) string {\n"
            "\tswitch normalizeLower(mode) {\n"
            '\tcase "", "default":\n'
            '\t\treturn "default"\n'
            '\tcase "ipv4_only", "ipv4":\n'
            '\t\treturn "ipv4_only"\n'
            '\tcase "ipv6_preferred", "ipv6":\n'
            '\t\treturn "ipv6_preferred"\n'
            "\tdefault:\n"
            '\t\treturn "default"\n'
            "\t}\n"
            "}\n"
        )
        data = data[:end] + helper + data[end:]

    # Normalize in Finalize().
    if "c.IPMode =" not in data:
        needle = "c.CustomTable = strings.TrimSpace(c.CustomTable)"
        if needle not in data:
            raise SystemExit("Finalize() normalization anchor not found (upstream changed?)")
        data = data.replace(needle, needle + "\n\tc.IPMode = normalizeIPMode(c.IPMode)", 1)

    path.write_text(data, encoding="utf-8")
    print("Patched", path)

def patch_client_dns_pref() -> None:
    path = root / "internal/app/client.go"
    data = path.read_text(encoding="utf-8")
    if "resolveAddrWithIPMode(" not in data:
        # Insert helpers right before peerKey() to keep near hostOnly().
        anchor = "func peerKey("
        idx = data.find(anchor)
        if idx == -1:
            raise SystemExit("peerKey not found in internal/app/client.go (upstream changed?)")
        helpers = (
            "\n"
            "func normalizeIPModeForClient(mode string) string {\n"
            "\tswitch strings.ToLower(strings.TrimSpace(mode)) {\n"
            '\tcase "ipv4_only", "ipv4":\n'
            '\t\treturn "ipv4_only"\n'
            '\tcase "ipv6_preferred", "ipv6":\n'
            '\t\treturn "ipv6_preferred"\n'
            "\tdefault:\n"
            '\t\treturn "default"\n'
            "\t}\n"
            "}\n"
            "\n"
            "func orderIPsByMode(ips []net.IP, mode string) []net.IP {\n"
            "\tif len(ips) == 0 {\n"
            "\t\treturn ips\n"
            "\t}\n"
            "\tmode = normalizeIPModeForClient(mode)\n"
            "\tv4 := make([]net.IP, 0, len(ips))\n"
            "\tv6 := make([]net.IP, 0, len(ips))\n"
            "\tfor _, ip := range ips {\n"
            "\t\tif ip == nil {\n"
            "\t\t\tcontinue\n"
            "\t\t}\n"
            "\t\tif ip.To4() != nil {\n"
            "\t\t\tv4 = append(v4, ip)\n"
            "\t\t} else {\n"
            "\t\t\tv6 = append(v6, ip)\n"
            "\t\t}\n"
            "\t}\n"
            "\tswitch mode {\n"
            '\tcase "ipv4_only":\n'
            "\t\treturn v4\n"
            '\tcase "ipv6_preferred":\n'
            "\t\treturn append(v6, v4...)\n"
            "\tdefault:\n"
            "\t\treturn append(v4, v6...)\n"
            "\t}\n"
            "}\n"
            "\n"
            "func resolveAddrWithIPMode(ctx context.Context, addr string, mode string) (string, error) {\n"
            "\taddr = strings.TrimSpace(addr)\n"
            "\tif addr == \"\" {\n"
            "\t\treturn \"\", fmt.Errorf(\"empty address\")\n"
            "\t}\n"
            "\thost, port, err := net.SplitHostPort(addr)\n"
            "\tif err != nil {\n"
            "\t\treturn \"\", err\n"
            "\t}\n"
            "\thost = strings.TrimPrefix(host, \"[\")\n"
            "\thost = strings.TrimSuffix(host, \"]\")\n"
            "\tif ip := net.ParseIP(host); ip != nil {\n"
            "\t\treturn net.JoinHostPort(ip.String(), port), nil\n"
            "\t}\n"
            "\tif ctx == nil {\n"
            "\t\tctx = context.Background()\n"
            "\t}\n"
            "\tips, err := lookupIPsWithCache(ctx, host)\n"
            "\tif err != nil {\n"
            "\t\treturn \"\", err\n"
            "\t}\n"
            "\tips = orderIPsByMode(ips, mode)\n"
            "\tif len(ips) == 0 {\n"
            "\t\treturn \"\", fmt.Errorf(\"no usable ip found for host %s\", host)\n"
            "\t}\n"
            "\treturn net.JoinHostPort(ips[0].String(), port), nil\n"
            "}\n"
            "\n"
        )
        data = data[:idx] + helpers + data[idx:]

    # Make PAC DNS resolution honor cfg.IPMode.
    needle = "ips, err := lookupIPsWithCache(ctx, host)"
    if needle in data and "orderIPsByMode(ips, cfg.IPMode)" not in data:
        data = data.replace(needle, needle + "\n\t\tips = orderIPsByMode(ips, cfg.IPMode)", 1)

    # Make direct dial honor cfg.IPMode (avoid OS resolver default order).
    before = 'dConn, err := directDial("tcp", directAddr, 5*time.Second)'
    if before in data and "resolveAddrWithIPMode" in data and 'dialAddr := directAddr' not in data:
        after = (
            "dialAddr := directAddr\n"
            "\tresolveCtx, resolveCancel := context.WithTimeout(context.Background(), 2*time.Second)\n"
            "\tif resolved, rerr := resolveAddrWithIPMode(resolveCtx, dialAddr, cfg.IPMode); rerr == nil && strings.TrimSpace(resolved) != \"\" {\n"
            "\t\tdialAddr = resolved\n"
            "\t}\n"
            "\tresolveCancel()\n"
            "\n"
            '\tdConn, err := directDial("tcp", dialAddr, 5*time.Second)'
        )
        data = data.replace(before, after, 1)

    before2 = 'dConn, err = directDial("tcp", destAddrStr, 5*time.Second)'
    if before2 in data and "resolveAddrWithIPMode" in data and "dialAddr2 :=" not in data:
        after2 = (
            "dialAddr2 := destAddrStr\n"
            "\t\t\tresolveCtx2, resolveCancel2 := context.WithTimeout(context.Background(), 2*time.Second)\n"
            "\t\t\tif resolved2, rerr2 := resolveAddrWithIPMode(resolveCtx2, dialAddr2, cfg.IPMode); rerr2 == nil && strings.TrimSpace(resolved2) != \"\" {\n"
            "\t\t\t\tdialAddr2 = resolved2\n"
            "\t\t\t}\n"
            "\t\t\tresolveCancel2()\n"
            "\t\t\tdConn, err = directDial(\"tcp\", dialAddr2, 5*time.Second)"
        )
        data = data.replace(before2, after2, 1)

    # UDP direct resolution: thread ip_mode through resolveUDPAddr().
    data = data.replace(
        "directAddr, err := resolveUDPAddr(ctx, decision.directAddr)",
        "directAddr, err := resolveUDPAddr(ctx, decision.directAddr, s.cfg.IPMode)",
        1,
    )

    if "func resolveUDPAddr(ctx context.Context, addr string, ipMode string)" not in data:
        data = data.replace(
            "func resolveUDPAddr(ctx context.Context, addr string) (*net.UDPAddr, error) {",
            "func resolveUDPAddr(ctx context.Context, addr string, ipMode string) (*net.UDPAddr, error) {",
            1,
        )
        data = data.replace(
            "resolved, err := dnsutil.ResolveWithCache(ctx, addr)",
            "resolved, err := resolveAddrWithIPMode(ctx, addr, ipMode)\n\tif err != nil {\n\t\tresolved, err = dnsutil.ResolveWithCache(ctx, addr)\n\t}",
            1,
        )

    path.write_text(data, encoding="utf-8")
    print("Patched", path)

patch_config_struct()
patch_finalize()
patch_client_dns_pref()
PY

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
cat <<'EOF' > "${SUDOKU_DIR}/pkg/mobile/mobile.go"
package mobile

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
	"github.com/saba-futai/sudoku/internal/app"
	"github.com/saba-futai/sudoku/internal/config"
	"github.com/saba-futai/sudoku/internal/tunnel"
)

const sudokuTCPSubprotocol = "sudoku-tcp-v1"

type reverseForwardStatus struct {
	Running    bool   `json:"running"`
	ListenAddr string `json:"listen_addr"`
	DialURL    string `json:"dial_url"`
	Insecure   bool   `json:"insecure"`
	LastError  string `json:"last_error"`
}

type reverseForwardInstance struct {
	ln         net.Listener
	done       chan struct{}
	listenAddr string
	dialURL    string
	insecure   bool
}

var (
	mu              sync.Mutex
	instance        *app.MobileInstance
	reverseInstance *reverseForwardInstance
	reverseStatus   reverseForwardStatus
	coreLocalPort   int32
)

func dialSocks5(ctx context.Context, proxyAddr, targetAddr string) (net.Conn, error) {
	proxyAddr = strings.TrimSpace(proxyAddr)
	targetAddr = strings.TrimSpace(targetAddr)
	if proxyAddr == "" {
		return nil, fmt.Errorf("empty proxy address")
	}
	if targetAddr == "" {
		return nil, fmt.Errorf("empty target address")
	}

	host, portStr, err := net.SplitHostPort(targetAddr)
	if err != nil {
		return nil, err
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port <= 0 || port > 65535 {
		return nil, fmt.Errorf("invalid port: %q", portStr)
	}

	if ctx == nil {
		ctx = context.Background()
	}
	conn, err := (&net.Dialer{}).DialContext(ctx, "tcp", proxyAddr)
	if err != nil {
		return nil, err
	}

	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}

	fail := func(e error) (net.Conn, error) {
		_ = conn.Close()
		return nil, e
	}

	// Greeting: VER=5, NMETHODS=1, METHODS=[0x00 no-auth]
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		return fail(err)
	}
	choice := make([]byte, 2)
	if _, err := io.ReadFull(conn, choice); err != nil {
		return fail(err)
	}
	if choice[0] != 0x05 || choice[1] != 0x00 {
		return fail(fmt.Errorf("socks5 no-auth not accepted"))
	}

	// CONNECT request.
	req := make([]byte, 0, 6+len(host))
	req = append(req, 0x05, 0x01, 0x00) // VER, CMD=CONNECT, RSV

	if ip := net.ParseIP(host); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			req = append(req, 0x01)
			req = append(req, ip4...)
		} else if ip16 := ip.To16(); ip16 != nil {
			req = append(req, 0x04)
			req = append(req, ip16...)
		} else {
			return fail(fmt.Errorf("invalid ip: %q", host))
		}
	} else {
		if len(host) > 255 {
			return fail(fmt.Errorf("domain too long"))
		}
		req = append(req, 0x03, byte(len(host)))
		req = append(req, []byte(host)...)
	}

	portBuf := make([]byte, 2)
	binary.BigEndian.PutUint16(portBuf, uint16(port))
	req = append(req, portBuf...)

	if _, err := conn.Write(req); err != nil {
		return fail(err)
	}

	// Reply: VER, REP, RSV, ATYP, BND.ADDR, BND.PORT
	hdr := make([]byte, 4)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		return fail(err)
	}
	if hdr[0] != 0x05 {
		return fail(fmt.Errorf("invalid socks5 reply version"))
	}
	if hdr[1] != 0x00 {
		return fail(fmt.Errorf("socks5 connect failed (rep=%d)", hdr[1]))
	}

	atyp := hdr[3]
	var addrLen int
	switch atyp {
	case 0x01:
		addrLen = 4
	case 0x04:
		addrLen = 16
	case 0x03:
		l := make([]byte, 1)
		if _, err := io.ReadFull(conn, l); err != nil {
			return fail(err)
		}
		addrLen = int(l[0])
	default:
		return fail(fmt.Errorf("unknown socks5 atyp=%d", atyp))
	}
	if addrLen > 0 {
		if _, err := io.ReadFull(conn, make([]byte, addrLen)); err != nil {
			return fail(err)
		}
	}
	if _, err := io.ReadFull(conn, make([]byte, 2)); err != nil {
		return fail(err)
	}

	_ = conn.SetDeadline(time.Time{})
	return conn, nil
}

func dialTCPWithFallback(ctx context.Context, network, addr string) (net.Conn, error) {
	if ctx == nil {
		ctx = context.Background()
	}

	directCtx, directCancel := context.WithTimeout(ctx, 3*time.Second)
	conn, err := (&net.Dialer{}).DialContext(directCtx, network, addr)
	directCancel()
	if err == nil {
		return conn, nil
	}

	port := atomic.LoadInt32(&coreLocalPort)
	if port <= 0 || port > 65535 {
		return nil, err
	}
	proxyAddr := fmt.Sprintf("127.0.0.1:%d", port)
	pConn, pErr := dialSocks5(ctx, proxyAddr, addr)
	if pErr == nil {
		return pConn, nil
	}
	return nil, fmt.Errorf("dial failed (direct: %v; via socks5(%s): %v)", err, proxyAddr, pErr)
}

func wsHTTPClientForDial(insecure bool, scheme string) *http.Client {
	tr := &http.Transport{
		ForceAttemptHTTP2: false,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return dialTCPWithFallback(ctx, network, addr)
		},
	}
	if insecure && strings.EqualFold(scheme, "wss") {
		tr.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
	}
	return &http.Client{Transport: tr}
}

func stopReverseForwarderLocked() {
	if reverseInstance == nil {
		reverseStatus.Running = false
		reverseStatus.LastError = ""
		return
	}

	ln := reverseInstance.ln
	done := reverseInstance.done
	reverseInstance = nil

	if ln != nil {
		_ = ln.Close()
	}
	if done != nil {
		select {
		case <-done:
		case <-time.After(1500 * time.Millisecond):
			log.Printf("[Mobile][Reverse] timeout while stopping forwarder")
		}
	}
	reverseStatus.Running = false
	reverseStatus.LastError = ""
}

func startReverseForwarderLocked(listenAddr, dialURL string, insecure bool) error {
	listenAddr = strings.TrimSpace(listenAddr)
	dialURL = strings.TrimSpace(dialURL)
	if listenAddr == "" {
		return fmt.Errorf("missing listen address")
	}
	if dialURL == "" {
		return fmt.Errorf("missing dial url")
	}

	u, err := url.Parse(dialURL)
	if err != nil || u == nil {
		return fmt.Errorf("invalid dial url: %q", dialURL)
	}
	switch strings.ToLower(u.Scheme) {
	case "ws", "wss":
	default:
		return fmt.Errorf("dial url must be ws:// or wss:// (got %q)", u.Scheme)
	}
	if strings.TrimSpace(u.Host) == "" {
		return fmt.Errorf("dial url missing host: %q", dialURL)
	}

	wsHTTPClient := wsHTTPClientForDial(insecure, u.Scheme)

	// Preflight: validate dialURL + subprotocol once so we fail early (instead of "Running" + SSH reset).
	{
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		ws, _, err := websocket.Dial(ctx, dialURL, &websocket.DialOptions{
			Subprotocols:    []string{sudokuTCPSubprotocol},
			CompressionMode: websocket.CompressionDisabled,
			HTTPClient:      wsHTTPClient,
		})
		cancel()
		if err != nil {
			return fmt.Errorf("preflight: %w", err)
		}
		if ws.Subprotocol() != sudokuTCPSubprotocol {
			_ = ws.Close(websocket.StatusPolicyViolation, "subprotocol required")
			return fmt.Errorf("server did not accept %s", sudokuTCPSubprotocol)
		}
		_ = ws.Close(websocket.StatusNormalClosure, "ok")
	}

	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		return err
	}

	inst := &reverseForwardInstance{
		ln:         ln,
		done:       make(chan struct{}),
		listenAddr: listenAddr,
		dialURL:    dialURL,
		insecure:   insecure,
	}
	reverseInstance = inst
	reverseStatus = reverseForwardStatus{
		Running:    true,
		ListenAddr: listenAddr,
		DialURL:    dialURL,
		Insecure:   insecure,
		LastError:  "",
	}

	go func(localInst *reverseForwardInstance) {
		defer close(localInst.done)
		for {
			c, err := localInst.ln.Accept()
			if err != nil {
				if errors.Is(err, net.ErrClosed) {
					return
				}
				mu.Lock()
				reverseStatus.LastError = err.Error()
				mu.Unlock()
				continue
			}

			go func(local net.Conn) {
				if local == nil {
					return
				}
				defer local.Close()

				ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
				ws, _, err := websocket.Dial(ctx, localInst.dialURL, &websocket.DialOptions{
					Subprotocols:    []string{sudokuTCPSubprotocol},
					CompressionMode: websocket.CompressionDisabled,
					HTTPClient:      wsHTTPClient,
				})
				cancel()
				if err != nil {
					mu.Lock()
					reverseStatus.LastError = err.Error()
					mu.Unlock()
					return
				}
				if ws.Subprotocol() != sudokuTCPSubprotocol {
					_ = ws.Close(websocket.StatusPolicyViolation, "subprotocol required")
					mu.Lock()
					reverseStatus.LastError = "server did not accept sudoku-tcp-v1"
					mu.Unlock()
					return
				}

				mu.Lock()
				reverseStatus.LastError = ""
				mu.Unlock()

				wsConn := websocket.NetConn(context.Background(), ws, websocket.MessageBinary)
				tunnel.PipeConn(local, wsConn)
			}(c)
		}
	}(inst)

	return nil
}

func StartReverseForwarder(listenAddr, dialURL string, insecure bool) error {
	mu.Lock()
	defer mu.Unlock()

	stopReverseForwarderLocked()
	return startReverseForwarderLocked(listenAddr, dialURL, insecure)
}

func StopReverseForwarder() {
	mu.Lock()
	defer mu.Unlock()
	stopReverseForwarderLocked()
}

func GetReverseForwardStatusJson() string {
	mu.Lock()
	status := reverseStatus
	mu.Unlock()
	b, err := json.Marshal(status)
	if err != nil {
		return "{}"
	}
	return string(b)
}

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
	switch strings.ToLower(strings.TrimSpace(cfg.HTTPMask.Mode)) {
	case "xhttp":
		cfg.HTTPMask.Mode = "stream"
	case "pht":
		cfg.HTTPMask.Mode = "poll"
	}
	if err := cfg.Finalize(); err != nil {
		return err
	}
	atomic.StoreInt32(&coreLocalPort, int32(cfg.LocalPort))

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
if [[ "${SKIP_GOMOBILE_BIND}" == "1" ]]; then
  echo "Skipping gomobile bind (SKIP_GOMOBILE_BIND=1)"
else
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
fi

# Cleanup
if [[ "${KEEP_WORK_DIR}" == "1" ]]; then
  echo "Keeping ${WORK_DIR} (KEEP_WORK_DIR=1)"
else
  rm -r "${WORK_DIR}" 2>/dev/null || true
fi
if [[ "${SKIP_GOMOBILE_BIND}" == "1" ]]; then
  echo "Skipped AAR generation (SKIP_GOMOBILE_BIND=1); expected output: ${OUT_AAR}"
else
  echo "Generated ${OUT_AAR}"
fi
