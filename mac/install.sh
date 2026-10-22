#!/bin/bash
# 构建并安装 FileWeave Mac 到 /Applications，然后启动。
# 这才是「正式安装」入口 —— 本机日常运行的就是这个路径下的 app。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="FileWeave Mac"
DEST="/Applications/$APP_NAME.app"

echo "==> 构建"
"$ROOT/build.sh"

echo "==> 安装到 $DEST"
# 先停掉正在跑的旧实例。必须用 TERM 让它走正常退出路径收掉 bridge.py 子进程；
# 直接 KILL 会把 bridge.py 孤儿化，占着 8787 让新实例永远起不来。
pkill -TERM -f "FileWeave Mac.app" 2>/dev/null || true
sleep 1
# 兜底：万一有上次异常退出的孤儿 bridge.py，一并收掉（只杀本仓库状态目录的实例）。
pkill -TERM -f "bridge\.py --state" 2>/dev/null || true
sleep 1
if [ -d "$DEST" ]; then
  rm -rf "$DEST"
fi
cp -R "$ROOT/build/$APP_NAME.app" "$DEST"
# build.sh 已签好。**这里不能再用 ad-hoc 重签**：
# 签名与路径无关，拷贝不破坏它；重签一次身份就变了。
codesign --verify "$DEST" 2>&1 | tail -2 || true

echo "==> 启动"
open -a "$DEST"

echo
echo "完成。菜单栏图标应该已经出现（没看到的话点一下屏幕右上角的状态栏区域）。"
echo "注意：手机侧需要与这台 Mac 完成配对，录音功能需要手机打开共享文件夹。"
