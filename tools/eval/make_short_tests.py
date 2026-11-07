#!/usr/bin/env python3
"""
Короткие проверки режима «Авто» для числа спикеров (Piper TTS + macOS say, голоса как в make_piper_dialogs.py):
  short_2v_*  — два разных голоса по ~1 с, всего ~4 с (должно быть 2 спикера);
  mono_*      — монолог одного голоса ~25 с (должен остаться 1 спикер).

  .cache/venv/bin/python tools/eval/make_short_tests.py [--out-dir .cache/test]
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_piper_dialogs import SR, Voices, active_rms, colored_noise, trim, write  # noqa: E402

SHORT = {
    "short_2v_mf": [("denis", "Привет, Максим."), ("irina", "Привет, Никита.")],
    "short_2v_mm": [("dmitri", "Привет, Максим."), ("ruslan", "Привет, Никита.")],
    "short_2v_mm2": [("denis", "Ну что, начинаем?"), ("dmitri", "Да, давай.")],
    "short_3t_mf": [("ruslan", "Привет, Максим."), ("irina", "Привет! Как дела?"), ("ruslan", "Отлично.")],
}

MONO_TEXT = [
    "Сегодня я расскажу, как прошла наша неделя.",
    "В понедельник мы закончили сборку первой версии приложения.",
    "Во вторник нашли несколько ошибок в распознавании речи и быстро их исправили.",
    "В среду весь день ушёл на тесты, а вечером мы наконец собрали релиз.",
    "Дальше планируем добавить поиск по всем разговорам и экспорт в документ.",
    "Если будут вопросы, пишите, я на связи.",
]
MONO = {"mono_dmitri": "dmitri", "mono_irina": "irina", "mono_milena": "milena"}


def build(parts, rng, gaps=(0.3, 0.6)):
    audio = [np.zeros(int(rng.uniform(0.5, 0.8) * SR), np.float32)]
    pos = len(audio[0])
    ref, ids = [], {}
    for i, (voice, text, seg) in enumerate(parts):
        seg = seg / active_rms(seg) * 10 ** (-22 / 20)
        sid = ids.setdefault(voice, f"speaker_{len(ids) + 1}")
        ref.append({"speaker_id": sid, "start_ms": int(round(pos * 1000 / SR)),
                    "end_ms": int(round((pos + len(seg)) * 1000 / SR)), "text": text})
        audio += [seg.astype(np.float32)]
        pos += len(seg)
        g = np.zeros(int((rng.uniform(*gaps) if i + 1 < len(parts) else rng.uniform(0.6, 0.9)) * SR), np.float32)
        audio.append(g)
        pos += len(g)
    x = np.concatenate(audio)
    x = x + 10 ** (-60 / 20) * colored_noise(len(x), rng, 1.0).astype(np.float32)
    return x.astype(np.float32), ref


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default=".cache/test")
    ap.add_argument("--tts-dir", default=".cache/tts")
    ap.add_argument("--clip-cache", default=".cache/tts/clips")
    a = ap.parse_args()
    voices = Voices(a.tts_dir, a.clip_cache)
    rng = np.random.default_rng(20261107)
    for name, spec in SHORT.items():
        x, ref = build([(v, t, trim(voices.synth(v, t))) for v, t in spec], rng)
        write(a.out_dir, name, x, ref)
        print(f"{name:14s} {len(x) / SR:5.1f} s  " + " | ".join(f"{s['speaker_id']} {(s['end_ms'] - s['start_ms']) / 1000:.2f} s" for s in ref))
    for name, v in MONO.items():
        if v not in voices.available:
            print(f"[skip] {name}: нет голоса {v}", file=sys.stderr)
            continue
        x, ref = build([(v, t, trim(voices.synth(v, t))) for t in MONO_TEXT], rng, gaps=(0.25, 0.7))
        write(a.out_dir, name, x, ref)
        print(f"{name:14s} {len(x) / SR:5.1f} s  реплик {len(ref)}")


if __name__ == "__main__":
    main()
