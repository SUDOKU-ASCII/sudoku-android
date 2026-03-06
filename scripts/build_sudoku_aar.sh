#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.."; pwd)"
WORK_DIR="${ROOT}/build_work"
SUDOKU_REPO="https://github.com/SUDOKU-ASCII/sudoku.git"
# v0.3.0 (upstream main). This script patches the v0.3.x upstream layout.
# If you need v0.2.x, use an older Sudodroid commit (or adjust patches accordingly).
SUDOKU_REF="${SUDOKU_REF:-main}"
SUDOKU_DIR="${WORK_DIR}/sudoku"
PATCH_DIR="${ROOT}/scripts/sudoku_patches"
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

# Upstream may set a stricter Go patch version (e.g. go 1.26.2) than what's installed locally.
# Relax to "go 1.26" (major.minor only) to keep builds working across patch releases.
echo "Normalizing upstream go.mod go version..."
SUDOKU_DIR="${SUDOKU_DIR}" python3 - <<'PY'
from __future__ import annotations

import os
import pathlib
import re

path = pathlib.Path(os.environ["SUDOKU_DIR"]) / "go.mod"
if not path.exists():
    raise SystemExit(0)
data = path.read_text(encoding="utf-8")

def repl(m: re.Match[str]) -> str:
    major = m.group(1)
    minor = m.group(2)
    return f"go {major}.{minor}"

new = re.sub(r"(?m)^go\s+(\d+)\.(\d+)\.\d+\s*$", repl, data)
if new != data:
    path.write_text(new, encoding="utf-8")
PY

if [[ -d "${PATCH_DIR}" ]]; then
  echo "Overlaying local sudoku patches from ${PATCH_DIR}..."
  cp -R "${PATCH_DIR}/." "${SUDOKU_DIR}/"
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
    m = re.search(r'(?m)^(?P<indent>\s*)ProxyMode\s+string\s+`json:"proxy_mode"`.*$', data)
    if m:
        indent = m.group("indent")
        insert_line = indent + 'IPMode             string   `json:"ip_mode"`             // "default", "ipv4_only", "ipv6_preferred"'
        data = data[: m.end()] + "\n" + insert_line + data[m.end() :]
    else:
        m2 = re.search(r'(?m)^(?P<indent>\s*)RuleURLs\s+\\[\\]string\\s+`json:"rule_urls"`.*$', data)
        if not m2:
            raise SystemExit(f"Failed to patch {path}: Config struct shape changed")
        indent = m2.group("indent")
        insert_line = indent + 'IPMode             string   `json:"ip_mode"`             // "default", "ipv4_only", "ipv6_preferred"'
        data = data[: m2.end()] + "\n" + insert_line + data[m2.end() :]

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
    # New upstream layout (v0.3.0): routing + dial logic split into client_route.go / client_target.go.
    route_path = root / "internal/app/client_route.go"
    target_path = root / "internal/app/client_target.go"
    socks5_path = root / "internal/app/client_socks5.go"

    route = route_path.read_text(encoding="utf-8")
    if "resolveAddrWithIPMode(" not in route:
        anchor = "var lookupIPsWithCache"
        idx = route.find(anchor)
        if idx == -1:
            raise SystemExit("lookupIPsWithCache not found in internal/app/client_route.go (upstream changed?)")
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
        route = route[:idx] + helpers + route[idx:]

    # Make PAC DNS resolution honor cfg.IPMode.
    needle = "\t\tips, err := lookupIPsWithCache(ctx, host)"
    if needle in route and "orderIPsByMode(ips, cfg.IPMode)" not in route:
        route = route.replace(needle, needle + "\n\t\tips = orderIPsByMode(ips, cfg.IPMode)", 1)

    # UDP direct resolution: thread ip_mode through resolveUDPAddr().
    if "func resolveUDPAddr(ctx context.Context, addr string, ipMode string)" not in route:
        route = route.replace(
            "func resolveUDPAddr(ctx context.Context, addr string) (*net.UDPAddr, error) {",
            "func resolveUDPAddr(ctx context.Context, addr string, ipMode string) (*net.UDPAddr, error) {",
            1,
        )
        route = route.replace(
            "resolved, err := dnsutil.ResolveWithCache(ctx, addr)",
            "resolved, err := resolveAddrWithIPMode(ctx, addr, ipMode)\n\tif err != nil {\n\t\tresolved, err = dnsutil.ResolveWithCache(ctx, addr)\n\t}",
            1,
        )
    route_path.write_text(route, encoding="utf-8")
    print("Patched", route_path)

    socks5 = socks5_path.read_text(encoding="utf-8")
    socks5 = socks5.replace(
        "directAddr, err := resolveUDPAddr(ctx, decision.directAddr)",
        "directAddr, err := resolveUDPAddr(ctx, decision.directAddr, s.cfg.IPMode)",
        1,
    )
    socks5_path.write_text(socks5, encoding="utf-8")
    print("Patched", socks5_path)

    target = target_path.read_text(encoding="utf-8")
    before = 'dConn, err := directDial("tcp", directAddr, 5*time.Second)'
    if before in target and 'dialAddr := directAddr' not in target:
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
        target = target.replace(before, after, 1)

    before2 = 'dConn, err = directDial("tcp", destAddrStr, 5*time.Second)'
    if before2 in target and "dialAddr2 :=" not in target:
        after2 = (
            "dialAddr2 := destAddrStr\n"
            "\t\tresolveCtx2, resolveCancel2 := context.WithTimeout(context.Background(), 2*time.Second)\n"
            "\t\tif resolved2, rerr2 := resolveAddrWithIPMode(resolveCtx2, dialAddr2, cfg.IPMode); rerr2 == nil && strings.TrimSpace(resolved2) != \"\" {\n"
            "\t\t\tdialAddr2 = resolved2\n"
            "\t\t}\n"
            "\t\tresolveCancel2()\n"
            "\t\tdConn, err = directDial(\"tcp\", dialAddr2, 5*time.Second)"
        )
        target = target.replace(before2, after2, 1)

    target_path.write_text(target, encoding="utf-8")
    print("Patched", target_path)

patch_config_struct()
patch_finalize()
patch_client_dns_pref()
PY

# Patch upstream dialTarget() to wrap direct/proxy sockets so we can attribute traffic.
echo "Patching dialTarget for traffic stats..."
python3 - <<PY
from __future__ import annotations

import pathlib

path = pathlib.Path("${SUDOKU_DIR}") / "internal/app/client_target.go"
data = path.read_text(encoding="utf-8")

needle = "func dialTarget("
start = data.find(needle)
if start == -1:
    raise SystemExit("dialTarget not found in internal/app/client_target.go (upstream changed?)")

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
    -tags=sudoku_patch \
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
