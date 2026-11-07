#!/usr/bin/env python3
"""
Быстрый перебор параметров кластеризации спикеров (режим «Авто» и заданное число).
VAD, ASR, поиск смены спикера и эмбеддинги кусков считаются один раз и кэшируются в .cache/test/emb/<имя>.pkl,
дальше для каждого набора параметров — только cluster_embeddings() → слова → сглаживание → реплики → DER.

  .cache/venv/bin/python tools/eval/cluster_sweep.py [--files a,b,...] [--grid "merge_thr=0.6,0.7 small_thr=0.3"]
Файлы ищутся как .cache/test/<имя>.wav (эталон <имя>.ref.json — если есть) или .cache/test/dev/<имя>/audio.wav.
Ожидаемое число спикеров без эталона — в EXPECT ниже.
"""
import argparse
import copy
import itertools
import os
import pickle
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import proto_pipeline as pp  # noqa: E402
import eval as ev  # noqa: E402

T = ".cache/test"
FILES = ["piper_2spk", "piper_3spk", "piper_4spk", "piper_fast", "piper_noisy", "dialog1",
         "short_2v_mf", "short_2v_mm", "short_2v_mm2", "short_3t_mf", "mono_dmitri", "mono_irina", "mono_milena",
         "rmw69k9fr373", "rmw60lo5o928", "rmw5dvohp534", "rmw5c67c6641", "rmw5c5l14960"]
# записи с устройства без эталона: ожидаемое число спикеров (диапазон)
# rmw69k9fr373 «Привет, Максим» / «Привет, Никита» — два человека; rmw5dvohp534, rmw5c5l14960 — один голос;
# rmw60lo5o928, rmw5c67c6641 (экранный диктор + человек?) — не проверено; rmw60v43s371 «Оперконтур» (52 мин) — по словам участника, 6 человек
EXPECT = {"rmw69k9fr373": (2, 2), "rmw60lo5o928": (1, 2), "rmw5dvohp534": (1, 1), "rmw5c67c6641": (1, 2),
          "rmw5c5l14960": (1, 1), "rmw60v43s371": (5, 7)}


def wav_of(name):
    p = f"{T}/{name}.wav"
    return p if os.path.exists(p) else f"{T}/dev/{name}/audio.wav"


def prepare(name, a):
    """Всё, что не зависит от параметров кластеризации, — один раз и в кэш."""
    cache = f"{T}/emb/{name}.pkl"
    if os.path.exists(cache):
        with open(cache, "rb") as f:
            return pickle.load(f)
    t0 = time.time()
    x = pp.load(wav_of(name))
    segs = pp.vad_segments(x, a)
    words = pp.asr_words(x, segs)
    cut_turns = pp.scd_cuts(x, segs, a) + [(w["start"] - 0.02, w["start"] - 0.02, -1) for w in words if w.get("turn")]
    chunks = pp.make_chunks(segs, cut_turns, a)
    dur = np.array([e - s for s, e in chunks])
    idx = [i for i in range(len(chunks)) if dur[i] >= a.min_emb]
    path = a.emb or f"{pp.M}/diarization/embedding.onnx"
    G = np.stack([pp.embed(x, chunks[i][0], chunks[i][1], path) for i in idx]) if idx else np.zeros((0, 1))
    data = dict(words=words, chunks=chunks, idx=idx, G=G, dur=dur)
    os.makedirs(os.path.dirname(cache), exist_ok=True)
    with open(cache, "wb") as f:
        pickle.dump(data, f)
    print(f"[prep] {name}: {len(x) / pp.SR:.0f} s, {len(chunks)} кусков, {len(idx)} с эмбеддингом, {time.time() - t0:.0f} s",
          file=sys.stderr)
    return data


def evaluate(name, data, a):
    chunks, idx, G, dur = data["chunks"], data["idx"], data["G"], data["dur"]
    out = np.full(len(chunks), -1)
    if idx:
        out[idx] = pp.cluster_embeddings(G, dur[idx], a)
    else:
        out[:] = 0
    mids = np.array([(s + e) / 2 for s, e in chunks])
    for i in range(len(chunks)):
        if out[i] < 0:
            out[i] = out[min(idx, key=lambda j: abs(mids[j] - mids[i]))]
    turns = [(s, e, int(l)) for (s, e), l in zip(chunks, out)]
    words = copy.deepcopy(data["words"])
    pp.assign_speakers(words, turns)
    pp.smooth(words, a)
    utts = pp.to_utterances(words, a)
    k = len({u["speaker_id"] for u in utts})
    ref_p = f"{T}/{name}.ref.json"
    if os.path.exists(ref_p):
        ref = ev.load(ref_p)
        true = len({s["speaker_id"] for s in ref})
        return k, (true, true), ev.der(ref, utts)[0] * 100, utts
    return k, EXPECT.get(name, (None, None)), None, utts


def parse_grid(s):
    keys, vals = [], []
    for part in (s or "").split():
        k, v = part.split("=")
        keys.append(k)
        vals.append([float(t) for t in v.split(",")])
    return keys, list(itertools.product(*vals)) if keys else [()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--files", default=",".join(FILES))
    ap.add_argument("--grid", default="")
    ap.add_argument("--speakers", type=int, default=0)
    ap.add_argument("--verbose", action="store_true", help="таблица по каждому файлу")
    ap.add_argument("rest", nargs=argparse.REMAINDER, help="прочие аргументы proto_pipeline после --")
    o = ap.parse_args()
    base = pp.args(["x"] + [r for r in o.rest if r != "--"])
    base.speakers = o.speakers
    names = o.files.split(",")
    data = {n: prepare(n, base) for n in names}
    keys, combos = parse_grid(o.grid)
    for combo in combos:
        a = copy.copy(base)
        for k, v in zip(keys, combo):
            setattr(a, k, v)
        ok, ders, cells = 0, [], []
        for n in names:
            k, (lo, hi), d, _ = evaluate(n, data[n], a)
            good = lo is None or lo <= k <= hi
            ok += good
            if d is not None and n.startswith(("piper", "dialog")):
                ders.append(d)  # средний DER — только по диалогам с эталоном
            cells.append(f"{n}:{k}{'' if good else '!'}" + (f"/{d:.1f}" if d is not None else ""))
            if o.verbose:
                want = "?" if lo is None else (str(lo) if lo == hi else f"{lo}-{hi}")
                print(f"  {n:14s} найдено {k}  нужно {want:4s} " + (f"DER {d:6.2f}" if d is not None else ""))
        tag = " ".join(f"{k}={v:g}" for k, v in zip(keys, combo)) or "default"
        md = np.mean(ders) if ders else float("nan")
        print(f"{tag:40s} ok {ok}/{len(names)}  DERср {md:5.2f}  | " + " ".join(cells), flush=True)


if __name__ == "__main__":
    main()
