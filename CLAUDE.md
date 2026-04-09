# pocketd — Claude Code Guide

## Project overview

Android app that runs a local LLM (Gemma via LiteRT-LM) as an OpenAI-compatible HTTP server, exposed to the local network.

## Build toolchain

All tools are downloaded and cached in `.cache/` — no system-level Android SDK installation required.

Run `make setup` once to populate the cache, then `make build` to assemble the debug APK.

### `.cache/` directory layout

```
.cache/
├── android-sdk/
│   ├── platform-tools/
│   │   └── adb              ← Android Debug Bridge (device/emulator control)
│   │   └── fastboot         ← Fastboot (bootloader flashing, rarely needed)
│   │   └── sqlite3          ← SQLite CLI
│   ├── build-tools/         ← aapt2, d8, zipalign, apksigner
│   ├── platforms/           ← android-35 SDK jars
│   ├── cmdline-tools/
│   │   └── latest/bin/
│   │       ├── sdkmanager   ← SDK component installer
│   │       └── avdmanager   ← AVD (emulator image) manager
│   ├── emulator/
│   │   └── emulator         ← Android emulator binary
│   └── system-images/       ← Emulator OS images (x86_64, google_apis)
├── gradle-dist/             ← Gradle distribution (extracted)
└── gradle-home/             ← Gradle cache / build artefacts
```

### Common ADB commands

The `adb` binary lives at `.cache/android-sdk/platform-tools/adb`.

```bash
ADB=.cache/android-sdk/platform-tools/adb

# List connected devices / emulators
$ADB devices

# Install APK
$ADB install app/build/outputs/apk/debug/app-debug.apk

# View live logs from the app
$ADB logcat -s pocketd

# Forward device port 8080 to localhost
$ADB forward tcp:8080 tcp:8080

# Open a shell on the device
$ADB shell
```

Or use the Makefile shortcut:

```bash
make port-forward   # forwards TCP 8080 (emulator → host)
```

### Makefile targets

| Target           | Description                                               |
|------------------|-----------------------------------------------------------|
| `make setup`     | Download Android SDK + Gradle into `.cache/` (idempotent) |
| `make build`     | Assemble debug APK (runs `setup` if needed)               |
| `make emulator`  | Launch Android emulator (downloads system image on demand)|
| `make port-forward` | Forward TCP 8080 from emulator to host via adb         |
| `make tui`       | Build the TUI chat client (npm + tsc)                     |
| `make tui-dev`   | Build and launch the TUI client                           |
| `make clean`     | Delete build outputs                                      |
| `make distclean` | Delete build outputs AND `.cache/`                        |

## Key source files

| File | Purpose |
|------|---------|
| `app/src/main/java/dev/thenets/pocketd/ui/MainActivity.kt` | All Compose UI — main screen, API activity log, request detail |
| `app/src/main/java/dev/thenets/pocketd/server/HttpServer.kt` | Ktor/Netty HTTP server, OpenAI-compatible endpoints |
| `app/src/main/java/dev/thenets/pocketd/service/LlmServerService.kt` | Android foreground service wrapping the HTTP server |
| `app/src/main/java/dev/thenets/pocketd/llm/LlmEngine.kt` | LiteRT-LM inference engine wrapper |
| `app/src/main/java/dev/thenets/pocketd/model/ApiLogEntry.kt` | Data model for logged HTTP requests |
