#!/usr/bin/env python3
"""
Самопроверка качества по экспортированному JSON (формат ТЗ):
  WER  — по словам, после нормализации (нижний регистр, без пунктуации, ё→е);
  DER  — доля времени с неверным спикером / пропуском / лишней речью
         (спикеры сопоставляются жадно по максимальному пересечению);
  Tstart/Tend — средняя ошибка границ реплик (мс) для пар «эталон ↔ ближайшая гипотеза».

  python3 tools/eval/eval.py ref.json hyp.json [--rtf processing_ms duration_ms]
Только стандартная библиотека Python.
"""
import json
import re
import sys
from collections import defaultdict


def load(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)["transcript"]


def norm_words(text):
    t = text.lower().replace("ё", "е")
    t = re.sub(r"[^\w\s]", " ", t)
    return t.split()


def wer(ref_words, hyp_words):
    n, m = len(ref_words), len(hyp_words)
    d = list(range(m + 1))
    for i in range(1, n + 1):
        prev, d[0] = d[0], i
        for j in range(1, m + 1):
            cur = d[j]
            d[j] = min(d[j] + 1, d[j - 1] + 1, prev + (ref_words[i - 1] != hyp_words[j - 1]))
            prev = cur
    return d[m] / max(1, n)


def frames(segs, step=10):
    lab = {}
    for s in segs:
        for t in range(s["start_ms"] // step, s["end_ms"] // step):
            lab[t] = s["speaker_id"]
    return lab


def der(ref, hyp, step=10):
    r, h = frames(ref, step), frames(hyp, step)
    # жадное сопоставление спикеров по пересечению
    ov = defaultdict(int)
    for t, rs in r.items():
        if t in h:
            ov[(rs, h[t])] += 1
    mapping, used = {}, set()
    for (rs, hs), _ in sorted(ov.items(), key=lambda kv: -kv[1]):
        if rs not in mapping and hs not in used:
            mapping[rs] = hs
            used.add(hs)
    miss = sum(1 for t in r if t not in h)
    fa = sum(1 for t in h if t not in r)
    conf = sum(1 for t, rs in r.items() if t in h and mapping.get(rs) != h[t])
    return (miss + fa + conf) / max(1, len(r)), miss / max(1, len(r)), fa / max(1, len(r)), conf / max(1, len(r))


def boundary_error(ref, hyp):
    if not hyp:
        return float("nan"), float("nan")
    ds, de = [], []
    for s in ref:
        best = min(hyp, key=lambda x: abs(x["start_ms"] - s["start_ms"]) + abs(x["end_ms"] - s["end_ms"]))
        ds.append(abs(best["start_ms"] - s["start_ms"]))
        de.append(abs(best["end_ms"] - s["end_ms"]))
    return sum(ds) / len(ds), sum(de) / len(de)


def main():
    ref, hyp = load(sys.argv[1]), load(sys.argv[2])
    rw = [w for s in ref for w in norm_words(s["text"])]
    hw = [w for s in hyp for w in norm_words(s["text"])]
    w = wer(rw, hw)
    d, miss, fa, conf = der(ref, hyp)
    bs, be = boundary_error(ref, hyp)
    print(f"WER      {w * 100:6.2f} %   ({len(rw)} слов в эталоне)")
    print(f"DER      {d * 100:6.2f} %   (пропуск {miss * 100:.1f}, лишнее {fa * 100:.1f}, путаница {conf * 100:.1f})")
    print(f"Спикеры  эталон {len({s['speaker_id'] for s in ref})}, найдено {len({s['speaker_id'] for s in hyp})}")
    print(f"Границы  старт ±{bs:.0f} мс, конец ±{be:.0f} мс")
    if "--rtf" in sys.argv:
        i = sys.argv.index("--rtf")
        p, dur = float(sys.argv[i + 1]), float(sys.argv[i + 2])
        print(f"RTF      {p / dur:.3f}")


if __name__ == "__main__":
    main()
