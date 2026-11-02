#!/usr/bin/env python3
"""
pwebcam receiver

Install as a login daemon:   bash mac/install.sh
Manual run:                  python3 mac/receiver.py

Architecture
------------
pyvirtualcam stays open the whole time so the virtual camera is always
visible to Teams/OBS/etc.  The phone only connects (and its camera only
turns on) when an app actually opens the virtual camera.
When the call ends and the app closes the camera, the phone disconnects.

Detection
---------
Watches the OBS camera extension's own log for two specific events:
  _clientQueue_addStreamingClient    → camera opened by a consumer app
  _clientQueue_removeStreamingClient → camera closed by a consumer app
These are the ground-truth signals emitted by CoreMediaIO itself.
"""

import http.client
import io
import logging
import os
import queue
import re
import signal
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path
from urllib.parse import urlsplit

import numpy as np
import pyvirtualcam
from PIL import Image


# ── Config (.env) ─────────────────────────────────────────────────────────────
# Optional mac/.env file — see mac/.env.example for all keys.

def _load_env() -> dict:
    env_path = Path(__file__).parent / ".env"
    cfg: dict = {}
    if not env_path.exists():
        return cfg
    for line in env_path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" in line:
            k, _, v = line.partition("=")
            cfg[k.strip()] = v.strip()
    return cfg

_cfg = _load_env()

# ── ADB device targeting ───────────────────────────────────────────────────────
# With multiple ADB devices (USB phone + TCP TV box etc.) plain `adb` commands
# fail with "error: more than one device/emulator".  Set DEVICE or CONNECTION
# in mac/.env to pin the receiver to one device.
#
# ADB_DEVICE_ARGS is prepended to every adb invocation:
#   DEVICE=<serial>      → ["-s", "<serial>"]   most explicit
#   CONNECTION=usb       → ["-d"]               any single USB device
#   CONNECTION=tcpip     → ["-e"]               any single TCP/IP device
#   (nothing set)        → []                   works only when exactly one device

_DEVICE     = _cfg.get("DEVICE", "").strip()
_CONNECTION = _cfg.get("CONNECTION", "").strip().lower()

if _DEVICE:
    ADB_DEVICE_ARGS: list = ["-s", _DEVICE]
elif _CONNECTION == "usb":
    ADB_DEVICE_ARGS = ["-d"]
elif _CONNECTION == "tcpip":
    ADB_DEVICE_ARGS = ["-e"]
else:
    ADB_DEVICE_ARGS = []

# ── Camera selection ──────────────────────────────────────────────────────────
# PREFER_CAMERA2 = True  → port 8081 (Camera2 API; hardware/software JPEG toggled in-app)
# PREFER_CAMERA2 = False → port 8080 (Camera1 API; software JPEG, most compatible)
# Note: Camera2 is throttled on LIMITED HALs (e.g. Kirin 659 / Huawei FIG-LX1).
#       Use True only on devices with FULL Camera2 support (e.g. Pixel).
_prefer_cam2_env = _cfg.get("PREFER_CAMERA2", "").strip().lower()
if _prefer_cam2_env in ("true", "1", "yes"):
    PREFER_CAMERA2 = True
elif _prefer_cam2_env in ("false", "0", "no"):
    PREFER_CAMERA2 = False
else:
    PREFER_CAMERA2 = True  # default

# ── Transport ─────────────────────────────────────────────────────────────────
# usb (default) | wifi | auto  — see mac/.env.example
TRANSPORT = _cfg.get("TRANSPORT", "usb").strip().lower()
if TRANSPORT not in ("usb", "wifi", "auto"):
    TRANSPORT = "usb"
PHONE_HOST = _cfg.get("PHONE_HOST", "").strip()
MDNS_TYPE  = "_pwebcam._tcp"

# DEBUG_TIMING=1 logs, once a second, frame rate, worst inter-frame gap and decode/send cost.
DEBUG_TIMING = _cfg.get("DEBUG_TIMING", os.environ.get("DEBUG_TIMING", "")).strip().lower() in ("1", "true", "yes")

# Camera settings (EV, focus) are stored on the phone and controlled via the
# app UI. The receiver connects with a plain URL — no params needed.

