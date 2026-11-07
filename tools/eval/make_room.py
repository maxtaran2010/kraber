#!/usr/bin/env python3
"""
«Живые» условия для тестового набора: из чистого диалога с эталоном делает запись «телефоном в комнате».

  - эхо комнаты (RT60 0.5–0.9 с), у каждого спикера своё место: своё расстояние, уровень и импульсная характеристика;
    люди немного двигаются — у каждой реплики своя смесь двух характеристик;
  - микрофон телефона: полоса ~120–7000 Гц;
  - шум комнаты (розовый + коричневый) и стуки по столу (пики под 0 дБ — ломают нормализацию по пику);
  - --whisper: шёпот соседей у самого микрофона (громче далёкого лектора), в эталон не входит.

Эталон (.ref.json) копируется как есть: таймкоды не меняются.

  .cache/venv/bin/python -I tools/eval/make_room.py .cache/test/piper_2spk.wav .cache/test/room_2spk [--whisper] [--seed N]
"""
import argparse
import json
import os
import sys

import numpy as np
import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_piper_dialogs import colored_noise, active_rms  # noqa: E402

SR = 16000


def rir(rng, rt60, drr_db, n=None):
    """Импульсная характеристика: прямой звук + экспоненциально затухающий шумовой хвост с ранними отражениями."""
    n = n or int(rt60 * 1.2 * SR)
    t = np.arange(n) / SR
    tail = rng.standard_normal(n) * np.exp(-6.9 * t / rt60)
    # хвост темнее прямого звука (поглощение высоких)
    k = np.exp(-np.arange(64) / 6.0)
    tail = np.convolve(tail, k / k.sum(), mode="same")
    tail[: int(0.004 * SR)] = 0
    for _ in range(6):  # ранние отражения
        d = int(rng.uniform(0.004, 0.03) * SR)
        tail[d] += rng.uniform(-0.6, 0.6) * 3
    h = tail / np.sqrt((tail ** 2).sum()) * 10 ** (-drr_db / 20)
    h[0] += 1.0
    return h


def fftconv(x, h):
    n = len(x) + len(h) - 1
    m = 1 << (n - 1).bit_length()
    return np.fft.irfft(np.fft.rfft(x, m) * np.fft.rfft(h, m), m)[:n]


def bandpass(x, lo=120.0, hi=7000.0):
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    g = 1 / np.sqrt(1 + (lo / np.maximum(f, 1)) ** 4) / np.sqrt(1 + (f / hi) ** 8)
    return np.fft.irfft(X * g, len(x))


def whisperize(x, rng):
    """Шёпот: огибающая спектра речи (кепстральное сглаживание) на шумовом возбуждении."""
    nfft, hop = 512, 128
    win = np.hanning(nfft)
    pad = np.concatenate([np.zeros(nfft), x, np.zeros(nfft)])
    frames = np.lib.stride_tricks.sliding_window_view(pad, nfft)[::hop] * win
    S = np.fft.rfft(frames, axis=1)
    logm = np.log(np.abs(S) + 1e-6)
    cep = np.fft.irfft(logm, axis=1)
    cep[:, 30:-30] = 0
    env = np.exp(np.fft.rfft(cep, axis=1).real)
    noise = np.fft.rfft(rng.standard_normal(frames.shape) * win, axis=1)
    Y = env * noise / (np.abs(noise) + 1e-9)
    y = np.fft.irfft(Y, nfft, axis=1) * win
    out = np.zeros(len(pad))
    for i, fr in enumerate(y):
        out[i * hop: i * hop + nfft] += fr
    out = out[nfft: nfft + len(x)]
    return bandpass(out, 300, 7000)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("inp")
    ap.add_argument("out", help="путь без расширения: пишет <out>.wav и <out>.ref.json")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--rt60", type=float, default=0.0, help="0 — случайно 0.5–0.9 с")
    ap.add_argument("--snr", type=float, default=15.0)
    ap.add_argument("--whisper", action="store_true")
    ap.add_argument("--whisper-src", default=".cache/test/mono_dmitri.wav,.cache/test/mono_irina.wav")
    a = ap.parse_args()
    rng = np.random.default_rng(a.seed)

    x, sr = sf.read(a.inp, dtype="float64")
    assert sr == SR
    ref = json.load(open(a.inp[:-4] + ".ref.json", encoding="utf-8"))
    utts = ref["transcript"] if isinstance(ref, dict) else ref
    rt60 = a.rt60 or rng.uniform(0.5, 0.9)

    # у каждого спикера своё место: DRR (дальше — меньше) и уровень
    spk = sorted({u["speaker_id"] for u in utts})
    place = {}
    for s in spk:
        drr = rng.uniform(-6, 6)
        place[s] = dict(h=(rir(rng, rt60, drr), rir(rng, rt60, drr + rng.uniform(-2, 2))),
                        gain=10 ** (rng.uniform(-14, -2) / 20))
    y = np.zeros(len(x) + int(rt60 * 1.2 * SR))
    for u in utts:
        s0, e0 = u["start_ms"] * SR // 1000, u["end_ms"] * SR // 1000
        seg = x[s0:e0]
        p = place[u["speaker_id"]]
        w = rng.uniform(0, 1)
        h = w * p["h"][0] + (1 - w) * p["h"][1]
        r = fftconv(seg, h) * p["gain"] * 10 ** (rng.uniform(-2, 2) / 20)
        y[s0:s0 + len(r)] += r
    y = y[: len(x)]
    speech = np.concatenate([y[u["start_ms"] * SR // 1000: u["end_ms"] * SR // 1000] for u in utts])
    p_speech = float(np.mean(speech ** 2))

    if a.whisper:
        srcs = [sf.read(p, dtype="float64")[0] for p in a.whisper_src.split(",") if os.path.exists(p)]
        n_bursts = max(3, int(len(x) / SR / 12))
        for _ in range(n_bursts):
            src = srcs[rng.integers(len(srcs))]
            L = int(rng.uniform(1.5, 4.0) * SR)
            st = rng.integers(0, max(1, len(src) - L))
            wsp = whisperize(src[st:st + L], rng)
            wsp *= np.sqrt(p_speech) * 10 ** (rng.uniform(0, 6) / 20) / (active_rms(wsp.astype(np.float32)) + 1e-9)
            pos = rng.integers(0, len(y) - L)
            y[pos:pos + L] += wsp

    noise = 0.7 * colored_noise(len(y), rng, 1.0) + 0.3 * colored_noise(len(y), rng, 2.0)
    y += noise / noise.std() * np.sqrt(p_speech / 10 ** (a.snr / 10))
    y = bandpass(y)
    # уровень записи: тихий (далёкий телефон), но стуки по столу — почти до 0 дБ
    y *= 10 ** (-38 / 20) / (np.sqrt(p_speech) + 1e-12)
    for _ in range(3):
        pos = rng.integers(0, len(y) - 800)
        y[pos:pos + 800] += rng.uniform(0.5, 0.9) * np.exp(-np.arange(800) / 80) * rng.choice([-1, 1])
    peak = np.abs(y).max()
    if peak > 0.99:
        y *= 0.99 / peak
    sf.write(a.out + ".wav", y.astype(np.float32), SR, subtype="PCM_16")
    json.dump(ref, open(a.out + ".ref.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    lvl = 20 * np.log10(np.sqrt(np.mean(y ** 2)) + 1e-12)
    print(f"{a.out}.wav: {len(y) / SR:.1f} s, RT60 {rt60:.2f}, RMS {lvl:.1f} dBFS, пик {20 * np.log10(np.abs(y).max()):.1f} dBFS")


if __name__ == "__main__":
    main()
