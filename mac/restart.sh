#!/usr/bin/env bash
LABEL="com.pwebcam.receiver"
launchctl stop  "$LABEL"
launchctl start "$LABEL"
echo "Restarted $LABEL"