PHONE_PORT_CAM1 = 8080
PHONE_PORT_CAM2 = 8081
PHONE_PACKAGE   = "com.pwebcam"
PHONE_SERVICE   = "com.pwebcam/.CameraService"
WIDTH, HEIGHT = 1280, 720
# Virtual camera output rate (FPS= in .env). The phone still sends ~30 fps; a higher value just
# makes the extension re-serve the latest frame sooner, shrinking sampling jitter.
try:
    FPS = max(1, min(120, int(_cfg.get("FPS", "30"))))
except ValueError:
    FPS = 30
# Seconds to wait after the last "remove client" before declaring camera closed.
# Handles apps that briefly disconnect/reconnect (e.g. switching camera sources).
CLOSE_DEBOUNCE = 2.0
# Seconds between black keep-alive frames while no app has the camera open.
IDLE_KEEPALIVE = 1.0
# Seconds between adb probes while the camera is open but the phone is absent.
ADB_RETRY_INTERVAL = 5.0


def _find_free_port(start: int = 32000) -> int:
    """Return the first free TCP port on localhost at or above *start*."""
    for port in range(start, 65536):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    raise RuntimeError(f"No free port found above {start}")


# Local ports chosen once at startup (above 32000, no conflicts with common services).
_LOCAL_PORT_CAM1 = _find_free_port(32000)
_LOCAL_PORT_CAM2 = _find_free_port(_LOCAL_PORT_CAM1 + 1)

LOCAL_PORT = _LOCAL_PORT_CAM2 if PREFER_CAMERA2 else _LOCAL_PORT_CAM1
PHONE_PORT = PHONE_PORT_CAM2  if PREFER_CAMERA2 else PHONE_PORT_CAM1

logging.basicConfig(
    format="%(asctime)s %(levelname)-8s %(message)s",
    datefmt="%H:%M:%S",
    level=logging.INFO,
    stream=sys.stdout,
)
log = logging.getLogger("pwebcam")

_running     = threading.Event()
_running.set()
_camera_open = threading.Event()   # set while any app has the camera open
_BLACK       = np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8)


# ── ADB ──────────────────────────────────────────────────────────────────────

def ensure_phone_app() -> None:
    """Start CameraService on the phone if it is not already running."""
    try:
        r = subprocess.run(
            ["adb"] + ADB_DEVICE_ARGS + ["shell", "dumpsys", "activity", "services", PHONE_PACKAGE],
            capture_output=True, text=True, timeout=5,
        )
        if "(nothing)" not in r.stdout and PHONE_PACKAGE in r.stdout:
            return  # already running
    except Exception:
        return

    log.info("Starting pwebcam app on phone…")
    try:
        r = subprocess.run(
            ["adb"] + ADB_DEVICE_ARGS + ["shell", "am", "start-foreground-service", "-n", PHONE_SERVICE],
            capture_output=True, text=True, timeout=5,
        )
        if r.returncode != 0:
            # Fallback for older Android: launch the activity instead
            subprocess.run(
                ["adb"] + ADB_DEVICE_ARGS + ["shell", "am", "start", "-n", f"{PHONE_PACKAGE}/.MainActivity"],
                capture_output=True, timeout=5,
            )
    except Exception as e:
        log.debug("ensure_phone_app: %s", e)


def adb_forward() -> bool:
    """Return True when the target device is present and port forwards are set up."""
    try:
        if not ADB_DEVICE_ARGS:
            # No specific device configured — require exactly one device to avoid
            # "error: more than one device/emulator" on the forward commands.
            out = subprocess.run(
                ["adb", "devices"], capture_output=True, text=True, timeout=5
            ).stdout
            if "\tdevice" not in out:
                return False

        # Forward both ports so switching camera only requires restarting the daemon.
        # We only check the return code of the active port — the other is best-effort.
        results = {}
        for local, phone in [(_LOCAL_PORT_CAM1, PHONE_PORT_CAM1),
                              (_LOCAL_PORT_CAM2, PHONE_PORT_CAM2)]:
            r = subprocess.run(
                ["adb"] + ADB_DEVICE_ARGS + ["forward", f"tcp:{local}", f"tcp:{phone}"],
                capture_output=True, timeout=5,
            )
            results[phone] = r.returncode == 0
        return results[PHONE_PORT]
    except Exception:
        return False


# ── Wi-Fi discovery (mDNS) ────────────────────────────────────────────────────
# Uses macOS's built-in `dns-sd` (talks to mDNSResponder) — no extra dependency.
# The pure-Python zeroconf package did not see the phone on this setup.

_found: dict = {}   # service name -> (host, cam1_port, cam2_port)
_found_lock = threading.Lock()


