#!/bin/bash
set -eu
cd "$(dirname "$0")"
APP="$PWD/build/FileWeave Mac.app"
mkdir -p "$APP/Contents/MacOS"
if [ ! -f "$APP/Contents/MacOS/FileWeaveMac" ] || [ MenuBar.swift -nt "$APP/Contents/MacOS/FileWeaveMac" ]; then
    swiftc -O "$PWD/MenuBar.swift" -o "$APP/Contents/MacOS/FileWeaveMac" -framework AppKit -framework CoreImage
fi
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>FileWeaveMac</string>
<key>CFBundleIdentifier</key><string>org.fileweave.mac.dev</string>
<key>CFBundleName</key><string>FileWeave Mac</string>
<key>CFBundleVersion</key><string>1</string>
<key>LSUIElement</key><true/>
<key>NSBonjourServices</key><array><string>_fileweave._tcp</string></array>
<key>NSLocalNetworkUsageDescription</key><string>FileWeave connects your Android device for files and clipboard text.</string>
</dict></plist>
PLIST
open "$APP"
