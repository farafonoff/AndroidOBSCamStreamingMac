#!/usr/bin/env bash
LABEL="com.pwebcam.receiver"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

launchctl bootout "gui/$UID/$LABEL" 2>/dev/null || true
rm -f "$PLIST"
echo "pwebcam receiver uninstalled."
