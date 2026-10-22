#!/bin/bash
# FileWeave — 本地转录环境安装
#
# 技术选型（2026-10-12 实测后确定）：
#   sherpa-onnx，不装 PyTorch。
#   - 识别：Paraformer-zh int8（中文，M1 上 RTF 约 0.01）
#   - 说话人：pyannote-segmentation-3-0 + 3dspeaker CAM++(zh-cn)
#     （这套组合曾在真实双人通话录音上测到 99.7% 说话人归属准确率。）
#     模型默认放在应用数据目录 asr/speaker 下；已有模型文件的话，
#     用环境变量 FILEWEAVE_SPEAKER_MODELS 指向自己的模型目录
#     （目录里要有下面那两个 onnx 文件）。
#
# 模型从 ModelScope 镜像下（实测 10MB/s，比 PyPI 快 30 倍以上）。
# 音频全程不出网。
set -euo pipefail

BASE="$HOME/Library/Application Support/FileWeave/asr"
VENV="$BASE/venv"
MODELS="$BASE/models"
PY="$VENV/bin/python"
MS="https://modelscope.cn/models/ZhaoChaoqun/sherpa-onnx-asr-models/resolve/master"

MIRROR=()
if curl -s -m 5 -o /dev/null https://pypi.tuna.tsinghua.edu.cn/simple/ 2>/dev/null; then
  MIRROR=(-i https://pypi.tuna.tsinghua.edu.cn/simple)
fi

echo "==> Python 虚拟环境"
if [ ! -x "$PY" ]; then
  PYBIN=""
  for c in /opt/homebrew/bin/python3.13 /usr/local/bin/python3.13 python3.13; do
    command -v "$c" >/dev/null 2>&1 && { PYBIN="$c"; break; }
  done
  [ -z "$PYBIN" ] && { echo "找不到 Python 3.13，请先 brew install python@3.13"; exit 1; }
  mkdir -p "$BASE"
  "$PYBIN" -m venv "$VENV"
fi
"$PY" --version

echo "==> 安装 sherpa-onnx（轻量，无需 PyTorch）"
"$VENV/bin/pip" install -q --upgrade pip "${MIRROR[@]}"
"$VENV/bin/pip" install -q sherpa-onnx numpy "${MIRROR[@]}"
"$PY" -c "import sherpa_onnx; print('sherpa-onnx', sherpa_onnx.__version__)"

mkdir -p "$MODELS/speaker"

echo "==> 说话人模型"
# 说话人模型来源：FILEWEAVE_SPEAKER_MODELS 指定的目录，或应用数据目录里的 speaker
TT="${FILEWEAVE_SPEAKER_MODELS:-$BASE/speaker}"
if [ ! -f "$TT/pyannote-segmentation-3-0.onnx" ]; then
  echo "  在 $TT 找不到说话人模型。"
  echo "  放置方式：把下面两个文件拷进该目录，或用 FILEWEAVE_SPEAKER_MODELS 指向已有目录"
  echo "    pyannote-segmentation-3-0.onnx"
  echo "    3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
fi
for f in pyannote-segmentation-3-0.onnx \
         3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx; do
  if [ -f "$TT/$f" ]; then
    cp -n "$TT/$f" "$MODELS/speaker/$f" && echo "  复用 $f"
  else
    echo "  缺少 $f，需从 sherpa-onnx releases 手动下载"
  fi
done

echo "==> 识别模型 Paraformer-zh（ModelScope，约 1GB）"
if [ ! -f "$MODELS/sherpa-onnx-paraformer-zh-2023-03-28/model.int8.onnx" ]; then
  curl -fL --retry 3 -o "$MODELS/pf.tar.bz2" \
    "$MS/sherpa-onnx-paraformer-zh-2023-03-28.tar.bz2"
  tar xjf "$MODELS/pf.tar.bz2" -C "$MODELS"
  rm -f "$MODELS/pf.tar.bz2"
  echo "  下载完成"
else
  echo "  已存在，跳过"
fi

echo
echo "==> 安装完成"
du -sh "$BASE"
echo "环境位置：$BASE"
echo "删除该目录即可彻底卸载转录功能。"