#!/usr/bin/env python3
"""FileWeave — 本地转录。

用 sherpa-onnx 做三件事，全程离线：
    1. Silero VAD      → 切出有人说话的片段
    2. Paraformer-zh   → 每段的文字 + 句级时间戳
    3. Speaker Diarization → 谁在什么时候说话

最后把说话人结果按时间重叠贴回每一段，输出「说话人 + 时间戳 + 内容」。

用法:
    transcribe.py <音频.wav> <输出txt路径> [--speakers N] [--model paraformer|zipformer]

约定:
- 第 1 行 stdout 输出 `META <音频秒数>`，供上层估算耗时
- 之后每段输出一行 `SEG <开始ms> <结束ms> <说话人> <文本>`
- 最终 TXT 写到 <输出txt路径>，同时写一个 .json 侧车文件
- 退出码 0 成功，非 0 失败（原因写 stderr）
"""

import argparse
import json
import os
import sys
import time
from datetime import datetime

import numpy as np
import sherpa_onnx

SR = 16000


def log(msg):
    print(msg, file=sys.stderr, flush=True)


def fmt_ts(ms):
    """毫秒 → MM:SS.t，保留一位小数，短句也能看出先后"""
    t = int(ms) / 1000.0
    m = int(t // 60)
    s = t - m * 60
    return f"{m:02d}:{s:04.1f}"


def read_wav(path):
    """读 16-bit 单声道 wav，返回 float32 采样与采样率"""
    import wave
    with wave.open(path, "rb") as f:
        if f.getnchannels() != 1 or f.getsampwidth() != 2:
            raise RuntimeError("只支持 16-bit 单声道 wav")
        raw = f.readframes(f.getnframes())
        rate = f.getframerate()
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    return samples, rate


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("audio")
    ap.add_argument("output")
    ap.add_argument("--speakers", type=int, default=0, help="说话人数，0=自动")
    ap.add_argument("--model", default="paraformer", choices=["paraformer", "zipformer"])
    args = ap.parse_args()

    if not os.path.exists(args.audio):
        log(f"音频不存在: {args.audio}")
        return 2

    started = time.time()
    try:
        samples, rate = read_wav(args.audio)
    except Exception as e:
        log(f"读取音频失败: {e}")
        return 2
    duration = len(samples) / float(rate)
    print(f"META {duration:.1f}", flush=True)

    base = os.path.dirname(os.path.abspath(__file__))
    # 模型目录：优先用打包进 app 的，其次用 Application Support 下的
    candidates = [
        os.path.join(base, "models"),
        os.path.expanduser(
            "~/Library/Application Support/FileWeave/asr/models"),
    ]
    models_dir = next((d for d in candidates if os.path.isdir(d)), None)
    if not models_dir:
        log("找不到模型目录")
        return 4

    threads = max(2, min(8, os.cpu_count() or 4))

    # ---------- 1. 语音活动检测（能量阈值法，不依赖外部模型） ----------
    # 试过 sherpa 自带的 Silero VAD：1.13.8 + 该模型组合在任何音频上都返回空，
    # 连 paraformer 自带的 test_wavs 都检测不到语音，疑似版本不兼容。
    # 改用能量阈值，零依赖、可控，对通话录音足够。
    segments = []
    t_vad = time.time()

    def energy_segments(sig, sr=SR):
        frame = int(0.02 * sr)      # 20ms
        hop = int(0.01 * sr)        # 10ms
        n = (len(sig) - frame) // hop
        if n <= 0:
            return [(0.0, len(sig) / sr)] if len(sig) else []
        idx = np.arange(frame)[None, :] + hop * np.arange(n)[:, None]
        rms = np.sqrt((sig[idx] ** 2).mean(axis=1))

        # 自适应门限：以噪声底的 3 倍为界，并设绝对下限
        noise = float(np.percentile(rms, 20))
        thresh = max(noise * 3.0, 0.008)
        voiced = rms > thresh

        segs, i = [], 0
        while i < n:
            if not voiced[i]:
                i += 1
                continue
            j = i
            while j < n and voiced[j]:
                j += 1
            segs.append([i * hop / sr, j * hop / sr + frame / sr])
            i = j

        # 合并间隔小于 0.3s 的相邻段（避免把一句话切碎）
        merged = []
        for s0, s1 in segs:
            if merged and s0 - merged[-1][1] < 0.3:
                merged[-1][1] = s1
            else:
                merged.append([s0, s1])
        # 丢掉太短的碎片（太短的输入会让 Paraformer 算出负的特征帧）
        merged = [(a, b) for a, b in merged if b - a >= 0.35]
        return merged or [(0.0, duration)]

    try:
        # energy_segments 返回 (起点, 终点)，统一转成 (起点, 时长) 供后面使用
        segments = [(a, b - a) for a, b in energy_segments(samples)]
    except Exception as e:
        log(f"分段失败，退回整段处理: {e}")
        segments = [(0.0, duration)]

    # 过长的片段再切一刀，避免单段超过模型上限（Paraformer 约 20s）
    refined = []
    for st, ln in segments:
        while ln > 19.0:
            refined.append((st, 19.0))
            st += 19.0
            ln -= 19.0
        if ln > 0.15:
            refined.append((st, ln))
    segments = refined
    log(f"分段: {len(segments)} 段，耗时 {time.time() - t_vad:.1f}s")

    # ---------- 2. 语音识别 ----------
    t_asr = time.time()
    if args.model == "zipformer":
        d = os.path.join(models_dir, "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23")
        rec = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=os.path.join(d, "encoder-epoch-99-avg-1.onnx"),
            decoder=os.path.join(d, "decoder-epoch-99-avg-1.onnx"),
            joiner=os.path.join(d, "joiner-epoch-99-avg-1.onnx"),
            tokens=os.path.join(d, "tokens.txt"),
            num_threads=threads, sample_rate=SR, feature_dim=80,
            decoding_method="greedy_search")
        model_name = "Zipformer-zh-14M"
    else:
        d = os.path.join(models_dir, "sherpa-onnx-paraformer-zh-2023-03-28")
        fp32 = os.path.join(d, "model.onnx")
        int8 = os.path.join(d, "model.int8.onnx")
        model_file = int8 if os.path.exists(int8) else fp32
        rec = sherpa_onnx.OfflineRecognizer.from_paraformer(
            paraformer=model_file,
            tokens=os.path.join(d, "tokens.txt"),
            num_threads=threads, sample_rate=SR, feature_dim=80,
            decoding_method="greedy_search")
        model_name = "Paraformer-zh" + (" int8" if model_file == int8 else "")
    log(f"ASR 就绪 {time.time() - t_asr:.1f}s（{model_name}，{threads} 线程）")

    t_asr2 = time.time()
    MIN_SAMPLES = int(0.60 * SR)   # 低于这个长度一律补静音，避免负特征帧
    texts = []
    failed = 0
    for st, ln in segments:
        off = int(st * SR)
        chunk = samples[off:off + int(ln * SR)]
        if len(chunk) < int(0.35 * SR):
            texts.append("")
            continue
        if len(chunk) < MIN_SAMPLES:
            chunk = np.pad(chunk, (0, MIN_SAMPLES - len(chunk)))
        try:
            s = rec.create_stream()
            s.accept_waveform(SR, chunk)
            rec.decode_stream(s)
            texts.append((s.result.text or "").strip())
        except Exception as e:
            # 单段失败就丢掉这一段，不让整篇报废
            failed += 1
            texts.append("")
            if failed <= 3:
                log(f"  片段 {st:.1f}s 解码失败，已跳过: {type(e).__name__}")
    asr_secs = time.time() - t_asr2
    log(f"ASR 完成 {len(segments)} 段（失败 {failed}），耗时 {asr_secs:.1f}s"
        f"（RTF {asr_secs / max(duration, .01):.3f}）")

    # ---------- 3. 说话人分离 ----------
    spk_turns = []
    t_spk = time.time()
    try:
        spk_dir = os.path.join(models_dir, "speaker")
        seg_cfg = sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=os.path.join(spk_dir, "pyannote-segmentation-3-0.onnx")
            )
        )
        emb_cfg = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
            model=os.path.join(spk_dir,
                               "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"),
            num_threads=threads,
        )
        clu_cfg = sherpa_onnx.FastClusteringConfig(
            num_clusters=args.speakers if args.speakers > 0 else -1,
            threshold=0.5,
        )
        sd_cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
            segmentation=seg_cfg,
            embedding=emb_cfg,
            clustering=clu_cfg,
            min_duration_on=0.25,
            min_duration_off=0.35,
        )
        sd = sherpa_onnx.OfflineSpeakerDiarization(sd_cfg)
        result = sd.process(samples)
        # sort_by_start_time() 是方法，返回按时间排好的 segment 列表
        for t in result.sort_by_start_time():
            spk_turns.append((t.start, t.end, t.speaker))
        log(f"说话人分离: {len(spk_turns)} 段 / {result.num_speakers} 人，"
            f"耗时 {time.time() - t_spk:.1f}s")
    except Exception as e:
        log(f"说话人分离不可用（将只输出文字与时间戳）: {e}")

    # ---------- 4. 合并：按时间重叠把说话人贴回每段 ----------
    sentences = []
    for (st, ln), text in zip(segments, texts):
        if not text:
            continue
        end = st + ln
        # 取该段内重叠最多的说话人
        best_spk, best_ov = None, 0.0
        for (ts, te, spk) in spk_turns:
            ov = min(end, te) - max(st, ts)
            if ov > best_ov:
                best_ov, best_spk = ov, spk
        # 重叠太少就不硬贴标签，宁可留空也不给错人
        label = str(best_spk) if best_spk is not None and best_ov >= 0.35 * ln else "-"
        sentences.append({
            "start": int(st * 1000),
            "end": int(end * 1000),
            "spk": None if label == "-" else label,
            "text": text,
        })

    if not sentences:
        log("没有识别到任何语音内容")
        return 7

    for s in sentences:
        print(f"SEG {s['start']} {s['end']} {s['spk'] or '-'} {s['text']}", flush=True)

    # ---------- 5. 写文件 ----------
    speakers = [s["spk"] for s in sentences if s["spk"]]
    # 聚类出来的是 0/1/2…，按「谁先开口」映射成 1/2/3…，符合阅读直觉
    spk_map = {}
    for s in sentences:
        spk = s["spk"]
        if spk and spk not in spk_map:
            spk_map[spk] = str(len(spk_map) + 1)
    for s in sentences:
        if s["spk"]:
            s["spk"] = spk_map[s["spk"]]
    speakers = [s["spk"] for s in sentences if s["spk"]]
    spk_count = len(set(speakers))

    dur = f"{int(duration) // 3600:02d}:{(int(duration) % 3600) // 60:02d}:{int(duration) % 60:02d}"
    lines = [
        f"文件：{os.path.basename(args.audio)}",
        f"时长：{dur}",
        f"生成：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        f"模型：{model_name} + 能量分段 + CAM++(zh-cn) 说话人分离",
        f"句子：{len(sentences)} 句" + (f"，说话人：{spk_count} 人" if spk_count else "，未分离说话人"),
        f"耗时：ASR {asr_secs:.1f}s，说话人 {time.time() - t_spk:.1f}s，"
        f"合计 {time.time() - started:.1f}s",
        "",
        "─" * 48,
        "",
    ]
    for s in sentences:
        head = f"[{fmt_ts(s['start'])} → {fmt_ts(s['end'])}]"
        if s["spk"]:
            lines.append(f"{head} 说话人 {s['spk']}")
        else:
            lines.append(head)
        lines.append(s["text"])
        lines.append("")

    out_dir = os.path.dirname(os.path.abspath(args.output))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    with open(os.path.splitext(args.output)[0] + ".json", "w", encoding="utf-8") as f:
        json.dump({
            "audio": os.path.basename(args.audio),
            "duration": duration,
            "model": model_name,
            "sentenceCount": len(sentences),
            "speakerCount": spk_count,
            "asrSeconds": round(asr_secs, 1),
            "rtf": round(asr_secs / max(duration, .01), 3),
            "totalSeconds": round(time.time() - started, 1),
            "sentences": sentences,
        }, f, ensure_ascii=False, indent=2)

    log(f"完成：{len(sentences)} 句，{spk_count} 个说话人")
    return 0


if __name__ == "__main__":
    sys.exit(main())