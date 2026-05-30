# pwebcam — Android phone as a low-latency USB webcam on macOS

Use an old Android phone as a webcam with **~50–70 ms** glass-to-glass latency.
Works with Teams, Zoom, OBS, Google Meet, and anything else that accepts a camera.

## Why not Wi-Fi streaming apps?

Wi-Fi webcam apps (DroidCam, EpocCam, etc.) buffer H.264 video for reliability,
adding 500–1500 ms of delay. This project eliminates that by using:

| Choice | Reason |
|---|---|
| **USB / ADB** instead of Wi-Fi | No jitter buffer needed — USB latency is ~5 ms |
| **MJPEG** instead of H.264 | No inter-frame dependencies, no encoder buffer |
| **Per-client writer threads** | Camera callback never blocks on network I/O |

Latency budget at 30 fps:

```
Camera sensor → YUV frame      ~33 ms  (1 frame)
YUV → JPEG (software)          ~10 ms
ADB USB transfer                ~5 ms
Virtual camera injection        ~5 ms
─────────────────────────────────────
Total                          ~53 ms  ✓
```

---

## Architecture

```
Android phone
  └─ Camera1 API → YUV → JPEG → TCP server on :8080
          │
          │  USB cable
          │  adb forward tcp:8080 tcp:8080
          │
macOS receiver (receiver.py)
  ├─ Watches OBS camera extension log for CoreMediaIO events
  │    _clientQueue_addStreamingClient    → any app opened the camera
  │    _clientQueue_removeStreamingClient → app closed the camera
  ├─ On open: ADB forward + connects to phone MJPEG stream
  └─ Writes frames into OBS Virtual Camera (pyvirtualcam)
          │
          ├─ OBS          (Video Capture Device → OBS Virtual Camera)
          └─ Teams / Zoom / Meet  (Settings → Camera → OBS Virtual Camera)
```

**Power saving:** the phone camera only turns on when the Mac receiver is
connected. When a Teams call ends and the camera closes, the phone camera
turns off automatically.

---

## Requirements

**Android phone**
- Android 5.0+ (API 21)
- USB Debugging enabled (Settings → Developer Options)

**Mac**
- macOS 13 Ventura or later
- [OBS Studio 26+](https://obsproject.com/) — provides the virtual camera driver
- [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) (`adb`)
  ```bash
  brew install android-platform-tools
  ```
- Python 3 with dependencies:
  ```bash
  pip install pyvirtualcam pillow numpy requests
  ```

---

## Setup

### 1 — Android app

Open the `android/` folder in Android Studio, build, and install the APK on your phone.

The app starts streaming automatically when opened. A foreground notification
shows the current state ("Waiting for connection…" / "Streaming on :8080").

### 2 — Mac receiver (manual)

```bash
python3 mac/receiver.py
```

### 2 — Mac receiver (auto-start at login) — recommended

```bash
bash mac/install.sh
```

Installs the receiver as a LaunchAgent. It starts at login, restarts on crash,
and logs to `~/Library/Logs/pwebcam.log`.

```bash
bash mac/uninstall.sh   # remove
launchctl stop  com.pwebcam.receiver   # pause without uninstalling
launchctl start com.pwebcam.receiver   # resume
```

### 3 — Use the camera

1. Connect the phone via USB — the receiver detects it automatically.
2. Open Teams / Zoom / OBS and select **OBS Virtual Camera**.
3. When a call starts the phone camera turns on; when it ends the camera turns off.

---

## How the camera open/close detection works

The receiver tails the macOS unified log for the OBS camera extension process
and watches for two CoreMediaIO method names:

| Log event | Meaning |
|---|---|
| `_clientQueue_addStreamingClient` | An app opened the camera |
| `_clientQueue_removeStreamingClient` | An app closed the camera |

A 2-second debounce on close handles apps that briefly renegotiate the stream
(e.g. Teams toggling the camera mid-call). All other extension log noise
(`_initWithLocalizedName` heartbeat, property messages, etc.) is discarded.

---

## Troubleshooting

**Phone not detected**
```bash
adb devices          # should show a device with status "device"
                     # if "unauthorized" — accept the prompt on the phone
```

**Virtual camera not visible in Teams/Zoom**
- Make sure OBS is installed (not just downloaded — run it once to register the extension).
- Restart the app after installing OBS.

**High latency**
- Use a USB cable directly to the Mac, not a hub.
- Lower JPEG quality in `CameraService.kt` (`JPEG_QUALITY = 70`) to reduce encode time.
- On capable phones, switch to 60 fps by adjusting `setPreviewFpsRange`.

**Stream unstable / drops**
- The MJPEG server uses per-client drop-oldest queues, so the camera thread
  never blocks. If drops persist, check `adb logcat` for camera errors on the phone.

**Check receiver logs**
```bash
tail -f ~/Library/Logs/pwebcam.log
```
