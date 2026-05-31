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

import io
import logging
import queue
import signal
import socket
import subprocess
import sys
import threading
import time

import numpy as np
import pyvirtualcam
import requests
from PIL import Image

# ── Camera selection ─────────────────────────────────────────────────────────
# Set PREFER_CAMERA2 = True  → connect to Camera2 port (hardware JPEG, less heat)
#     PREFER_CAMERA2 = False → connect to Camera1 port (software JPEG, wider compat)
# Switching takes effect after daemon restart (bash mac/restart.sh).
PREFER_CAMERA2  = False

# Camera settings (EV, focus) are stored on the phone and controlled via the
# app UI. The receiver connects with a plain URL — no params needed.  # Camera2 JPEG continuous streaming is throttled on LIMITED HALs (e.g. Kirin 659)

PHONE_PORT_CAM1 = 8080
PHONE_PORT_CAM2 = 8081
PHONE_PACKAGE   = "com.pwebcam"
PHONE_SERVICE   = "com.pwebcam/.CameraService"
WIDTH, HEIGHT = 1280, 720
FPS           = 30
# Seconds to wait after the last "remove client" before declaring camera closed.
# Handles apps that briefly disconnect/reconnect (e.g. switching camera sources).
CLOSE_DEBOUNCE = 2.0


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
            ["adb", "shell", "dumpsys", "activity", "services", PHONE_PACKAGE],
            capture_output=True, text=True, timeout=5,
        )
        if "(nothing)" not in r.stdout and PHONE_PACKAGE in r.stdout:
            return  # already running
    except Exception:
        return

    log.info("Starting pwebcam app on phone…")
    try:
        r = subprocess.run(
            ["adb", "shell", "am", "start-foreground-service", "-n", PHONE_SERVICE],
            capture_output=True, text=True, timeout=5,
        )
        if r.returncode != 0:
            # Fallback for older Android: launch the activity instead
            subprocess.run(
                ["adb", "shell", "am", "start", "-n", f"{PHONE_PACKAGE}/.MainActivity"],
                capture_output=True, timeout=5,
            )
    except Exception as e:
        log.debug("ensure_phone_app: %s", e)


def adb_forward() -> bool:
    """Return True when a device is present and the active port forward is set up."""
    try:
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
                ["adb", "forward", f"tcp:{local}", f"tcp:{phone}"],
                capture_output=True, timeout=5,
            )
            results[phone] = r.returncode == 0
        return results[PHONE_PORT]
    except Exception:
        return False


# ── MJPEG parser ─────────────────────────────────────────────────────────────

def iter_mjpeg(url: str):
    try:
        with requests.get(url, stream=True, timeout=5) as r:
            r.raise_for_status()
            ct = r.headers.get("Content-Type", "")
            boundary = None
            for part in ct.split(";"):
                p = part.strip()
                if p.startswith("boundary="):
                    boundary = ("--" + p[len("boundary="):]).encode()
                    break
            if not boundary:
                return
            buf = b""
            for chunk in r.iter_content(chunk_size=8192):
                if not _running.is_set():
                    return
                buf += chunk
                while True:
                    s = buf.find(boundary)
                    if s == -1:
                        buf = buf[-(len(boundary) - 1):]
                        break
                    e = buf.find(boundary, s + len(boundary))
                    if e == -1:
                        buf = buf[s:]
                        break
                    section = buf[s + len(boundary): e]
                    he = section.find(b"\r\n\r\n")
                    if he != -1:
                        jpeg = section[he + 4:]
                        if jpeg.endswith(b"\r\n"):
                            jpeg = jpeg[:-2]
                        if jpeg:
                            yield jpeg
                    buf = buf[e:]
    except requests.exceptions.ConnectionError:
        pass
    except Exception as e:
        log.debug("mjpeg: %s", e)


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

    while _camera_open.is_set() and _running.is_set():
        # ── Wait for phone / ADB ─────────────────────────────────────────
        if not adb_forward():
            if not adb_logged:
                log.info("Waiting for phone via USB (adb forward %d→%d)…",
                         LOCAL_PORT, PHONE_PORT)
                adb_logged = True
            cam.send(_BLACK)
            cam.sleep_until_next_frame()
            continue  # try again next frame (~33 ms)

        adb_logged = False  # reset so next disconnect logs again
        ensure_phone_app()

        # ── Single connection attempt ─────────────────────────────────────
        url  = f"http://127.0.0.1:{LOCAL_PORT}/"
        fq: queue.Queue = queue.Queue(maxsize=1)
        done = threading.Event()
        t    = threading.Thread(target=_reader, args=(url, fq, done), daemon=True)
        t.start()

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
                    frame = Image.open(io.BytesIO(jpeg)).convert("RGB")
                    if frame.size != (WIDTH, HEIGHT):
                        frame = frame.resize((WIDTH, HEIGHT), Image.BILINEAR)
                    cam.send(np.asarray(frame))
                    cam.sleep_until_next_frame()
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
            log.info("Phone not responding on port %d (%.1f s) — retrying…",
                     PHONE_PORT, elapsed)
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

    cam_label = "Camera2 (hardware JPEG)" if PREFER_CAMERA2 else "Camera1 (software JPEG)"
    log.info("pwebcam receiver starting — %s  (local %d → phone %d)",
             cam_label, LOCAL_PORT, PHONE_PORT)

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
                    cam.send(_BLACK)
                    cam.sleep_until_next_frame()
                    # Yield to camera monitor thread so it can set _camera_open

    except Exception as e:
        log.error("Virtual camera error: %s", e)
        log.error("Make sure OBS 26+ is installed (provides the virtual camera driver)")

    log.info("pwebcam receiver stopped")


if __name__ == "__main__":
    main()
