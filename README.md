# Sudodroid (Android client for sudoku)


[![Latest Release](https://img.shields.io/github/v/release/saba-futai/sudoku-android?style=for-the-badge)](https://github.com/saba-futai/sudoku-android/releases)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg?style=for-the-badge)](./LICENSE)

Sudodroid is a thin Android shell around the upstream [sudoku](https://github.com/SUDOKU-ASCII/sudoku) Go core. The UI is written with Kotlin + Jetpack Compose, while all protocol/transport logic is compiled into an AAR via `gomobile`. Highlights:

- Full node editor with validation, proxy modes (Global/Direct/PAC), padding tweaks, and a toggle for packed (bandwidth-optimized) downlink.
- Quick Settings tile to start/stop the VPN, plus notification traffic stats split by DIRECT vs PROXY.
- Import/export `sudoku://` short links, copy them straight into the clipboard, and rename nodes inline.
- Foreground VPN service that starts the Go core, binds a local mixed proxy, and bridges the device TUN interface through `hev-socks5-tunnel`.
- Built-in latency probes for each node (runs the thin Kotlin dialer without affecting the active tunnel).
- Keeps all configuration inside Jetpack DataStore; node selection survives process restarts.

## Architecture

```
┌──────────────┐    gomobile bind     ┌────────────────────┐
│  Android UI  │ ───────────────►     │  mobile/ (Go pkg)  │
│ (Compose)    │                      │  uses internal/app │
└──────────────┘                      └────────┬───────────┘
        │                                      │
        │ start/stop                           │ listens on 127.0.0.1:mixed_port
        ▼                                      ▼
┌──────────────────────┐      socks5      ┌─────────────────────┐
│ SudokuVpnService     │ ◄─────────────── │ hev-socks5-tunnel   │
│  (VpnService)        │   tun fd via JNI │  (ndk-build)        │
└──────────────────────┘                  └─────────────────────┘
```

The Go binding lives in `/mobile` and emits `sudodroid/app/libs/sudoku.aar`. `GoCoreClient` calls into that AAR to start/stop the upstream mixed proxy. The Kotlin UI is strictly for data entry, persistence, and calling into the native cores.

## Native tunnel

We pull [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) as a submodule and build it via `ndk-build`, with a tiny JNI bridge that feeds the TUN fd and socks5 upstream (local mixed proxy). Make sure the submodule is present (if you unpacked a ZIP, clone it into `sudodroid/third_party/hev-socks5-tunnel`), and have NDK r26.1 available:

```bash
git submodule update --init --recursive   # or git clone https://github.com/heiher/hev-socks5-tunnel sudodroid/third_party/hev-socks5-tunnel
./gradlew assembleRelease
```

## Build

- Install Go >= 1.24, Android cmdline-tools, and NDK r26.1.
- `./gradlew assembleRelease`

During `preBuild`, Gradle will:

1. Ensure `third_party/hev-socks5-tunnel` (and its submodules) are present.
2. Run `scripts/build_sudoku_aar.sh`, which clones upstream `sudoku` at `SUDOKU_REF` (default: `v0.1.7`), executes `gomobile bind` (default targets: `android/arm,android/arm64`) on `./pkg/mobile`, and drops the AAR into `app/libs/`.

Artifacts live in `app/build/outputs/apk/<variant>/`.

## Notes

- Sudoku tables are generated with a Go-compatible RNG and key normalization, so keys pasted from the Go CLI (private or public) produce matching tables.
- The VPN service creates the VPN session, excludes the app itself from the VPN to avoid socket loops, and hands the TUN file descriptor to `hev-socks5-tunnel`.
- Downlink mode (pure Sudoku vs. packed) is controlled via the node editor and matches the upstream `enable_pure_downlink` flag and `sudoku://` short link `x` field.

## Continuous integration

`.github/workflows/android.yml` contains the GitHub Actions recipe used locally:

- Installs Temurin JDK 17, Go 1.24, Android cmdline-tools + platform packages, and NDK r26.1.
- Builds the `gomobile` AAR via `scripts/build_sudoku_aar.sh`.
- Runs `./gradlew :app:assembleRelease` and uploads the resulting APK as an artifact.