def _resolve_instance(name: str) -> None:
    """Resolve one advertised instance with `dns-sd -L` and record its address and ports."""
    proc = subprocess.Popen(
        ["dns-sd", "-L", name, MDNS_TYPE, "local."],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1,
    )
    host = None
    port = 0
    txt: dict = {}
    deadline = time.monotonic() + 5
    try:
        for line in proc.stdout:
            m = re.search(r"can be reached at (\S+?):(\d+)", line)
            if m:
                host, port = m.group(1), int(m.group(2))
                continue
            if host and "=" in line:
                txt = dict(t.split("=", 1) for t in line.split() if "=" in t)
                break
            if time.monotonic() > deadline:
                break
    finally:
        proc.terminate()
    if not host:
        return
    try:
        addr = socket.getaddrinfo(host, None, socket.AF_INET)[0][4][0]
    except OSError:
        return
    with _found_lock:
        if name not in _found:
            log.info("Discovered phone %s at %s", name, addr)
        _found[name] = (addr, int(txt.get("cam1") or port), int(txt.get("cam2") or PHONE_PORT_CAM2))


def _discovery_loop() -> None:
    """Follow `dns-sd -B` (the system mDNSResponder) and keep _found up to date."""
    while _running.is_set():
        try:
            proc = subprocess.Popen(
                ["dns-sd", "-B", MDNS_TYPE, "local."],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1,
            )
        except FileNotFoundError:
            log.error("'dns-sd' not found — Wi-Fi discovery disabled (set PHONE_HOST instead)")
            return
        for line in proc.stdout:
            parts = line.split(None, 6)
            if len(parts) < 7 or parts[1] not in ("Add", "Rmv"):
                continue
            name = parts[6].strip()
            if parts[1] == "Add":
                threading.Thread(target=_resolve_instance, args=(name,), daemon=True).start()
            else:
                with _found_lock:
                    _found.pop(name, None)
        proc.terminate()
        time.sleep(2)


def start_discovery() -> None:
    threading.Thread(target=_discovery_loop, daemon=True, name="mdns").start()
    log.info("Wi-Fi discovery active (%s)", MDNS_TYPE)


def wifi_url() -> str | None:
    """URL of the phone on the LAN, from PHONE_HOST or mDNS; None if not found."""
    if PHONE_HOST:
        return f"http://{PHONE_HOST}:{PHONE_PORT}/"
    with _found_lock:
        if not _found:
            return None
        host, cam1, cam2 = next(iter(_found.values()))
    return f"http://{host}:{cam2 if PREFER_CAMERA2 else cam1}/"


def resolve_url() -> str | None:
    """
    Pick a transport and return the stream URL, or None if the phone is unreachable.
    USB goes through adb forward (and auto-starts the phone service); Wi-Fi needs
    the phone app already running.
    """
    if TRANSPORT in ("usb", "auto") and adb_forward():
        ensure_phone_app()
        return f"http://127.0.0.1:{LOCAL_PORT}/"
    if TRANSPORT in ("wifi", "auto"):
        return wifi_url()
    return None


# ── MJPEG parser ─────────────────────────────────────────────────────────────

def iter_mjpeg(url: str):
    """
    Yield JPEG frames from the phone's multipart stream.

    Each part carries a Content-Length, so a frame is emitted the moment its last
    byte arrives — we never wait for the *next* boundary (that would delay every
    frame by one full frame interval) and never block on a fixed-size read.
    """
    u = urlsplit(url)
    conn = http.client.HTTPConnection(u.hostname, u.port, timeout=5)
    try:
        conn.request("GET", u.path or "/")
        resp = conn.getresponse()
        if resp.status != 200:
            return
        while _running.is_set():
            line = resp.readline()
            if not line:
                return                      # connection closed
            if not line.startswith(b"--"):
                continue                    # blank line between parts
            length = None
            while True:
                h = resp.readline()
                if not h:
                    return
                if h in (b"\r\n", b"\n"):
                    break
                if h[:15].lower() == b"content-length:":
                    length = int(h[15:])
            if not length:
                continue
            jpeg = resp.read(length)
            if len(jpeg) < length:
                return
            yield jpeg
    except (OSError, http.client.HTTPException, ValueError) as e:
        log.debug("mjpeg: %s", e)
    finally:
        conn.close()


