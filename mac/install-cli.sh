#!/bin/bash
# Install a stable user command pointing into the already installed signed app.
set -euo pipefail
APP="${1:-/Applications/FileWeave Mac.app}"
BIN_DIR="${FILEWEAVE_CLI_BIN_DIR:-$HOME/.local/bin}"
TARGET="$APP/Contents/MacOS/fileweave"
[ -x "$TARGET" ] || { echo "CLI executable missing / 找不到 CLI: $TARGET" >&2; exit 1; }
codesign --verify --strict "$TARGET"
/usr/bin/python3 - "$TARGET" "$BIN_DIR" <<'PY'
import os, pathlib, sys
target = pathlib.Path(sys.argv[1]).absolute()
folder = pathlib.Path(sys.argv[2]).expanduser().absolute()
folder.mkdir(parents=True, exist_ok=True)
link = folder / 'fileweave'
try:
    link.symlink_to(target)
except FileExistsError:
    if not link.is_symlink() or os.readlink(link) != str(target):
        raise SystemExit('Existing command was not replaced / 未替换已有命令: ' + str(link))
print('FileWeave CLI: ' + str(link))
PY
