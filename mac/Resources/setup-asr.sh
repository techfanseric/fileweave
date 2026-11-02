#!/bin/bash
# FileWeave — 本地转录环境安装
#
# 技术选型（2026-10-12 实测后确定）：
#   sherpa-onnx，不装 PyTorch。
#   - 识别：Paraformer-zh int8（中文，M1 上 RTF 约 0.01）
#   - 说话人：pyannote-segmentation-3-0 + 3dspeaker CAM++(zh-cn)
#     （这套组合曾在真实双人通话录音上测到 99.7% 说话人归属准确率。）
#     模型默认放在应用数据目录 asr/models/speaker 下；已有模型文件的话，
#     用环境变量 FILEWEAVE_SPEAKER_MODELS 指向自己的模型目录
#     （目录里要有下面那两个 onnx 文件）。
#
# 模型从 ModelScope 镜像下（实测 10MB/s，比 PyPI 快 30 倍以上）。
# 音频全程不出网。
set -euo pipefail

fw_message() {
  if [ "${FILEWEAVE_LANGUAGE:-en}" = "zh-Hans" ]; then
    printf '%s\n' "$1"
  else
    printf '%s\n' "$2"
  fi
}

BASE="${FILEWEAVE_ASR_DIR:-$HOME/Library/Application Support/FileWeave/asr}"
VENV="$BASE/venv"
MODELS="$BASE/models"
PY="$VENV/bin/python"
MS="https://modelscope.cn/models/ZhaoChaoqun/sherpa-onnx-asr-models/resolve/master"

MIRROR=()
if curl -s -m 5 -o /dev/null https://pypi.tuna.tsinghua.edu.cn/simple/ 2>/dev/null; then
  MIRROR=(-i https://pypi.tuna.tsinghua.edu.cn/simple)
fi

fw_message "==> Python 虚拟环境" "==> Python virtual environment"
if ! "$PY" -c 'import sherpa_onnx, numpy' >/dev/null 2>&1; then
  PYBIN=""
  for c in /opt/homebrew/bin/python3.13 /usr/local/bin/python3.13 python3.13 \
           /opt/homebrew/bin/python3.12 /usr/local/bin/python3.12 python3.12; do
    if command -v "$c" >/dev/null 2>&1 && "$c" --version >/dev/null 2>&1; then
      PYBIN="$c"
      break
    fi
  done
  [ -z "$PYBIN" ] && { fw_message "找不到可运行的 Python 3.12–3.13，请先安装其中一个版本" "Python 3.12–3.13 was not found. Install either version first."; exit 1; }
  mkdir -p "$BASE"
  # venv 的解释器链接和 pip/activate 都包含绝对路径，搬家或 Homebrew
  # 移除旧 Python 后必须重建。只重建 venv，不动模型、转录与凭据。
  BACKUP=""
  if [ -e "$VENV" ]; then
    BACKUP="$(mktemp -d "$BASE/.venv-backup.XXXXXX")"
    mv "$VENV" "$BACKUP/venv"
  fi
  restore_venv() {
    result=$?
    if [ "$result" -ne 0 ]; then
      rm -rf "$VENV"
      if [ -n "$BACKUP" ]; then mv "$BACKUP/venv" "$VENV"; fi
    fi
    if [ -n "$BACKUP" ]; then rmdir "$BACKUP" 2>/dev/null || true; fi
  }
  trap restore_venv EXIT
  "$PYBIN" -m venv "$VENV"
fi
"$PY" --version

fw_message "==> 安装 sherpa-onnx（轻量，无需 PyTorch）" "==> Installing sherpa-onnx (no PyTorch required)"
# python.org 的 macOS 安装有时尚未运行 Install Certificates.command。
# 使用系统 CA 文件补齐信任，仍然校验 HTTPS，不使用 trusted-host。
if [ -z "${PIP_CERT:-}" ] && [ -f /etc/ssl/cert.pem ] && \
   ! "$PY" -c 'import ssl, sys; sys.exit(0 if ssl.get_default_verify_paths().cafile else 1)'; then
  export PIP_CERT=/etc/ssl/cert.pem
fi
"$PY" -m pip install -q --upgrade pip ${MIRROR[@]+"${MIRROR[@]}"}
# 固定真实录音回归使用的版本；numpy 2.5 要求 Python >= 3.12。
"$PY" -m pip install -q 'sherpa-onnx==1.13.8' 'numpy==2.5.4' ${MIRROR[@]+"${MIRROR[@]}"}
"$PY" -c "import sherpa_onnx, numpy; print('sherpa-onnx', sherpa_onnx.__version__, 'numpy', numpy.__version__)"
if [ -n "${BACKUP:-}" ]; then
  rm -rf "$BACKUP"
  BACKUP=""
fi
# 从这里开始失败也保留已验证可运行的新环境。
trap - EXIT

mkdir -p "$MODELS/speaker"

fw_message "==> 说话人模型" "==> Speaker models"
# 说话人模型来源：FILEWEAVE_SPEAKER_MODELS 指定的目录，或已安装的 models/speaker
TT="${FILEWEAVE_SPEAKER_MODELS:-$MODELS/speaker}"
if [ ! -f "$TT/pyannote-segmentation-3-0.onnx" ]; then
  fw_message "  在 $TT 找不到说话人模型。" "  Speaker models not found in $TT."
  fw_message "  放置方式：把下面两个文件拷进该目录，或用 FILEWEAVE_SPEAKER_MODELS 指向已有目录" "  Copy these files into that folder, or set FILEWEAVE_SPEAKER_MODELS to an existing model folder:"
  echo "    pyannote-segmentation-3-0.onnx"
  echo "    3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
fi
for f in pyannote-segmentation-3-0.onnx \
         3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx; do
  if [ -f "$TT/$f" ]; then
    if [ ! -f "$MODELS/speaker/$f" ]; then cp "$TT/$f" "$MODELS/speaker/$f"; fi
    fw_message "  复用 $f" "  Reusing $f"
  else
    fw_message "  缺少 ${f}，需从 sherpa-onnx releases 手动下载" "  Missing ${f}; download it from sherpa-onnx releases."
  fi
done

fw_message "==> 识别模型 Paraformer-zh（ModelScope，约 1GB）" "==> Paraformer-zh speech model (ModelScope, about 1 GB)"
if [ ! -f "$MODELS/sherpa-onnx-paraformer-zh-2023-03-28/model.int8.onnx" ]; then
  curl -fL --retry 3 -o "$MODELS/pf.tar.bz2" \
    "$MS/sherpa-onnx-paraformer-zh-2023-03-28.tar.bz2"
  tar xjf "$MODELS/pf.tar.bz2" -C "$MODELS"
  rm -f "$MODELS/pf.tar.bz2"
  fw_message "  下载完成" "  Download complete"
else
  fw_message "  已存在，跳过" "  Already installed; skipping"
fi

echo
fw_message "==> 安装完成" "==> Installation complete"
du -sh "$BASE"
fw_message "环境位置：$BASE" "Environment: $BASE"
fw_message "删除该目录即可彻底卸载转录功能。" "Remove this folder to uninstall transcription."
