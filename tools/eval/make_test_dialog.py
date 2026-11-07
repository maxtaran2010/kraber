#!/usr/bin/env python3
"""
Синтетический тестовый диалог для самопроверки (macOS: say + afconvert).
Голос второго спикера получается сдвигом высоты тона (ресемплинг), третьего — повышением.
Пишет <out>.wav (16 кГц mono) и <out>.ref.json в формате ТЗ.

  python3 tools/eval/make_test_dialog.py out/dialog1
"""
import json
import os
import struct
import subprocess
import sys
import tempfile
import wave

SR = 16000

DIALOG = [
    (1, "Добрый день, коллеги! Начинаем заседание котов."),
    (2, "Здравствуйте. У меня короткий отчёт про запасы корма."),
    (1, "Слушаем внимательно."),
    (2, "За неделю мы съели двенадцать пакетов корма и половину пиццы."),
    (3, "Пиццу трогать было нельзя, это запасы на праздник."),
    (2, "Да, но мыши сказали, что она была ничья."),
    (1, "Хорошо. Барсик, проверь, пожалуйста, склад до пятницы."),
    (3, "Принято, проверю до пятницы и пришлю отчёт."),
    (1, "Тогда на сегодня всё. Спасибо всем!"),
]

# коэффициент «высоты»: <1 — ниже и медленнее, >1 — выше и быстрее
PITCH = {1: 1.0, 2: 0.80, 3: 1.18}
GAPS = [0.5, 0.7, 0.4, 0.9, 0.6, 0.5, 0.8, 0.6]


def tts(text: str) -> list[float]:
    with tempfile.TemporaryDirectory() as d:
        aiff, wav = os.path.join(d, "a.aiff"), os.path.join(d, "a.wav")
        subprocess.run(["say", "-v", "Milena", "-o", aiff, text], check=True)
        subprocess.run(["afconvert", "-f", "WAVE", "-d", f"LEI16@{SR}", "-c", "1", aiff, wav], check=True)
        with wave.open(wav) as w:
            raw = w.readframes(w.getnframes())
    n = len(raw) // 2
    return [v / 32768 for v in struct.unpack(f"<{n}h", raw)]


def repitch(x: list[float], k: float) -> list[float]:
    if k == 1.0:
        return x
    out_len = int(len(x) / k)
    out = []
    for i in range(out_len):
        t = i * k
        j = int(t)
        f = t - j
        a = x[j] if j < len(x) else 0.0
        b = x[j + 1] if j + 1 < len(x) else 0.0
        out.append(a + (b - a) * f)
    return out


def trim(x: list[float], thr: float = 0.01) -> list[float]:
    s = next((i for i, v in enumerate(x) if abs(v) > thr), 0)
    e = len(x) - next((i for i, v in enumerate(reversed(x)) if abs(v) > thr), 0)
    return x[max(0, s - 400): min(len(x), e + 400)]


def main(out: str):
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    audio: list[float] = [0.0] * int(0.8 * SR)
    ref = []
    for i, (spk, text) in enumerate(DIALOG):
        seg = trim(repitch(tts(text), PITCH[spk]))
        start = len(audio)
        audio += seg
        ref.append({"speaker_id": f"speaker_{spk}", "start_ms": start * 1000 // SR,
                    "end_ms": len(audio) * 1000 // SR, "text": text})
        audio += [0.0] * int((GAPS[i] if i < len(GAPS) else 0.8) * SR)
    with wave.open(out + ".wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(struct.pack(f"<{len(audio)}h", *[int(max(-1, min(1, v)) * 32767) for v in audio]))
    with open(out + ".ref.json", "w", encoding="utf-8") as f:
        json.dump({"transcript": ref}, f, ensure_ascii=False, indent=2)
    print(f"{out}.wav  {len(audio) / SR:.1f} s, {len(ref)} реплик")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "out/dialog1")
