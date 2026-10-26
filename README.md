# pwebcam

Android phone as a low-latency (~55 ms over USB) webcam on macOS, with an optional Wi-Fi mode. Works with Teams, Zoom, OBS, Google Meet.

## Requirements

- Android 5.0+ phone with USB Debugging enabled (USB mode; not needed for Wi-Fi-only use)
- macOS 13+
- [OBS Studio 26+](https://obsproject.com/) — provides the virtual camera driver
- [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) (`brew install android-platform-tools`)
- Python 3 with dependencies: `pip install -r mac/requirements.txt`

## Setup

**1. Android app** — open `android/` in Android Studio, build and install the APK.

**2. Mac receiver** — install as a login daemon (auto-starts at login):
```bash
bash mac/install.sh
```

Or run manually:
```bash
python3 mac/receiver.py
```

**3. Use** — connect the phone via USB (or see [Wi-Fi mode](#wi-fi-mode)), select **OBS Virtual Camera** in Teams / Zoom / OBS.

The phone camera turns on automatically when a call starts and off when it ends.

## Configuration

Receiver options live in `mac/.env` (copy `mac/.env.example`, which documents every key with examples). Changes take effect after `bash mac/restart.sh`.

| Key | Purpose |
|---|---|
| `TRANSPORT` | `usb` (default), `wifi`, or `auto` (USB if an ADB device is present, otherwise Wi-Fi) |
| `PHONE_HOST` | Fixed phone IP for Wi-Fi mode; skips mDNS discovery |
| `DEVICE` / `CONNECTION` | Pin to one ADB device when several are connected |
| `PREFER_CAMERA2` | `false` = Camera1, port 8080 (most compatible); `true` = Camera2, port 8081 |

## Wi-Fi mode

Set `TRANSPORT=wifi` (or `auto`) in `mac/.env`. The phone advertises `_pwebcam._tcp` over mDNS and the receiver finds it with macOS's built-in `dns-sd`, so there is nothing extra to install and no ADB is needed.

- The phone app must already be running; ADB can't start it over Wi-Fi. It starts on boot by default (see below), or open it by hand.
- Phone and Mac must be on the same subnet and the network must allow mDNS. If it doesn't (guest or corporate Wi-Fi), set `PHONE_HOST=<ip>`.
- The stream is plain MJPEG, **unencrypted and unauthenticated**. Anyone on the network can open it. Use trusted networks only.
- Expect higher and less steady latency than USB. Many older phones are 2.4 GHz-only, which is the usual bottleneck. Lower the quality or FPS if the stream stutters (below).

## Web UI

Open `http://<phone-ip>:8080/ui` in a browser (the phone also advertises itself as an `_http._tcp` service). It shows status, live fps and Mbit/s, an optional live preview, and the camera controls (EV, focus, flash, JPEG quality, max FPS, Camera2 hardware JPEG). Port 8081 serves the same page for the Camera2 side.

Opening the preview turns the phone camera on. If the Mac is streaming from the other camera API, the preview will interrupt it, because both servers share one camera.

Over USB, forward a port first: `adb forward tcp:8080 tcp:8080`, then open `http://localhost:8080/ui`.

## Camera settings and bandwidth

Adjust **EV**, **focus mode**, **flash**, **JPEG quality** (30–95, default 80) and **max FPS** (5–30, default 30) from the web UI. EV, focus and flash are also in the app. All settings are stored on the phone and persist across reboots. The JSON API behind the page is `GET /api/state` and `GET /api/set?quality=65&maxFps=24` (also `evDelta`, `focus`, `flash`, `flashBrightness`, `hwJpeg`).

Bitrate scales mostly with quality and FPS. Watch the Mbit/s readout in the web UI and lower quality if Wi-Fi stutters.

## Start on boot

The phone app starts its service after boot (switch in the app, on by default), so it is discoverable without opening it. Android 11+ generally blocks camera-type foreground services from starting in the background, so on newer phones the boot start may be refused and you open the app once manually.

## Troubleshooting

```bash
tail -f ~/Library/Logs/pwebcam.log   # receiver logs
adb devices                           # phone must show as "device"
bash mac/restart.sh                   # restart the daemon after config changes
bash mac/uninstall.sh                 # remove the daemon
```

See [DECISIONS.md](DECISIONS.md) for architecture rationale.
