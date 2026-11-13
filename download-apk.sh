#!/bin/sh
# Helper script to download compiled APK directly to /sdcard/Download/LiveAiDostAutoReply.apk
set -e

DEST="/sdcard/Download/LiveAiDostAutoReply.apk"

if [ -z "$1" ]; then
    echo "Usage: $0 <github-username/repo-name> [tag]"
    echo "Example: $0 yourname/live-ai-dost-autoreply"
    exit 1
fi

REPO="$1"
TAG="${2:-v1.0.0}"
URL="https://github.com/$REPO/releases/download/$TAG/app-debug.apk"

echo "Downloading APK from $URL..."
curl -L -f -o "$DEST" "$URL"

if [ -f "$DEST" ]; then
    echo "Successfully downloaded APK to: $DEST"
    echo "You can now install it from your device's Downloads folder!"
else
    echo "Download failed. Please check if the GitHub Action release has completed."
fi
