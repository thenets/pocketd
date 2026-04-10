#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "rich>=13.0",
# ]
# ///
"""
pocketd device controller — start/stop the LLM server, inspect state, and
forward ports via ADB.

Usage:
    ./tools/control.py status
    ./tools/control.py start [OPTIONS]
    ./tools/control.py stop
    ./tools/control.py restart [OPTIONS]
    ./tools/control.py log [-n LINES] [-f]
    ./tools/control.py forward [--port PORT]
    ./tools/control.py test [--prompt TEXT]
    ./tools/control.py info
    ./tools/control.py models
    ./tools/control.py ui-tap BUTTON_TEXT
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import textwrap
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

# ── Constants ─────────────────────────────────────────────────────────────────

PACKAGE = "dev.thenets.pocketd"
SERVICE = f"{PACKAGE}/.service.LlmServerService"
ACTIVITY = f"{PACKAGE}/.ui.MainActivity"
ACTION_STOP = f"{PACKAGE}.STOP_SERVER"
DEFAULT_MODEL = "/sdcard/Download/pocketd/Gemma-4-E2B-it/gemma-4-E2B-it.litertlm"
LEGACY_MODEL = "/sdcard/Download/gemma-4-E2B-it.litertlm"
DEFAULT_PORT = 8080
DEFAULT_CONTEXT = 4096
DEFAULT_TOP_K = 64
DEFAULT_BACKEND = "GPU_WITH_CPU_FALLBACK"
BACKENDS = ["CPU", "GPU", "NPU", "GPU_WITH_CPU_FALLBACK"]

# Available models in the Gallery
AVAILABLE_MODELS = {
    "Gemma-4-E2B-it": "/sdcard/Download/pocketd/Gemma-4-E2B-it/gemma-4-E2B-it.litertlm",
    "Gemma-4-E4B-it": "/sdcard/Download/pocketd/Gemma-4-E4B-it/gemma-4-E4B-it.litertlm",
    "Gemma-3n-E2B-it": "/sdcard/Download/pocketd/Gemma-3n-E2B-it/gemma-3n-E2B-it.litertlm",
    "Gemma-3n-E4B-it": "/sdcard/Download/pocketd/Gemma-3n-E4B-it/gemma-3n-E4B-it.litertlm",
    "Gemma3-1B-IT": "/sdcard/Download/pocketd/Gemma3-1B-IT/gemma3-1b-it.litertlm",
    "Qwen2.5-1.5B-Instruct": "/sdcard/Download/pocketd/Qwen2.5-1.5B-Instruct/qwen2.5-1.5b-instruct.litertlm",
    "DeepSeek-R1-Distill-Qwen-1.5B": "/sdcard/Download/pocketd/DeepSeek-R1-Distill-Qwen-1.5B/deepseek-r1-distill-qwen-1.5b.litertlm",
    "TinyGarden-270M": "/sdcard/Download/pocketd/TinyGarden-270M/tinygarden-270m.litertlm",
    "MobileActions-270M": "/sdcard/Download/pocketd/MobileActions-270M/mobileactions-270m.litertlm",
}

# Locate ADB relative to this script (project root / .cache / …)
PROJECT_ROOT = Path(__file__).resolve().parent.parent
ADB_PATH = PROJECT_ROOT / ".cache" / "android-sdk" / "platform-tools" / "adb"


def adb() -> str:
    """Return path to adb, falling back to PATH."""
    if ADB_PATH.exists():
        return str(ADB_PATH)
    found = shutil.which("adb")
    if found:
        return found
    print("ERROR: adb not found. Run 'make setup' first.", file=sys.stderr)
    sys.exit(1)


# ── ADB helpers ──────────────────────────────────────────────────────────────

def run(cmd: list[str], *, check: bool = True, capture: bool = True,
        timeout: int = 30) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(cmd, capture_output=capture, text=True,
                              check=check, timeout=timeout)
    except subprocess.TimeoutExpired:
        print(f"TIMEOUT: {' '.join(cmd)}", file=sys.stderr)
        sys.exit(1)
    except subprocess.CalledProcessError as e:
        if capture:
            print(e.stderr or e.stdout or str(e), file=sys.stderr)
        sys.exit(e.returncode)


def adb_shell(cmd: str, **kw) -> subprocess.CompletedProcess:
    return run([adb(), "shell", cmd], **kw)


def get_device() -> Optional[str]:
    """Return first connected device serial, or None."""
    r = run([adb(), "devices"], check=False)
    for line in r.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            return parts[0]
    return None


def require_device() -> str:
    d = get_device()
    if not d:
        print("ERROR: No device/emulator connected.", file=sys.stderr)
        sys.exit(1)
    return d


# ── Device info ──────────────────────────────────────────────────────────────

@dataclass
class DeviceInfo:
    serial: str = ""
    model: str = ""
    brand: str = ""
    sdk: str = ""
    abi: str = ""
    android_version: str = ""
    total_ram_mb: int = 0
    avail_ram_mb: int = 0
    battery_pct: int = 0
    battery_temp: float = 0.0


def get_device_info() -> DeviceInfo:
    info = DeviceInfo()
    info.serial = require_device()
    props = {
        "model": "ro.product.model",
        "brand": "ro.product.brand",
        "sdk": "ro.build.version.sdk",
        "abi": "ro.product.cpu.abi",
        "android_version": "ro.build.version.release",
    }
    for attr, prop in props.items():
        r = adb_shell(f"getprop {prop}", check=False)
        setattr(info, attr, r.stdout.strip())

    # RAM from /proc/meminfo
    r = adb_shell("cat /proc/meminfo", check=False)
    for line in r.stdout.splitlines():
        if line.startswith("MemTotal:"):
            info.total_ram_mb = int(line.split()[1]) // 1024
        elif line.startswith("MemAvailable:"):
            info.avail_ram_mb = int(line.split()[1]) // 1024

    # Battery
    r = adb_shell("dumpsys battery", check=False)
    for line in r.stdout.splitlines():
        line = line.strip()
        if line.startswith("level:"):
            info.battery_pct = int(line.split(":")[1].strip())
        elif line.startswith("temperature:"):
            info.battery_temp = int(line.split(":")[1].strip()) / 10.0

    return info


# ── Server state ─────────────────────────────────────────────────────────────

@dataclass
class ServerStatus:
    running: bool = False
    pid: int = 0
    port: int = 0
    backend: str = ""
    model: str = ""
    uptime: str = ""
    app_ram_mb: int = 0


def get_server_status() -> ServerStatus:
    status = ServerStatus()

    # Check if the service process is running
    r = adb_shell(f"pidof {PACKAGE}", check=False)
    pid_str = r.stdout.strip()
    if pid_str:
        status.pid = int(pid_str.split()[0])

    # Parse logcat for latest server state (use grep instead of -s tag filter
    # for reliability across devices)
    r = adb_shell(
        "logcat -d -t 200 | grep -E 'LlmServerService|HttpServer'",
        check=False,
    )
    for line in r.stdout.splitlines():
        if "Server running" in line:
            status.running = True
            m = re.search(r"port=(\d+)", line)
            if m:
                status.port = int(m.group(1))
            m = re.search(r"backend=(\S+)", line)
            if m:
                status.backend = m.group(1)
            m = re.search(r"model=(\S+)", line)
            if m:
                status.model = m.group(1)
        elif "Service destroyed" in line or "Server stopped" in line:
            status.running = False
            status.port = 0

    # Also check if port is actually listening (most reliable signal)
    if status.pid and not status.running:
        # Check /proc/net/tcp6 and tcp for port 8080 (0x1F90) in LISTEN state (0A)
        for tcp_file in ("cat /proc/net/tcp", "cat /proc/net/tcp6"):
            r = adb_shell(tcp_file, check=False)
            for line in r.stdout.splitlines():
                if ":1F90" in line.upper():
                    fields = line.split()
                    if len(fields) > 3 and fields[3] == "0A":
                        status.running = True
                        status.port = status.port or DEFAULT_PORT
                        break
            if status.running:
                break

    # Last resort: try an HTTP probe via port-forward
    if status.pid and not status.running:
        run([adb(), "forward", "tcp:18080", "tcp:8080"], check=False)
        try:
            req = urllib.request.Request("http://localhost:18080/v1/models")
            with urllib.request.urlopen(req, timeout=2) as resp:
                if resp.status == 200:
                    status.running = True
                    status.port = DEFAULT_PORT
        except Exception:
            pass
        finally:
            run([adb(), "forward", "--remove", "tcp:18080"], check=False)

    # App memory usage
    if status.pid:
        r = adb_shell(f"cat /proc/{status.pid}/status", check=False)
        for line in r.stdout.splitlines():
            if line.startswith("VmRSS:"):
                status.app_ram_mb = int(line.split()[1]) // 1024
                break

    return status


# ── Commands ─────────────────────────────────────────────────────────────────

def cmd_status(args: argparse.Namespace) -> None:
    from rich.console import Console
    from rich.table import Table

    console = Console()
    require_device()

    server = get_server_status()
    device = get_device_info()

    # Device table
    dt = Table(title="Device", show_header=False, border_style="dim")
    dt.add_column("Key", style="bold")
    dt.add_column("Value")
    dt.add_row("Serial", device.serial)
    dt.add_row("Model", f"{device.brand} {device.model}")
    dt.add_row("Android", f"{device.android_version} (SDK {device.sdk})")
    dt.add_row("ABI", device.abi)
    dt.add_row("RAM", f"{device.avail_ram_mb:,} MB free / {device.total_ram_mb:,} MB total")
    dt.add_row("Battery", f"{device.battery_pct}% ({device.battery_temp}\u00b0C)")
    console.print(dt)
    console.print()

    # Server table
    st = Table(title="Server", show_header=False, border_style="dim")
    st.add_column("Key", style="bold")
    st.add_column("Value")
    if server.running:
        st.add_row("Status", "[bold green]RUNNING[/]")
        st.add_row("PID", str(server.pid))
        st.add_row("Port", str(server.port))
        st.add_row("Backend", server.backend)
        st.add_row("Model", server.model)
        if server.app_ram_mb:
            st.add_row("App RSS", f"{server.app_ram_mb:,} MB")
    else:
        st.add_row("Status", "[bold red]STOPPED[/]")
        if server.pid:
            st.add_row("PID", f"{server.pid} (app running, server stopped)")
    console.print(st)


def cmd_start(args: argparse.Namespace) -> None:
    from rich.console import Console
    console = Console()
    require_device()

    # Launch the activity first (required for foreground service)
    run([adb(), "shell", "am", "start", "-n", ACTIVITY], capture=False)
    time.sleep(1)

    # Try starting service directly — if it fails (permission), use UI tap
    port = args.port or DEFAULT_PORT
    backend = args.backend or DEFAULT_BACKEND
    context = args.context or DEFAULT_CONTEXT
    top_k = args.top_k or DEFAULT_TOP_K
    model = args.model or DEFAULT_MODEL

    if backend not in BACKENDS:
        console.print(f"[red]Invalid backend: {backend}[/]")
        console.print(f"Valid: {', '.join(BACKENDS)}")
        sys.exit(1)

    if not (1 <= top_k <= 128):
        console.print(f"[red]Invalid top-k: {top_k}[/]")
        console.print(f"Valid range: 1-128")
        sys.exit(1)

    # Android requires foreground services to be started from the app itself.
    # Use UI automation to tap the Start Server button.
    console.print(f"[dim]Starting server: port={port} backend={backend} context={context} top_k={top_k}[/]")

    # Tap the Start Server button via uiautomator (auto-scrolls to find it)
    _tap_button("Start Server", console)

    # Wait for server to start
    console.print("[dim]Waiting for server...[/]")
    for _ in range(15):
        time.sleep(1)
        st = get_server_status()
        if st.running:
            console.print(f"[bold green]Server started[/] on port {st.port} (backend={st.backend})")

            # Auto-forward port
            run([adb(), "forward", f"tcp:{st.port}", f"tcp:{st.port}"], check=False)
            console.print(f"[dim]Port forwarded: localhost:{st.port} -> device:{st.port}[/]")
            return

    console.print("[yellow]Server may not have started. Check 'status' or 'log'.[/]")


def cmd_stop(args: argparse.Namespace) -> None:
    from rich.console import Console
    console = Console()
    require_device()

    # Bring app to foreground first
    run([adb(), "shell", "am", "start", "-n", ACTIVITY], capture=True, check=False)
    time.sleep(1)

    # Try tapping Stop Server in UI (auto-scrolls to find it)
    _tap_button("Stop Server", console)

    time.sleep(2)

    st = get_server_status()
    if not st.running:
        console.print("[bold green]Server stopped.[/]")
    else:
        console.print("[yellow]Server may still be running. Check 'status'.[/]")


def cmd_restart(args: argparse.Namespace) -> None:
    from rich.console import Console
    console = Console()
    require_device()

    st = get_server_status()
    if st.running:
        console.print("[dim]Stopping server...[/]")
        _tap_button("Stop Server", console)
        time.sleep(2)

    cmd_start(args)


def cmd_log(args: argparse.Namespace) -> None:
    require_device()
    grep_pat = "LlmEngine|HttpServer|LlmServerService"
    n = args.lines or 30

    if args.follow:
        # Stream logs with grep filter
        cmd = [adb(), "logcat", "-T", "1"]
        try:
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, text=True)
            for line in proc.stdout:
                if re.search(grep_pat, line):
                    print(line, end="")
        except KeyboardInterrupt:
            pass
        finally:
            proc.terminate()
    else:
        r = adb_shell(
            f"logcat -d -t 500 | grep -E '{grep_pat}' | tail -n {n}",
            check=False,
        )
        print(r.stdout)


def cmd_forward(args: argparse.Namespace) -> None:
    from rich.console import Console
    console = Console()
    require_device()

    port = args.port or DEFAULT_PORT
    run([adb(), "forward", f"tcp:{port}", f"tcp:{port}"])
    console.print(f"[green]Forwarded[/] localhost:{port} -> device:{port}")


def cmd_test(args: argparse.Namespace) -> None:
    from rich.console import Console
    console = Console()
    require_device()

    port = args.port or DEFAULT_PORT

    # Ensure port is forwarded
    run([adb(), "forward", f"tcp:{port}", f"tcp:{port}"], check=False)

    base = f"http://localhost:{port}"

    # Test 1: models endpoint
    console.print("\n[bold]1. GET /v1/models[/]")
    try:
        req = urllib.request.Request(f"{base}/v1/models")
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read())
            console.print(f"   [green]OK[/] {resp.status} — {json.dumps(data)}")
    except Exception as e:
        console.print(f"   [red]FAIL[/] {e}")
        return

    # Test 2: chat completion
    prompt = args.prompt or "What is 2+2? Answer in one word."
    console.print(f"\n[bold]2. POST /v1/chat/completions[/] (non-streaming)")
    console.print(f"   [dim]Prompt: {prompt}[/]")
    try:
        body = json.dumps({
            "messages": [{"role": "user", "content": prompt}],
        }).encode()
        req = urllib.request.Request(
            f"{base}/v1/chat/completions",
            data=body,
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read())
            answer = data["choices"][0]["message"]["content"]
            console.print(f"   [green]OK[/] {resp.status} — \"{answer}\"")
    except Exception as e:
        console.print(f"   [red]FAIL[/] {e}")
        return

    # Test 3: streaming
    console.print(f"\n[bold]3. POST /v1/chat/completions[/] (streaming)")
    try:
        body = json.dumps({
            "messages": [{"role": "user", "content": "Count from 1 to 3."}],
            "stream": True,
        }).encode()
        req = urllib.request.Request(
            f"{base}/v1/chat/completions",
            data=body,
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=120) as resp:
            chunks = 0
            full_text = []
            for line in resp:
                line = line.decode().strip()
                if line.startswith("data: ") and line != "data: [DONE]":
                    chunks += 1
                    try:
                        chunk = json.loads(line[6:])
                        c = chunk.get("choices", [{}])[0].get("delta", {}).get("content", "")
                        if c:
                            full_text.append(c)
                    except json.JSONDecodeError:
                        pass
            text = "".join(full_text)
            console.print(f"   [green]OK[/] {chunks} chunks — \"{text.strip()}\"")
    except Exception as e:
        console.print(f"   [red]FAIL[/] {e}")

    # Test 4: system instruction + temperature
    console.print(f"\n[bold]4. System instruction + temperature[/]")
    try:
        body = json.dumps({
            "messages": [
                {"role": "system", "content": "Always respond with exactly one word."},
                {"role": "user", "content": "What color is the sky?"},
            ],
            "temperature": 0.1,
        }).encode()
        req = urllib.request.Request(
            f"{base}/v1/chat/completions",
            data=body,
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read())
            answer = data["choices"][0]["message"]["content"]
            console.print(f"   [green]OK[/] \"{answer}\"")
    except Exception as e:
        console.print(f"   [red]FAIL[/] {e}")

    console.print()


def cmd_models(args: argparse.Namespace) -> None:
    from rich.console import Console
    from rich.table import Table
    console = Console()

    # List available models with their paths
    table = Table(title="Available Models", show_header=True, border_style="dim")
    table.add_column("Model Name", style="bold cyan")
    table.add_column("Path", style="dim")
    table.add_column("Status", style="bold")

    for name, path in AVAILABLE_MODELS.items():
        table.add_row(name, path, "[dim]Not checked[/]")

    console.print(table)
    console.print("\nUse with: [bold]./tools/control.py start --model <name>[/]")
    console.print("Example:  [bold]./tools/control.py start --model Gemma-4-E2B-it[/]")


def cmd_info(args: argparse.Namespace) -> None:
    from rich.console import Console
    from rich.table import Table
    console = Console()
    require_device()

    # Model file info (check both new and legacy paths)
    console.print("[bold]Model files[/]")
    models_found = []

    # Check new model directory structure
    r = adb_shell(f"find /sdcard/Download/pocketd -name '*.litertlm' 2>/dev/null", check=False)
    for line in r.stdout.strip().splitlines():
        if line:
            models_found.append(line)
            console.print(f"  [green]Found[/]: {line}")

    # Check legacy model path
    r = adb_shell(f"ls -la {LEGACY_MODEL}", check=False)
    if r.returncode == 0:
        models_found.append(LEGACY_MODEL)
        console.print(f"  [green]Found (legacy)[/]: {LEGACY_MODEL}")

    if not models_found:
        console.print(f"  [yellow]No models found in /sdcard/Download/pocketd/ or {LEGACY_MODEL}[/]")

    # App info
    console.print("\n[bold]App package[/]")
    r = adb_shell(f"pm list packages | grep {PACKAGE}", check=False)
    if PACKAGE in r.stdout:
        console.print(f"  [green]Installed[/]: {PACKAGE}")
        r2 = adb_shell(f"dumpsys package {PACKAGE} | grep versionName", check=False)
        for line in r2.stdout.splitlines():
            if "versionName" in line:
                console.print(f"  Version: {line.strip()}")
                break
    else:
        console.print(f"  [red]Not installed[/]")

    # Permissions
    console.print("\n[bold]Permissions[/]")
    r = adb_shell(f"dumpsys package {PACKAGE} | grep 'granted=true'", check=False)
    for line in r.stdout.splitlines():
        perm = line.strip()
        if perm:
            # shorten android.permission. prefix
            perm = perm.replace("android.permission.", "")
            console.print(f"  {perm}")

    # Port listening
    console.print("\n[bold]Network[/]")
    r = adb_shell("cat /proc/net/tcp", check=False)
    listening_8080 = False
    for line in r.stdout.splitlines():
        # port 8080 = 0x1F90
        if ":1F90" in line.upper() and "0A" in line.split()[3:4]:
            listening_8080 = True
    console.print(f"  Port 8080: {'[green]listening[/]' if listening_8080 else '[dim]not listening[/]'}")

    # Engine logs
    console.print("\n[bold]Recent engine activity[/]")
    r = adb_shell("logcat -d -t 500 | grep LlmEngine | tail -n 10", check=False)
    for line in r.stdout.splitlines():
        parts = line.split("LlmEngine:", 1)
        if len(parts) > 1:
            console.print(f"  {parts[1].strip()}")


def cmd_ui_tap(args: argparse.Namespace) -> None:
    from rich.console import Console
    console = Console()
    require_device()
    _tap_button(args.text, console)


# ── UI automation helper ─────────────────────────────────────────────────────

def _tap_button(text: str, console, scroll_attempts: int = 3) -> bool:
    """Find a button/view by text in the UI hierarchy and tap its center.
    Scrolls down up to `scroll_attempts` times if the element is not visible."""
    for attempt in range(scroll_attempts + 1):
        adb_shell("uiautomator dump /sdcard/ui.xml", check=False)
        r = adb_shell("cat /sdcard/ui.xml", check=False)
        xml = r.stdout

        # Find bounds for the text
        pattern = rf'text="{re.escape(text)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
        m = re.search(pattern, xml)
        if not m:
            pattern = rf'content-desc="{re.escape(text)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
            m = re.search(pattern, xml)

        if m:
            x1, y1, x2, y2 = int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))
            cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
            console.print(f"[dim]Tapping '{text}' at ({cx}, {cy})[/]")
            adb_shell(f"input tap {cx} {cy}", check=False)
            return True

        if attempt < scroll_attempts:
            adb_shell("input swipe 500 1500 500 300 300", check=False)
            time.sleep(0.5)

    console.print(f"[red]Button '{text}' not found in UI[/]")
    return False


# ── CLI ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        prog="control.py",
        description="pocketd device controller",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=textwrap.dedent("""\
            examples:
              %(prog)s status                      Show device & server state
              %(prog)s start                       Start server (default settings)
              %(prog)s start --backend CPU         Start with CPU backend
              %(prog)s start --context 4096        Start with 4096 token context
              %(prog)s start --top-k 100           Start with top-k sampling at 100
              %(prog)s start --model Qwen2.5-1.5B-Instruct    Start with Qwen model
              %(prog)s stop                        Stop the server
              %(prog)s restart --backend GPU       Restart with GPU backend
              %(prog)s models                      List available models
              %(prog)s log -f                      Stream live logs
              %(prog)s test                        Run quick smoke tests
              %(prog)s test --prompt "Hi there"    Test with custom prompt
              %(prog)s info                        Show model, app, permission info
              %(prog)s forward                     Forward port 8080
        """),
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # status
    sub.add_parser("status", help="Show device and server status")

    # start
    p = sub.add_parser("start", help="Start the LLM server")
    p.add_argument("--port", type=int, help=f"Server port (default: {DEFAULT_PORT})")
    p.add_argument("--backend", choices=BACKENDS,
                   help=f"Inference backend (default: {DEFAULT_BACKEND})")
    p.add_argument("--context", type=int,
                   help=f"Context size in tokens (default: {DEFAULT_CONTEXT})")
    p.add_argument("--top-k", type=int, dest="top_k",
                   help=f"Top-K sampling parameter (default: {DEFAULT_TOP_K}, range: 1-128)")
    p.add_argument("--model", help=f"Model path on device (default: {DEFAULT_MODEL})")

    # stop
    sub.add_parser("stop", help="Stop the LLM server")

    # restart
    p = sub.add_parser("restart", help="Restart the LLM server")
    p.add_argument("--port", type=int, help=f"Server port (default: {DEFAULT_PORT})")
    p.add_argument("--backend", choices=BACKENDS,
                   help=f"Inference backend (default: {DEFAULT_BACKEND})")
    p.add_argument("--context", type=int,
                   help=f"Context size in tokens (default: {DEFAULT_CONTEXT})")
    p.add_argument("--top-k", type=int, dest="top_k",
                   help=f"Top-K sampling parameter (default: {DEFAULT_TOP_K}, range: 1-128)")
    p.add_argument("--model", help=f"Model path on device (default: {DEFAULT_MODEL})")

    # log
    p = sub.add_parser("log", help="View server logs")
    p.add_argument("-n", "--lines", type=int, default=30, help="Number of lines")
    p.add_argument("-f", "--follow", action="store_true", help="Stream live logs")

    # forward
    p = sub.add_parser("forward", help="Forward device port to localhost")
    p.add_argument("--port", type=int, help=f"Port to forward (default: {DEFAULT_PORT})")

    # test
    p = sub.add_parser("test", help="Run quick smoke tests against the server")
    p.add_argument("--port", type=int, help=f"Server port (default: {DEFAULT_PORT})")
    p.add_argument("--prompt", help="Custom prompt for chat test")

    # info
    sub.add_parser("info", help="Show model, app, and permission info")

    # models
    sub.add_parser("models", help="List available models from Gallery")

    # ui-tap
    p = sub.add_parser("ui-tap", help="Tap a UI element by its text")
    p.add_argument("text", help="Button/element text to tap")

    args = parser.parse_args()

    commands = {
        "status": cmd_status,
        "start": cmd_start,
        "stop": cmd_stop,
        "restart": cmd_restart,
        "log": cmd_log,
        "forward": cmd_forward,
        "test": cmd_test,
        "info": cmd_info,
        "models": cmd_models,
        "ui-tap": cmd_ui_tap,
    }
    commands[args.command](args)


if __name__ == "__main__":
    main()
