#!/usr/bin/env python3
"""Длинные записи для стресс-теста (5 / 30 / 90 минут).

Берём чистый 3-спикерный диалог `piper_3spk`, повторяем его целое число раз и
добиваем тишиной ровно до 5, 30 и 90 минут. Эталон (`*.ref.json`) строится из того
же диалога со сдвигом времени на каждый повтор, поэтому WER и DER считаются честно
(`tools/eval/eval.py`). Проверяем, что качество и скорость не плывут на длинной записи,
а приложение не падает по памяти.

Запуск:  .cache/venv/bin/python -I tools/eval/make_long_tests.py
Прогон:  .cache/venv/bin/python tools/eval/proto_pipeline.py .cache/test/long30.wav .cache/test/long30.hyp.json
         .cache/venv/bin/python tools/eval/eval.py .cache/test/long30.ref.json .cache/test/long30.hyp.json
"""
import json, sys
import numpy as np, soundfile as sf

SR = 16000
SRC_WAV = ".cache/test/piper_3spk.wav"
SRC_REF = ".cache/test/piper_3spk.ref.json"
TARGETS = {"long5": 5 * 60, "long30": 30 * 60, "long90": 90 * 60}

x, sr = sf.read(SRC_WAV, dtype="float32", always_2d=True)
x = x.mean(axis=1)
if sr != SR:
    sys.exit(f"ожидали {SR} Гц, получили {sr}")
unit_samples = len(x)
unit_ms = int(round(unit_samples / SR * 1000))
ref = json.load(open(SRC_REF))["transcript"]
print(f"единица: {unit_samples} сэмплов = {unit_ms} мс, {len(ref)} реплик")

for name, tgt_s in TARGETS.items():
    tgt_samples = tgt_s * SR
    n = tgt_samples // unit_samples                      # целых повторов, которые влезают
    body = np.tile(x, n)
    pad = tgt_samples - len(body)                        # хвост тишины до точной длины
    audio = np.concatenate([body, np.zeros(pad, dtype=np.float32)])
    sf.write(f".cache/test/{name}.wav", audio, SR, subtype="PCM_16")
    segs = []
    for r in range(n):
        off = r * unit_ms
        for s in ref:
            segs.append({"speaker_id": s["speaker_id"],
                         "start_ms": s["start_ms"] + off,
                         "end_ms": s["end_ms"] + off,
                         "text": s["text"]})
    json.dump({"transcript": segs}, open(f".cache/test/{name}.ref.json", "w"), ensure_ascii=False)
    print(f"{name}: {n} повторов + {pad / SR:.1f} с тишины = {len(audio) / SR / 60:.1f} мин, {len(segs)} реплик в эталоне")
