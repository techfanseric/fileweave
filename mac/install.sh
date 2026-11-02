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
# Only stop the installed app and child bridge processes confirmed before shutdown.
# Do not match every bridge.py or development/QA copy on this Mac.
/usr/bin/python3 - "$DEST/Contents/MacOS/FileWeaveMac" "$ROOT/bridge.py" "$ROOT/.state" <<'PYCODE'
import os, re, signal, subprocess, sys, time
exe, script, state = sys.argv[1:]
def processes():
    result = []
    for line in subprocess.check_output(["ps", "-axo", "pid=,ppid=,command="], text=True).splitlines():
        match = re.match(r"\s*(\d+)\s+(\d+)\s+(.*)", line)
        if match: result.append((int(match[1]), int(match[2]), match[3]))
    return result
rows = processes()
apps = {pid for pid, _, command in rows if command == exe or command.startswith(exe + " ")}
children = {pid for pid, parent, command in rows if parent in apps and command.endswith(script + " --state " + state)}
for pid in apps: os.kill(pid, signal.SIGTERM)
for ids in [apps, children]:
    if ids is children:
        for pid, _, command in processes():
            if pid in children and command.endswith(script + " --state " + state): os.kill(pid, signal.SIGTERM)
    deadline = time.time() + 5
    while time.time() < deadline and any(pid in ids for pid, _, _ in processes()): time.sleep(0.1)
    if any(pid in ids for pid, _, _ in processes()):
        raise SystemExit("The previous app or its server has not exited; installation stopped without force-killing it.")
PYCODE
# Retain the old executable bundle for rollback. User data is stored outside it.
BACKUP="$(mktemp -d "${TMPDIR:-/tmp}/fileweave-app-backup.XXXXXX")"
if [ -d "$DEST" ]; then cp -R "$DEST" "$BACKUP/$APP_NAME.app"; fi
STAGING="$(mktemp -d "/Applications/.fileweave-install.XXXXXX")"
cp -R "$ROOT/build/$APP_NAME.app" "$STAGING/$APP_NAME.app"
codesign --verify --deep --strict "$STAGING/$APP_NAME.app"
if [ -d "$DEST" ]; then
  rm -rf "$DEST"
fi
if ! mv "$STAGING/$APP_NAME.app" "$DEST"; then
  if [ -d "$BACKUP/$APP_NAME.app" ]; then cp -R "$BACKUP/$APP_NAME.app" "$DEST"; fi
  exit 1
fi
rmdir "$STAGING"
echo "Previous app backup: $BACKUP"
# build.sh 已签好。**这里不能再用 ad-hoc 重签**：
# 签名与路径无关，拷贝不破坏它；重签一次身份就变了。
codesign --verify --deep --strict "$DEST" 2>&1 | tail -2

echo "==> Install CLI / 安装 CLI"
"$ROOT/install-cli.sh" "$DEST"

echo "==> 启动"
open -a "$DEST"

echo
echo "完成。菜单栏图标应该已经出现（没看到的话点一下屏幕右上角的状态栏区域）。"
echo "注意：手机侧需要与这台 Mac 完成配对，录音功能需要手机打开共享文件夹。"
