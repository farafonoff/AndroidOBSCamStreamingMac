# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**pwebcam** turns an Android phone into a low-latency (~55ms) USB webcam for macOS via ADB-forwarded MJPEG over TCP. The virtual camera is injected via OBS's CMIOExtension driver (pyvirtualcam). Works with Teams, Zoom, OBS, Google Meet.

## Commands

### Android app (in `android/`)
```bash
./gradlew assembleDebug                             # Build debug APK
adb install app/build/outputs/apk/debug/app-debug.apk  # Deploy to phone
```

### macOS receiver
```bash
# Manual run (dev)
python3 mac/receiver.py

# Install/manage as login launchd agent
bash mac/install.sh
bash mac/restart.sh
bash mac/uninstall.sh

# Logs
tail -f ~/Library/Logs/pwebcam.log

# Python deps
pip install -r mac/requirements.txt
```

There are no automated tests.

## Architecture

### Data flow
```
Android Camera → MjpegServer (TCP) → ADB forward (USB) → receiver.py → pyvirtualcam → OBS virtual cam
```
- Phone listens on **port 8080** (Camera1) and **8081** (Camera2) simultaneously
- Receiver forwards a local port ≥32000 to one of those via `adb forward`
- Receiver streams `GET /` and parses MJPEG boundary frames

### Android components (`android/app/src/main/java/com/pwebcam/`)

| File | Role |
|------|------|
| `CameraService.kt` | Foreground service; starts both MJPEG servers; persists settings via SharedPreferences |
| `MjpegServer.kt` | Raw `ServerSocket`; per-client `ArrayBlockingQueue(2)` with drop-oldest; never blocks camera callback |
| `CameraSource.kt` | Interface: `start`, `stop`, `configure(ev, focusAlias)`, `setFlash(enabled, brightness)` |
| `Camera1Source.kt` | Camera1 API (default); NV21→JPEG in software; targets devices where Camera2 throttles JPEG to <30fps |
| `Camera2Source.kt` | Camera2 API; hardware JPEG (toggleable), per-frame `CaptureRequest` for EV/focus |
| `MainActivity.kt` | UI: start/stop, EV ±3, focus mode, flash, Camera2 HW JPEG toggle |

Camera API is chosen by **which port** the receiver connects to. Both servers run concurrently.

### macOS receiver (`mac/receiver.py`)

- Opens pyvirtualcam **once** at startup; sends black frames when idle
- **Camera open/close detection**: streams macOS unified logs from the OBS CMIOExtension process watching for `_clientQueue_addStreamingClient` / `_clientQueue_removeStreamingClient` (2s close debounce). `AVCaptureDevice.isInUseByAnotherApplication` does not work for CMIOExtension.
- Connects to the phone only while the virtual camera is actively used; disconnects on idle to power down the phone camera
- Auto-starts the phone foreground service via `adb shell am start-foreground-service` on reconnect
- `PREFER_CAMERA2 = True` at top of file controls which port (8080 vs 8081) the receiver targets

## Key design constraints

- **MJPEG, not H.264**: intra-frame only → exact 1-frame latency; no decoder jitter buffers
- **USB/ADB, not Wi-Fi**: deterministic link, ~5ms transport vs. ~1s for Wi-Fi H.264
- **Camera1 is the default**: LIMITED Camera2 HALs (e.g. Kirin 659) throttle JPEG output below 30fps
- **Raw ServerSocket over NanoHTTPD**: drop-oldest queue semantics; camera callback must never block on slow clients
- **Dynamic local port ≥32000**: avoids collisions with common dev servers; phone-side ports are fixed

See `DECISIONS.md` for full rationale on all 15 architectural decisions.
