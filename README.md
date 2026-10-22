# Sudodroid (Android client for sudoku)


[![Latest Release](https://img.shields.io/github/v/release/SUDOKU-ASCII/sudoku-android?style=for-the-badge)](https://github.com/SUDOKU-ASCII/sudoku-android/releases)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg?style=for-the-badge)](./LICENSE)

Current release: `v0.3.3`, with Sudoku core [`v0.5.2.0`](https://github.com/SUDOKU-ASCII/sudoku/releases/tag/v0.5.2.0) (commit `56ce6f1aeea18aa69b9bc1d57557ed312b59e5f5`) and hev-socks5-tunnel `2.19.1`.

Sudodroid is a thin Android shell around the upstream [sudoku](https://github.com/SUDOKU-ASCII/sudoku) Go core. The UI is written with Kotlin + Jetpack Compose (Material 3, dark-first), while all protocol/transport logic is compiled into an AAR via `gomobile`. Highlights:

- **Live traffic, no patches.** The core is started with its loopback external controller enabled; the app streams `GET /traffic` for the speed readout, the session totals, the sparkline and the notification text. The stream is open only while something is looking (app in the foreground or screen on) and closes itself a few seconds after.
- **Live routing mode.** Global / Direct / PAC is switched with `PATCH /configs` on the running core, with no restart and no dropped connections (it applies to new connections). The PAC list is always handed to the core at start, so switching to PAC at runtime has rules to use.
- **Activity tab.** Open connections from `GET /connections` (target, direct vs proxy route, bytes each way), with per-connection and close-all actions. It polls every two seconds, only while visible.
- **Adaptive layout.** Bottom bar on phones, navigation rail on foldables and small tablets, rail plus two-pane layouts with bounded content width on large screens.
- Full node editor with validation, per-node transport tuning (padding / HTTP mask / custom tables), and a toggle for packed (bandwidth-optimized) downlink.
- PAC rule editor (shared by all nodes): direct rules, `-`/`_`-prefixed proxy rules, and `!`-prefixed reject rules. Rule lists are read at core start, so saving them reconnects a running session.
- URL rule downloads use the upstream persistent cache with an Android app-private cache directory for offline startup fallback.
- Proxy-only mode (no VPN required): starts Sudoku core and local mixed proxy directly for app-level proxy use cases.
- Reverse TCP-over-WebSocket local forwarder UI (no VPN needed), equivalent to `./sudoku -rev-dial ... -rev-listen ...` for local SSH/RDP style hops.
- Quick Settings tile to start/stop the VPN.
- Import/export `sudoku://` short links, copy them straight into the clipboard, and rename nodes inline.
- Foreground VPN service that starts the Go core, binds a local mixed proxy, and bridges the device TUN interface through `hev-socks5-tunnel`.
- Built-in latency probes for each node (runs the thin Kotlin dialer without affecting the active tunnel). A probe times a whole request (dial, Sudoku handshake, TLS, HTTP), about eight round trips, so the figure shown is that time divided by 8.
- Keeps all configuration inside Jetpack DataStore; node selection survives process restarts.

## Architecture

```
┌──────────────┐    gomobile bind     ┌────────────────────┐
│  Android UI  │ ───────────────►     │  mobile/ (Go pkg)  │
│ (Compose)    │                      │  uses internal/app │
└──────┬───────┘                      └────────┬───────────┘
       │ TrafficHub / ControllerApi            │ 127.0.0.1:local_port (mixed proxy)
       │ HTTP + bearer token                   │ 127.0.0.1:random   (external controller)
       ▼                                       ▼
┌──────────────────────┐      socks5      ┌─────────────────────┐
│ SudokuVpnService     │ ◄─────────────── │ hev-socks5-tunnel   │
│  (VpnService)        │   tun fd via JNI │  (upstream AAR)     │
└──────────────────────┘                  └─────────────────────┘
```

The Go binding lives in `scripts/sudoku_patches/pkg/mobile` and emits `app/libs/sudoku.aar`. `GoCoreClient` calls into that AAR to start/stop the upstream mixed proxy.

The external controller binds to loopback on a random free port and is protected by a random 128-bit secret generated on every core start. Both are kept in memory only (`ControllerApi`). `TrafficHub` turns the `/traffic` NDJSON stream into one shared `StateFlow` (subscribers: Home, the notifications); it connects on the first subscriber and disconnects three seconds after the last.

Battery notes: no polling timers while the screen is off, no infinite animations, a single shared traffic stream, notification updates only when the text changes, and Compose state collected lifecycle-aware.

## Native tunnel

Gradle downloads the [hev-socks5-tunnel 2.19.1 Android AAR](https://github.com/heiher/hev-socks5-tunnel/releases/tag/2.19.1) and verifies its SHA-256. The AAR includes the native libraries and the default `hev.htproxy.TProxyService` JNI binding; the VPN service passes its TUN fd to that binding. No separate tunnel source checkout or `ndk-build` step is needed.

```bash
./gradlew assembleRelease
```

## Build

- Android 10 (API 29) or newer is required by the upstream hev-socks5-tunnel AAR.
- Install Go 1.27.2 (or enable Go toolchain auto-selection), Android cmdline-tools, and an NDK (r26.1 recommended). The NDK is needed only by `gomobile bind` when building the Go AAR; the app module has no native code of its own, so Gradle does not pin an NDK version.
- `./gradlew assembleRelease`

During `preBuild`, Gradle will:

1. Download and verify the hev-socks5-tunnel `2.19.1` AAR into `app/libs/`.
2. Run `scripts/build_sudoku_aar.sh`, which clones upstream `sudoku` at `SUDOKU_REF` (default: commit `56ce6f1aeea18aa69b9bc1d57557ed312b59e5f5`, i.e. Sudoku `v0.5.2.0`), overlays the Android mobile entrypoints under `scripts/sudoku_patches/`, executes `gomobile bind` (default targets: `android/arm,android/arm64`) on `./pkg/mobile`, and drops the AAR into `app/libs/`.

Artifacts live in `app/build/outputs/apk/<variant>/`.

## Notes

- Sudoku tables are generated with a Go-compatible RNG and key normalization, so keys pasted from the Go CLI (private or public) produce matching tables.
- The VPN service creates the VPN session, excludes the app itself from the VPN to avoid socket loops, and hands the TUN file descriptor to `hev-socks5-tunnel`.
- Downlink mode (pure Sudoku vs. packed) is controlled via the node editor and matches the upstream `enable_pure_downlink` flag and `sudoku://` short link `x` field.

## Continuous integration

`.github/workflows/android.yml` contains the GitHub Actions recipe used locally:

- Installs Temurin JDK 17, Go 1.27.2, Android cmdline-tools + platform packages, and NDK r26.1.
- Builds the `gomobile` AAR via `scripts/build_sudoku_aar.sh` and downloads the verified hev-socks5-tunnel AAR during Gradle `preBuild`.
- Runs `./gradlew :app:assembleRelease` and uploads the resulting APK as an artifact.
