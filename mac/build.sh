#!/bin/bash
# 构建 "FileWeave Mac.app"（SwiftPM 三目标 + 组装 bundle + 签名）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="FileWeave Mac"
BUNDLE_ID="org.fileweave.mac.dev"
OUT="$ROOT/build"
APP="$OUT/$APP_NAME.app"

echo "==> swift build -c release"
cd "$ROOT"
swift build -c release 2>&1 | tail -30

BIN="$(swift build -c release --show-bin-path)/FileWeaveMenuBar"
[ -x "$BIN" ] || { echo "找不到可执行文件: $BIN"; exit 1; }

echo "==> 组装 .app bundle"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/FileWeaveMac"

# 转录脚本等资源
cp "$ROOT/Resources/transcribe.py" "$APP/Contents/Resources/transcribe.py"
cp "$ROOT/Resources/setup-asr.sh" "$APP/Contents/Resources/setup-asr.sh" 2>/dev/null || true
chmod +x "$APP/Contents/Resources/setup-asr.sh" 2>/dev/null || true

# 注意：bridge.py / pairing.py / .state 不打进 bundle —— BridgeProcess 按
# 源码树定位它们（见 MacPaths），数据和脚本始终留在仓库 mac/ 目录里。
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>$APP_NAME</string>
    <key>CFBundleDisplayName</key><string>$APP_NAME</string>
    <key>CFBundleIdentifier</key><string>$BUNDLE_ID</string>
    <key>CFBundleExecutable</key><string>FileWeaveMac</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSMinimumSystemVersion</key><string>14.0</string>
    <key>LSUIElement</key><true/>
    <key>NSHighResolutionCapable</key><true/>
    <key>NSHumanReadableCopyright</key><string>Eric Yim</string>
    <!-- 两类 Bonjour：手机配对发现(_fileweave._tcp.)与共享文件夹发现(_fileweave-folder._tcp.)。
         macOS 15+ 没有这把钥匙就静默拒绝本地网络访问：不弹窗、不报错，
         连接直接以 EHOSTUNREACH / Network is down 失败。 -->
    <key>NSBonjourServices</key><array>
        <string>_fileweave._tcp.</string>
        <string>_fileweave-folder._tcp.</string>
    </array>
    <!-- 必须有，理由同上：本地网络访问的用途说明 -->
    <key>NSLocalNetworkUsageDescription</key><string>FileWeave connects your Android device for files and clipboard text.</string>
</dict>
</plist>
PLIST

printf 'APPL????' > "$APP/Contents/PkgInfo"

echo "==> 签名"
# ad-hoc 签名。注意权衡：TCC 隐私授权（本地网络这类弹窗）按签名链匹配记录，
# ad-hoc（--sign -）每次构建指纹都变，用户点过的「允许」到下次构建就作废，
# 权限弹窗会重新弹。要稳定身份时再引入固定的自签证书，这里先保持简单。
codesign --force --sign - --timestamp=none "$APP" 2>&1 | tail -3

echo "==> 完成: $APP"
du -sh "$APP"
