# Sudodroid (Android client for sudoku)


[![Latest Release](https://img.shields.io/github/v/release/SUDOKU-ASCII/sudoku-android?style=for-the-badge)](https://github.com/SUDOKU-ASCII/sudoku-android/releases)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg?style=for-the-badge)](./LICENSE)

Current release: `v0.3.2`, with Sudoku core `v0.5.1` and hev-socks5-tunnel `2.18.0`.

Sudodroid is a thin Android shell around the upstream [sudoku](https://github.com/SUDOKU-ASCII/sudoku) Go core. The UI is written with Kotlin + Jetpack Compose, while all protocol/transport logic is compiled into an AAR via `gomobile`. Highlights:

- Full node editor with validation, per-node transport tuning (padding / HTTP mask / custom tables), and a toggle for packed (bandwidth-optimized) downlink.
- Global network settings card (shared by all nodes): routing mode (Global/Direct/PAC) and PAC rule URLs, including direct rules, `-`/`_`-prefixed proxy rules, and `!`-prefixed reject rules.
- Routing mode changes are saved immediately and restart the active proxy core; the PAC save button only applies edited rule URLs.
- URL rule downloads use the upstream persistent cache with an Android app-private cache directory for offline startup fallback.
- Proxy-only mode (no VPN required): starts Sudoku core and local mixed proxy directly for app-level proxy use cases.
- Reverse TCP-over-WebSocket local forwarder UI (no VPN needed), equivalent to `./sudoku -rev-dial ... -rev-listen ...` for local SSH/RDP style hops.
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
│  (VpnService)        │   tun fd via JNI │  (upstream AAR)     │
└──────────────────────┘                  └─────────────────────┘
```

The Go binding lives in `scripts/sudoku_patches/pkg/mobile` and emits `app/libs/sudoku.aar`. `GoCoreClient` calls into that AAR to start/stop the upstream mixed proxy. The Kotlin UI is strictly for data entry, persistence, and calling into the native cores.

## Native tunnel

Gradle downloads the [hev-socks5-tunnel 2.18.0 Android AAR](https://github.com/heiher/hev-socks5-tunnel/releases/tag/2.18.0) and verifies its SHA-256. The AAR includes the native libraries and the default `hev.htproxy.TProxyService` JNI binding; the VPN service passes its TUN fd to that binding. No separate tunnel source checkout or `ndk-build` step is needed.

```bash
./gradlew assembleRelease
```

## Build

- Install Go 1.26.4 (or enable Go toolchain auto-selection), Android cmdline-tools, and NDK r26.1.
- `./gradlew assembleRelease`

During `preBuild`, Gradle will:

1. Download and verify the hev-socks5-tunnel `2.18.0` AAR into `app/libs/`.
2. Run `scripts/build_sudoku_aar.sh`, which clones upstream `sudoku` at `SUDOKU_REF` (default: tag `v0.5.1`), overlays the Android mobile entrypoints under `scripts/sudoku_patches/`, executes `gomobile bind` (default targets: `android/arm,android/arm64`) on `./pkg/mobile`, and drops the AAR into `app/libs/`.

Artifacts live in `app/build/outputs/apk/<variant>/`.

## Notes

- Sudoku tables are generated with a Go-compatible RNG and key normalization, so keys pasted from the Go CLI (private or public) produce matching tables.
- The VPN service creates the VPN session, excludes the app itself from the VPN to avoid socket loops, and hands the TUN file descriptor to `hev-socks5-tunnel`.
- Downlink mode (pure Sudoku vs. packed) is controlled via the node editor and matches the upstream `enable_pure_downlink` flag and `sudoku://` short link `x` field.

## Continuous integration

`.github/workflows/android.yml` contains the GitHub Actions recipe used locally:

- Installs Temurin JDK 17, Go 1.26.4, Android cmdline-tools + platform packages, and NDK r26.1.
- Builds the `gomobile` AAR via `scripts/build_sudoku_aar.sh` and downloads the verified hev-socks5-tunnel AAR during Gradle `preBuild`.
- Runs `./gradlew :app:assembleRelease` and uploads the resulting APK as an artifact.