def _reader(url: str, fq: queue.Queue, done: threading.Event) -> None:
    for jpeg in iter_mjpeg(url):
        if done.is_set() or not _running.is_set():
            break
        try:
            fq.get_nowait()
        except queue.Empty:
            pass
        fq.put(jpeg)
    done.set()


# ── Camera-open monitor ───────────────────────────────────────────────────────

def camera_monitor() -> None:
    """
    Stream logs from the OBS camera extension and watch for the two events
    that CoreMediaIO emits when a consumer app starts or stops the camera:

      _clientQueue_addStreamingClient    — app opened the camera
      _clientQueue_removeStreamingClient — app closed the camera

    Everything else (the per-second _initWithLocalizedName heartbeat, property
    set messages, etc.) is filtered out so logs stay clean.
    """
    predicate = (
        'process CONTAINS[c] "obs-studio.mac-camera-extension"'
        ' OR process CONTAINS[c] "mac-camera-extension"'
    )
    try:
        proc = subprocess.Popen(
            ["log", "stream", "--predicate", predicate,
             "--style", "compact", "--info"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, bufsize=1,
        )
    except FileNotFoundError:
        log.error("'log' binary not found — camera monitor disabled")
        return

    log.info("Camera monitor active")

    # Monotonic timestamp after which we consider the camera truly closed.
    # Zero means "no pending close".
    close_at: float = 0.0

    def check_pending_close() -> None:
        nonlocal close_at
        if close_at and time.monotonic() >= close_at:
            close_at = 0.0
            if _camera_open.is_set():
                log.info("Camera closed")
                _camera_open.clear()

    for raw in proc.stdout:
        if not _running.is_set():
            break
        line = raw.strip()
        if not line or line.startswith("Filtering"):
            continue

        # The heartbeat fires every ~1 s — use it only to drive debounce timing.
        # Don't display it.
        if "_initWithLocalizedName" in line:
            check_pending_close()
            continue

        # ── Ground-truth open/close signals from CoreMediaIO ─────────────────
        if "_clientQueue_addStreamingClient" in line:
            close_at = 0.0               # cancel any pending close
            if not _camera_open.is_set():
                log.info("Camera opened")
                _camera_open.set()

        elif "_clientQueue_removeStreamingClient" in line:
            # Don't close immediately — the app may briefly reconnect
            # (e.g. Teams renegotiating the stream).
            close_at = time.monotonic() + CLOSE_DEBOUNCE

        else:
            # Show any other non-noise message at debug in case new patterns emerge
            log.debug("[cam-ext] %s", line[:160])
            check_pending_close()

    proc.terminate()


class _Timing:
    """Once-a-second summary of frame cadence and per-frame cost (DEBUG_TIMING=1)."""

    def __init__(self) -> None:
        self.last = None
        self.reset(time.perf_counter())

    def reset(self, now: float) -> None:
        self.start, self.n, self.gap, self.dec, self.snd = now, 0, 0.0, 0.0, 0.0

    def add(self, t0: float, dec: float, snd: float) -> None:
        if self.last is not None:
            self.gap = max(self.gap, t0 - self.last)
        self.last = t0
        self.n += 1
        self.dec += dec
        self.snd += snd
        if t0 - self.start >= 1.0:
            n = self.n
            log.info("timing: %.1f fps  max gap %.0f ms  decode %.1f ms  send %.1f ms",
                     n / (t0 - self.start), self.gap * 1000, self.dec / n * 1000, self.snd / n * 1000)
            self.reset(t0)


# ── Phone streaming ───────────────────────────────────────────────────────────

def phone_stream(cam: pyvirtualcam.Camera) -> None:
    """
    Keep the phone stream alive for as long as _camera_open is set.
    Handles USB disconnects and reconnects internally — the main loop
    only needs to call this once per camera-open event.
    """
    log.info("Camera opened — connecting to phone...")
    ever_streamed  = False
    adb_logged     = False  # avoid spamming "waiting for ADB" every frame

    next_adb_check = 0.0  # monotonic time of the next allowed adb probe

    while _camera_open.is_set() and _running.is_set():
        # ── Wait for phone / ADB ─────────────────────────────────────────
        # Keep feeding black frames to the consumer at full rate, but probe adb
        # (3 subprocesses) only every ADB_RETRY_INTERVAL seconds.
        url = None
        if time.monotonic() >= next_adb_check:
            url = resolve_url()
            if url is None:
                next_adb_check = time.monotonic() + ADB_RETRY_INTERVAL
                if not adb_logged:
                    log.info("Waiting for phone (%s)…", TRANSPORT)
                    adb_logged = True
        if url is None:
            cam.send(_BLACK)
            cam.sleep_until_next_frame()
            continue

        adb_logged = False  # reset so next disconnect logs again

        # ── Single connection attempt ─────────────────────────────────────
        fq: queue.Queue = queue.Queue(maxsize=1)
        done = threading.Event()
        t    = threading.Thread(target=_reader, args=(url, fq, done), daemon=True)
        t.start()

        stats = _Timing() if DEBUG_TIMING else None
        streamed_this_session = False
        session_start = time.monotonic()

        try:
            while _camera_open.is_set() and _running.is_set() and not done.is_set():
                try:
                    jpeg = fq.get(timeout=0.5)
                except queue.Empty:
                    cam.send(_BLACK)
                    cam.sleep_until_next_frame()
                    continue

                if not streamed_this_session:
                    log.info("Streaming from phone")
                    streamed_this_session = True
                    ever_streamed = True

                try:
                    t0 = time.perf_counter()
                    img = Image.open(io.BytesIO(jpeg))
                    if img.mode != "RGB":
                        img = img.convert("RGB")
                    if img.size != (WIDTH, HEIGHT):
                        img = img.resize((WIDTH, HEIGHT), Image.BILINEAR)
                    frame = np.asarray(img)
                    t1 = time.perf_counter()
                    # Send immediately: the phone's own clock paces the stream. Sleeping
                    # to a fixed grid here beats against that clock and adds judder.
                    cam.send(frame)
                    t2 = time.perf_counter()
                    if stats is not None:
                        stats.add(t0, t1 - t0, t2 - t1)
                except Exception as e:
                    log.debug("frame decode: %s", e)
        finally:
            done.set()
            t.join(timeout=5)
            try:
                while True:
                    fq.get_nowait()
            except queue.Empty:
                pass

        if not _camera_open.is_set() or not _running.is_set():
            break  # clean exit

        elapsed = time.monotonic() - session_start
        if streamed_this_session:
            log.info("Phone disconnected — reconnecting...")
        else:
            # Connection attempt failed before any frame arrived.
            # The phone may need a moment after USB reconnect — back off briefly.
            log.info("Phone not responding at %s (%.1f s) — retrying…", url, elapsed)
            retry_deadline = time.monotonic() + 1.0
            while time.monotonic() < retry_deadline:
                cam.send(_BLACK)
                cam.sleep_until_next_frame()

    if ever_streamed:
        log.info("Phone stream stopped")


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    def _stop(sig, _frame):
        log.info("Signal %s — shutting down", sig)
        _running.clear()
        _camera_open.set()  # unblock any blocked waiter

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    cam_label = "Camera2 (hw/sw JPEG toggled in-app)" if PREFER_CAMERA2 else "Camera1 (software JPEG)"
    if ADB_DEVICE_ARGS:
        device_label = " ".join(ADB_DEVICE_ARGS)
    else:
        device_label = "any single device (set DEVICE= or CONNECTION= in mac/.env for multi-device setups)"
    log.info("pwebcam receiver starting — %s  (local %d → phone %d)",
             cam_label, LOCAL_PORT, PHONE_PORT)
    log.info("Transport: %s · ADB target: %s", TRANSPORT, device_label)
    if TRANSPORT in ("wifi", "auto") and not PHONE_HOST:
        start_discovery()

    threading.Thread(target=camera_monitor, daemon=True, name="cam-monitor").start()

    try:
        with pyvirtualcam.Camera(
            width=WIDTH, height=HEIGHT, fps=FPS,
            fmt=pyvirtualcam.PixelFormat.RGB, print_fps=False,
        ) as cam:
            log.info("Virtual camera ready: %s — idle until camera is opened", cam.device)

            while _running.is_set():
                if _camera_open.is_set():
                    phone_stream(cam)   # blocks until camera closes or shutdown
                else:
                    # Idle: no consumer, so don't burn CPU pushing 30 fps of black.
                    # Block until the camera opens (wakes instantly), with a slow
                    # keep-alive frame so the virtual camera stays alive.
                    cam.send(_BLACK)
                    _camera_open.wait(IDLE_KEEPALIVE)

    except Exception as e:
        log.error("Virtual camera error: %s", e)
        log.error("Make sure OBS 26+ is installed (provides the virtual camera driver)")

    log.info("pwebcam receiver stopped")


if __name__ == "__main__":
    main()
