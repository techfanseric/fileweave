#!/bin/bash
# 开发者一键运行：构建 .app 然后 open。
set -eu
cd "$(dirname "$0")"
./build.sh
open "$PWD/build/FileWeave Mac.app"
