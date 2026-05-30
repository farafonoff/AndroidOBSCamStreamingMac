#!/usr/bin/env bash
# Installs pwebcam receiver as a macOS login-item daemon.
# After installation it starts automatically at login and restarts on crash.

set -euo pipefail

# LaunchAgents are user-level — sudo is not needed and breaks UID detection.
if [[ $EUID -eq 0 ]]; then
  echo "ERROR: Do not run with sudo." >&2
  echo "Run:   bash ./install.sh" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RECEIVER="$SCRIPT_DIR/receiver.py"
PYTHON="$(command -v python3)"
LABEL="com.pwebcam.receiver"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/pwebcam.log"

if [[ ! -f "$RECEIVER" ]]; then
  echo "ERROR: receiver.py not found at $RECEIVER" >&2
  exit 1
fi

if [[ -z "$PYTHON" ]]; then
  echo "ERROR: python3 not found in PATH" >&2
  exit 1
fi

# Verify dependencies
"$PYTHON" -c "import pyvirtualcam, PIL, numpy, requests" 2>/dev/null || {
  echo "Installing Python dependencies…"
  "$PYTHON" -m pip install pyvirtualcam pillow numpy requests --quiet
}

mkdir -p "$(dirname "$LOG")"

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>$PYTHON</string>
        <string>$RECEIVER</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>$LOG</string>
    <key>StandardErrorPath</key>
    <string>$LOG</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>PATH</key>
        <string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
    </dict>
</dict>
</plist>
EOF

# Unload previous version if running
launchctl bootout "gui/$UID/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$UID" "$PLIST"

echo ""
echo "pwebcam receiver installed."
echo ""
echo "  Starts automatically at login."
echo "  Logs → $LOG"
echo ""
echo "  Stop:      launchctl stop $LABEL"
echo "  Start:     launchctl start $LABEL"
echo "  Uninstall: bash $SCRIPT_DIR/uninstall.sh"
