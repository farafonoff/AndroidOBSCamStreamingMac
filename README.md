# pwebcam

Android phone as a low-latency (~55 ms) USB webcam on macOS. Works with Teams, Zoom, OBS, Google Meet.

## Requirements

- Android 5.0+ phone with USB Debugging enabled
- macOS 13+
- [OBS Studio 26+](https://obsproject.com/) — provides the virtual camera driver
- [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) (`brew install android-platform-tools`)
- Python 3 with dependencies: `pip install pyvirtualcam pillow numpy requests`

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

**3. Use** — connect the phone via USB, select **OBS Virtual Camera** in Teams / Zoom / OBS.

The phone camera turns on automatically when a call starts and off when it ends.

## Camera settings

Open the pwebcam app on the phone to adjust **EV** (brightness) and **focus mode**. Settings persist across reboots.

## Troubleshooting

```bash
tail -f ~/Library/Logs/pwebcam.log   # receiver logs
adb devices                           # phone must show as "device"
bash mac/restart.sh                   # restart the daemon after config changes
bash mac/uninstall.sh                 # remove the daemon
```

See [DECISIONS.md](DECISIONS.md) for architecture rationale.
