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

## Multi-model support

pocketd now supports 9 models from the LiteRT Community Gallery:

| Model | Size | Specialty |
|-------|------|-----------|
| **Gemma-4-E2B-it** | 4B | Default, balanced performance |
| Gemma-4-E4B-it | 4B | Extended variant |
| Gemma-3n-E2B-it | 3B | Efficient variant |
| Gemma-3n-E4B-it | 3B | Extended variant |
| Gemma3-1B-IT | 1B | Ultra-lightweight |
| Qwen2.5-1.5B-Instruct | 1.5B | Alternative instruction-tuned model |
| DeepSeek-R1-Distill-Qwen-1.5B | 1.5B | Reasoning-focused distill |
| TinyGarden-270M | 270M | Ultra-minimal |
| MobileActions-270M | 270M | Action-oriented ultra-minimal |

Models are stored in `/sdcard/Download/pocketd/<model-name>/<filename>`. Legacy models in `/sdcard/Download/*.litertlm` are still supported.

## Device control script

`tools/control.py` is a self-contained Python CLI (runs via `uv run --script`) for controlling the app on a connected device/emulator via ADB. It uses `uiautomator` to tap buttons in the app UI and HTTP probes to detect server state.

```bash
# Show device info + server status (running/stopped, port, backend, RAM)
./tools/control.py status

# Start the server (brings app to foreground, taps Start Server, forwards port)
./tools/control.py start
./tools/control.py start --backend CPU
./tools/control.py start --context 4096                          # Larger context (default: 4096)
./tools/control.py start --top-k 100                             # Configure sampling (default: 64, range: 1-128)
./tools/control.py start --model Qwen2.5-1.5B-Instruct          # Choose alternative model

# Stop the server
./tools/control.py stop

# Restart with different settings
./tools/control.py restart --backend GPU
./tools/control.py restart --top-k 50 --model Gemma3-1B-IT

# List available models
./tools/control.py models

# Stream live logs (Ctrl+C to stop)
./tools/control.py log -f

# View last N log lines
./tools/control.py log -n 50

# Run smoke tests (models, chat, streaming, system instructions)
./tools/control.py test
./tools/control.py test --prompt "Hello world"

# Show model files, app version, permissions, port status
./tools/control.py info

# Forward port manually
./tools/control.py forward

# Tap any UI element by its text
./tools/control.py ui-tap "Start Server"
```

The script auto-discovers `adb` from `.cache/android-sdk/platform-tools/adb` and falls back to `PATH`. It auto-scrolls the UI to find buttons that are off-screen.

### New sampler configuration

The `--top-k` flag (range 1-128, default 64) controls the top-K sampling parameter for inference. Smaller values = more deterministic, larger values = more diverse. Set via the UI's Top-K slider.

## Key source files

| File | Purpose |
|------|---------|
| `app/src/main/java/dev/thenets/pocketd/ui/MainActivity.kt` | All Compose UI — main screen, API activity log, request detail, model picker, top-K slider, backend selector |
| `app/src/main/java/dev/thenets/pocketd/server/HttpServer.kt` | Ktor/Netty HTTP server, OpenAI-compatible endpoints, sampler pass-through |
| `app/src/main/java/dev/thenets/pocketd/service/LlmServerService.kt` | Android foreground service wrapping the HTTP server — intent extras: EXTRA_TOP_K (Int) |
| `app/src/main/java/dev/thenets/pocketd/llm/LlmEngine.kt` | LiteRT-LM inference engine — BackendType, InferenceParams (topK parameter), ConversationConfig, cancel support |
| `app/src/main/java/dev/thenets/pocketd/llm/PromptFormatter.kt` | Converts OpenAI messages to Gemma prompt format, system instruction extraction |
| `app/src/main/java/dev/thenets/pocketd/llm/ToolCallParser.kt` | Parses `<tool_call>` XML from LLM output into OpenAI tool call format |
| `app/src/main/java/dev/thenets/pocketd/model/OpenAiModels.kt` | OpenAI-compatible data models — multimodal ChatMessage (JsonElement content), request/response types |
| `app/src/main/java/dev/thenets/pocketd/model/ApiLogEntry.kt` | Data model for logged HTTP requests |
| `tools/control.py` | Device controller CLI — start/stop server, status, logs, smoke tests, model listing via ADB |
