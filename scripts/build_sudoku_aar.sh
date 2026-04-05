#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.."; pwd)"
WORK_DIR="${ROOT}/build_work"
SUDOKU_REPO="https://github.com/SUDOKU-ASCII/sudoku.git"
# Default to the upstream v0.4.2 tag.
SUDOKU_REF="${SUDOKU_REF:-v0.4.2}"
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

if [[ "${SKIP_GOMOBILE_BIND}" != "1" ]]; then
  export PATH="$(dirname "${GOMOBILE_BIN}"):${PATH}"
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

# Patch upstream dialTarget() to wrap direct/proxy sockets so we can attribute traffic.
echo "Patching dialTarget for traffic stats..."
python3 - <<PY
from __future__ import annotations

import pathlib
import re

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

def replace_return(text: str, conn_name: str, should_proxy: bool) -> tuple[str, int]:
    pattern = re.compile(
        rf"return\s+{re.escape(conn_name)}\s*,\s*((?:[A-Za-z_][A-Za-z0-9_]*\s*,\s*)?)true"
    )

    def repl(match: re.Match[str]) -> str:
        middle = match.group(1) or ""
        return f"return wrapConnForTrafficStats({conn_name}, {str(should_proxy).lower()}), {middle}true"

    return pattern.subn(repl, text, count=1)

func_text, proxy_count = replace_return(func_text, "conn", True)
func_text, direct_count = replace_return(func_text, "dConn", False)

if proxy_count != 1 or direct_count != 1:
    raise SystemExit("dialTarget success returns not found (upstream changed?)")

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
