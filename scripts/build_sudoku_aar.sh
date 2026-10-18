#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.."; pwd)"
WORK_DIR="${ROOT}/build_work"
SUDOKU_REPO="https://github.com/SUDOKU-ASCII/sudoku.git"
# Default to the pinned upstream commit.
SUDOKU_REF="${SUDOKU_REF:-cce3162481e0a30fec928683deb99cc9276128b8}"
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
download_tarball() {
  echo "Downloading sudoku tarball (${SUDOKU_REF})..."
  rm -r "${SUDOKU_DIR}" 2>/dev/null || true
  mkdir -p "${SUDOKU_DIR}"
  curl -fsSL "https://codeload.github.com/SUDOKU-ASCII/sudoku/tar.gz/${SUDOKU_REF}" \
    | tar -xz -C "${SUDOKU_DIR}" --strip-components=1
}

fetched=0
if command -v git >/dev/null 2>&1; then
  if [[ "${SUDOKU_REF}" =~ ^[0-9a-fA-F]{40}$ ]]; then
    # `git clone --branch` rejects raw commit SHAs, so fetch the commit directly.
    echo "Fetching sudoku commit ${SUDOKU_REF}..."
    mkdir -p "${SUDOKU_DIR}"
    git -C "${SUDOKU_DIR}" init -q
    git -C "${SUDOKU_DIR}" remote add origin "${SUDOKU_REPO}"
    if git -C "${SUDOKU_DIR}" fetch --depth 1 origin "${SUDOKU_REF}" \
      && git -C "${SUDOKU_DIR}" checkout -q FETCH_HEAD; then
      fetched=1
    else
      echo "git fetch failed; falling back to tarball download..."
      rm -r "${SUDOKU_DIR}" 2>/dev/null || true
    fi
  elif git clone --depth 1 --branch "${SUDOKU_REF}" "${SUDOKU_REPO}" "${SUDOKU_DIR}"; then
    fetched=1
  else
    echo "git clone failed; falling back to tarball download..."
    rm -r "${SUDOKU_DIR}" 2>/dev/null || true
  fi
fi

if [[ "${fetched}" != "1" ]]; then
  download_tarball
fi

# Honor the exact upstream Go requirement. Go's toolchain auto-selection
# downloads it when necessary.
upstream_go_version="$(awk '$1 == "go" { print $2; exit }' "${SUDOKU_DIR}/go.mod")"
if [[ -n "${upstream_go_version}" && -z "${GOTOOLCHAIN:-}" ]]; then
  export GOTOOLCHAIN="go${upstream_go_version}+auto"
fi
echo "Using Go toolchain requirement ${upstream_go_version:-unknown} (${GOTOOLCHAIN:-default})"

if [[ -d "${PATCH_DIR}" ]]; then
  echo "Overlaying local sudoku patches from ${PATCH_DIR}..."
  cp -R "${PATCH_DIR}/." "${SUDOKU_DIR}/"
fi

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
