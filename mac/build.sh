#!/bin/bash
# 构建 "FileWeave Mac.app"（SwiftPM 三目标 + 组装 bundle + 签名）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="FileWeave Mac"
BUNDLE_ID="org.fileweave.mac.dev"
OUT="$ROOT/build"
APP="$OUT/$APP_NAME.app"

# Optional SDK override for machines whose newest SDK needs unavailable Xcode plugins.
SWIFT_ARGS=()
if [ -n "${FILEWEAVE_MACOS_SDK:-}" ]; then
  SWIFT_ARGS+=(--sdk "$FILEWEAVE_MACOS_SDK" --build-system native)
fi

echo "==> swift build -c release"
cd "$ROOT"
# bash 3.2（macOS 自带）在 set -u 下展开空数组会报 unbound variable，
# 所以用 ${ARR[@]+...} 写法兼容空数组。
swift build -c release ${SWIFT_ARGS[@]+"${SWIFT_ARGS[@]}"} 2>&1 | tail -30

BIN="$(swift build -c release ${SWIFT_ARGS[@]+"${SWIFT_ARGS[@]}"} --show-bin-path)/FileWeaveMenuBar"
[ -x "$BIN" ] || { echo "找不到可执行文件: $BIN"; exit 1; }

echo "==> 组装 .app bundle"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/FileWeaveMac"
CLI_BIN="$(dirname "$BIN")/FileWeaveCLI"
[ -x "$CLI_BIN" ] || { echo "FileWeave CLI executable missing / 找不到 CLI: $CLI_BIN"; exit 1; }
cp "$CLI_BIN" "$APP/Contents/MacOS/fileweave"
cp "$ROOT/../docs/CLI.md" "$APP/Contents/Resources/CLI.md"

# Reuse the Android launcher artwork for the menu bar and app bundle.
cp "$ROOT/../art/launcher_icon-web.png" "$APP/Contents/Resources/FileWeaveIcon.png"
ICONSET="$OUT/FileWeave.iconset"
mkdir -p "$ICONSET"
for size in 16 32 128 256 512; do
    sips -z "$size" "$size" "$ROOT/../art/launcher_icon-web.png" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
    double=$((size * 2))
    sips -z "$double" "$double" "$ROOT/../art/launcher_icon-web.png" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/FileWeave.icns"

# 转录脚本等资源
cp -R "$ROOT/Resources/en.lproj" "$ROOT/Resources/zh-Hans.lproj" "$APP/Contents/Resources/"
cp "$ROOT/Resources/transcribe.py" "$APP/Contents/Resources/transcribe.py"
cp "$ROOT/Resources/setup-asr.sh" "$APP/Contents/Resources/setup-asr.sh"
chmod +x "$APP/Contents/Resources/setup-asr.sh"

# 注意：bridge.py / pairing.py / .state 不打进 bundle —— BridgeProcess 按
# 源码树定位它们（见 MacPaths），数据和脚本始终留在仓库 mac/ 目录里。
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>$APP_NAME</string>
    <key>CFBundleDisplayName</key><string>$APP_NAME</string>
    <key>CFBundleDevelopmentRegion</key><string>en</string>
    <key>CFBundleLocalizations</key><array><string>en</string><string>zh-Hans</string></array>
    <key>CFBundleIdentifier</key><string>$BUNDLE_ID</string>
    <key>CFBundleIconFile</key><string>FileWeave.icns</string>
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
codesign --force --sign - --timestamp=none "$APP/Contents/MacOS/fileweave"
codesign --force --sign - --timestamp=none "$APP" 2>&1 | tail -3

echo "==> 完成: $APP"
du -sh "$APP"
