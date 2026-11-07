"""
Прототип пайплайна v2 (зеркало Kotlin-реализации), чтобы быстро подбирать параметры на Mac.

  VAD (silero) → ASR каждого куска речи (GigaAM v3, таймстемпы токенов → слова)
  диаризация всего файла (pyannote + эмбеддинги) → спикер каждого слова → сглаживание → реплики

Запуск:  .cache/venv/bin/python tools/eval/proto_pipeline.py in.wav out.json [--speakers N] [--thr 0.5]
"""
import argparse, json, sys, time
import numpy as np, soundfile as sf, sherpa_onnx

M = "src/android/app/src/main/assets/models"
SR = 16000
PUNCT_END = (".", "?", "!", "…")


def load(path):
    x, sr = sf.read(path, dtype="float32", always_2d=True)
    x = x.mean(axis=1)
    if sr != SR:
        import subprocess, tempfile, os
        tmp = tempfile.mktemp(suffix=".wav")
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", path, tmp], check=True)
        x, _ = sf.read(tmp, dtype="float32"); os.remove(tmp)
    return normalize(x)


def normalize(x):
    import os
    mode = os.environ.get("NORM", "peak")
    if mode == "level":
        return normalize_level(x)
    if mode == "agc":
        return normalize_agc(x)
    x = x - x.mean()
    peak = np.abs(x).max() + 1e-9
    rms = np.sqrt((x ** 2).mean()) + 1e-9
    gain = min(0.9 / peak, 0.2 / rms)
    gain = min(max(gain, 1.0), 10.0)
    return (x * gain).astype(np.float32)


def frame_rms(x, fl=320):
    n = len(x) // fl
    return np.sqrt(np.mean(x[: n * fl].reshape(n, fl) ** 2, axis=1) + 1e-12)


def soft_limit(y, knee=0.5):
    a = np.abs(y)
    over = a > knee
    y = y.copy()
    y[over] = np.sign(y[over]) * (knee + (1 - knee) * np.tanh((a[over] - knee) / (1 - knee)))
    return y


def normalize_level(x, target=0.1, gmax=float(__import__("os").environ.get("GMAX", "30"))):
    """Уровень речи (95-й перцентиль RMS 20-мс кадров) → −20 dBFS; пики (стуки) — мягким лимитером."""
    x = x - x.mean()
    r = frame_rms(x)
    if len(r) == 0:
        return x.astype(np.float32)
    lvl = float(np.percentile(r, 95))
    g = min(max(target / (lvl + 1e-9), 1.0), gmax)
    return soft_limit(x * g).astype(np.float32)


def normalize_agc(x, target=0.1, gmax=30.0, extra_db=12.0):
    """Медленная АРУ: общий уровень как в normalize_level, плюс до +extra_db тихим местам (далёкий голос)."""
    x = x - x.mean()
    fl = 320
    r = frame_rms(x, fl)
    if len(r) == 0:
        return x.astype(np.float32)
    lvl = float(np.percentile(r, 95))
    g0 = min(max(target / (lvl + 1e-9), 1.0), gmax)
    # огибающая: максимум за ±0.4 с, затем среднее за ±0.5 с
    w = 20
    pad = np.pad(r, w, mode="edge")
    env = np.lib.stride_tricks.sliding_window_view(pad, 2 * w + 1).max(axis=1)
    k = np.ones(51) / 51
    env = np.convolve(np.pad(env, 25, mode="edge"), k, mode="valid")
    floor = lvl * 10 ** (-extra_db / 20)
    gf = np.clip(target / np.maximum(env, floor), g0, g0 * 10 ** (extra_db / 20))
    gs = np.repeat(gf, fl)
    gs = np.concatenate([gs, np.full(len(x) - len(gs), gf[-1])])
    return soft_limit(x * gs).astype(np.float32)


# ---------------------------------------------------------------- VAD
def vad_segments(x, a):
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = f"{M}/vad/silero_vad.onnx"
    cfg.silero_vad.threshold = a.vad_thr
    cfg.silero_vad.min_silence_duration = a.vad_min_sil
    cfg.silero_vad.min_speech_duration = 0.15
    cfg.silero_vad.max_speech_duration = a.vad_max
    cfg.silero_vad.window_size = 512
    cfg.sample_rate = SR
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=max(60, len(x) / SR + 10))
    out = []

    def drain():
        while not vad.empty():
            out.append((vad.front.start, len(vad.front.samples)))
            vad.pop()

    for i in range(0, len(x), 512):
        vad.accept_waveform(x[i:i + 512])
        drain()
    vad.flush()
    drain()
    return out  # [(start_sample, n_samples)]


# ---------------------------------------------------------------- ASR
_rec = None


def recognizer():
    global _rec
    if _rec is None:
        _rec = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=f"{M}/asr/encoder.onnx", decoder=f"{M}/asr/decoder.onnx", joiner=f"{M}/asr/joiner.onnx",
            tokens=f"{M}/asr/tokens.txt", num_threads=4, sample_rate=SR, feature_dim=64,
            decoding_method="greedy_search", model_type="nemo_transducer")
    return _rec


def words_from_tokens(tokens, times, seg_start, seg_end):
    """Токены GigaAM: ' xxx' — начало слова; знаки препинания приклеиваем к предыдущему слову."""
    words = []  # [text, start, last_token_time, turn_start]
    new_word = True
    dash = False
    for tok, t in zip(tokens, times):
        t = seg_start + t
        if tok.startswith(" "):
            body = tok[1:]
            new_word = True
            if not body:
                continue
            tok = body
        if tok.strip() in ("—", "–", "-") or (tok and set(tok) <= set("—–")):
            # Тире в выводе GigaAM — признак смены реплики в диалоге
            dash = True
            new_word = True
            continue
        if tok and all(c in ".,?!…:;" for c in tok) and words:
            words[-1][0] += tok
            continue
        if new_word or not words:
            words.append([tok, t, t, dash])
            new_word = False
            dash = False
        else:
            words[-1][0] += tok
            words[-1][2] = t
    out = []
    for i, (txt, st, last, ts) in enumerate(words):
        nxt = words[i + 1][1] if i + 1 < len(words) else seg_end
        end = min(nxt, last + 0.35) if i + 1 < len(words) else min(seg_end, last + 0.5)
        out.append({"text": txt, "start": st, "end": max(end, st + 0.08), "turn": ts or i == 0})
    return out


def asr_words(x, segs, pad=0.1):
    rec = recognizer()
    words = []
    p = int(pad * SR)
    for st, n in segs:
        a, b = max(0, st - p), min(len(x), st + n + p)
        s = rec.create_stream()
        s.accept_waveform(SR, x[a:b])
        rec.decode_stream(s)
        r = s.result
        if not r.text.strip():
            continue
        words += words_from_tokens(r.tokens, r.timestamps, a / SR, b / SR)
    return words


# ---------------------------------------------------------------- diarization
def diarize(x, a):
    cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(model=".cache/models/segmentation.onnx"),
            num_threads=4),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=a.emb or f"{M}/diarization/embedding.onnx", num_threads=4),
        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=a.speakers if a.speakers > 0 else -1, threshold=a.thr),
        min_duration_on=0.2, min_duration_off=0.4)
    sd = sherpa_onnx.OfflineSpeakerDiarization(cfg)
    res = sd.process(x).sort_by_start_time()
    return [(r.start, r.end, r.speaker) for r in res]


# ---------------------------------------------------------------- гибридная диаризация
_ext = {}


def extractor(path):
    if path not in _ext:
        _ext[path] = sherpa_onnx.SpeakerEmbeddingExtractor(
            sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=path, num_threads=4))
    return _ext[path]


def embed(x, s, e, path):
    ex = extractor(path)
    st = ex.create_stream()
    st.accept_waveform(SR, x[int(s * SR):int(e * SR)])
    st.input_finished()
    v = np.array(ex.compute(st), dtype=np.float32)
    return v / (np.linalg.norm(v) + 1e-9)


def make_chunks(vad_segs, turns, a):
    """Куски речи VAD, разрезанные по границам pyannote и не длиннее chunk_max."""
    cuts = sorted({t[0] for t in turns} | {t[1] for t in turns})
    chunks = []
    for st, n in vad_segs:
        s, e = st / SR, (st + n) / SR
        pts = [s] + [c for c in cuts if s + a.min_piece < c < e - a.min_piece] + [e]
        for p0, p1 in zip(pts, pts[1:]):
            k = max(1, int(np.ceil((p1 - p0) / a.chunk_max)))
            step = (p1 - p0) / k
            for i in range(k):
                chunks.append([p0 + i * step, p0 + (i + 1) * step])
    return chunks


def split_for_speakers(chunks, n, a):
    """Число спикеров задано, а кусков с эмбеддингом меньше (вся речь — одна короткая фраза):
    делим самый длинный кусок пополам, пока половинки не короче min_emb."""
    while sum(e - s >= a.min_emb for s, e in chunks) < n:
        i = max(range(len(chunks)), key=lambda k: chunks[k][1] - chunks[k][0])
        s, e = chunks[i]
        if e - s < 2 * a.min_emb:
            return
        chunks[i:i + 1] = [[s, (s + e) / 2], [(s + e) / 2, e]]


def ahc(E, thr, n=None):
    """Агломеративная кластеризация, average linkage по косинусной близости."""
    k = len(E)
    if k == 1:
        return np.zeros(1, dtype=int)
    C = E @ E.T
    np.fill_diagonal(C, -np.inf)
    sizes = np.ones(k)
    label = np.arange(k)
    active = np.ones(k, dtype=bool)
    while active.sum() > 1:
        i, j = np.unravel_index(np.argmax(C), C.shape)
        best = C[i, j]
        if n is not None:
            if active.sum() <= n:
                break
        elif best < thr:
            break
        C[i, :] = (C[i, :] * sizes[i] + C[j, :] * sizes[j]) / (sizes[i] + sizes[j])
        C[:, i] = C[i, :]
        C[i, i] = -np.inf
        C[j, :] = -np.inf
        C[:, j] = -np.inf
        sizes[i] += sizes[j]
        active[j] = False
        label[label == j] = i
    _, lab = np.unique(label, return_inverse=True)
    return lab


def compact(lab):
    """Перенумерация меток 0..K-1 в порядке первого появления (как compact() в Kotlin)."""
    m = {}
    return np.array([m.setdefault(int(l), len(m)) for l in lab], dtype=int)


def centroids(G, d, lab):
    K = lab.max() + 1
    cent = np.stack([(G[lab == c] * d[lab == c, None]).sum(0) for c in range(K)])
    return cent / (np.linalg.norm(cent, axis=1, keepdims=True) + 1e-9)


def refine(G, d, lab):
    """Центроиды (с весом по длительности) → каждый кусок к ближайшему."""
    return compact(np.argmax(G @ centroids(G, d, lab).T, axis=1))


def merge_small(G, d, lab, a):
    """Маленький кластер присоединяем к ближайшим, только если он похож на соседа или совсем крошечный.
    Непохожий голос остаётся отдельным спикером даже в 4-секундной записи."""
    total = d.sum()
    soft = max(a.min_spk_sec, a.min_spk_frac * total)   # меньше — «подозрительный» кластер
    hard = min(a.min_spk_sec, a.tiny_frac * total)      # меньше — точно шум (для коротких записей — доля)
    lab = lab.copy()
    kept = set()  # маленькие, но непохожие на остальных — отдельный голос
    while True:
        K = lab.max() + 1
        tot = np.array([d[lab == c].sum() for c in range(K)])
        alive = [c for c in range(K) if tot[c] > 0]
        if len(alive) <= 1:
            break
        cand = [c for c in alive if c not in kept and tot[c] < soft]
        if not cand:
            break
        s = min(cand, key=lambda c: tot[c])
        cent = centroids(G, d, lab)
        others = [c for c in alive if c != s]
        near = max(float(cent[s] @ cent[c]) for c in others)
        if tot[s] >= hard and near < a.small_thr:
            kept.add(s)
            continue
        # куски маленького кластера — каждый к ближайшему из остальных центроидов
        sims = G[lab == s] @ cent[others].T
        lab[lab == s] = np.array(others)[np.argmax(sims, axis=1)]
    return compact(lab)


def merge_thr_for(sec, a):
    """Порог слияния пары кластеров по речи меньшего из них: центроид по 10–20 с речи шумный (эхо, расстояние,
    громкость), один голос легко дробится — порог merge_lo; по минутам речи центроид надёжен — merge_thr."""
    lo, hi = a.merge_lo_sec, a.merge_hi_sec
    if a.merge_lo >= a.merge_thr or sec >= hi:
        return a.merge_thr
    if sec <= lo:
        return a.merge_lo
    t = (sec - lo) / (hi - lo)
    return a.merge_lo + t * (a.merge_thr - a.merge_lo)


def merge_close(G, d, lab, a):
    """Против дробления одного голоса: пока есть пара кластеров с косинусом центроидов не ниже порога
    (зависит от объёма речи меньшего кластера) — сливаем пару с наибольшим запасом над порогом."""
    while lab.max() + 1 > 1:
        cent = centroids(G, d, lab)
        K = lab.max() + 1
        tot = np.array([d[lab == c].sum() for c in range(K)])
        S = cent @ cent.T
        T = np.array([[merge_thr_for(min(tot[i], tot[j]), a) for j in range(K)] for i in range(K)])
        M = S - T
        np.fill_diagonal(M, -np.inf)
        i, j = np.unravel_index(np.argmax(M), M.shape)
        if M[i, j] < 0:
            break
        lab = lab.copy()
        lab[lab == max(i, j)] = min(i, j)
        lab = compact(lab)
    return lab


def cluster_embeddings(G, d, a):
    """Метки кластеров для эмбеддингов G (нормированы) с длительностями d. Зеркало SpeakerClustering.kt."""
    n = a.speakers if a.speakers > 0 else None
    lab = ahc(G, a.ahc_thr, n)
    if n is not None:
        # число задано: уточнение не должно схлопнуть кластеры ниже того, что дала AHC
        k0 = lab.max() + 1
        for _ in range(2):
            new = refine(G, d, lab)
            if new.max() + 1 < k0:
                break
            lab = new
        return lab
    # сначала убираем мелочь (AHC на живой речи даёт сотни крошечных кластеров), потом сливаем
    # раздробленные голоса уже среди крупных кластеров и ещё раз уточняем по центроидам
    for _ in range(2):
        lab = refine(G, d, lab)
    lab = merge_small(G, d, lab, a)
    lab = merge_close(G, d, lab, a)
    for _ in range(2):
        lab = refine(G, d, lab)
    return lab


def cluster_chunks(x, chunks, a, pturns=()):
    path = a.emb or f"{M}/diarization/embedding.onnx"
    dur = np.array([e - s for s, e in chunks])
    good = dur >= a.min_emb
    if good.sum() == 0:
        return np.zeros(len(chunks), dtype=int)
    idx = [i for i in range(len(chunks)) if good[i]]
    G = np.stack([embed(x, chunks[i][0], chunks[i][1], path) for i in idx])
    lab = cluster_embeddings(G, dur[idx], a)
    out = np.full(len(chunks), -1)
    out[idx] = lab
    # соответствие меток pyannote → наши кластеры (по перекрытию)
    vote = {}
    for i in idx:
        s0, e0 = chunks[i]
        for ps, pe, pk in pturns:
            ov = min(pe, e0) - max(ps, s0)
            if ov > 0:
                vote.setdefault(pk, {}).setdefault(out[i], 0.0)
                vote[pk][out[i]] += ov
    pmap = {pk: max(v, key=v.get) for pk, v in vote.items()}
    for i in range(len(chunks)):
        if out[i] < 0 and a.pmap:
            s0, e0 = chunks[i]
            best, bov = None, 0.0
            for ps, pe, pk in pturns:
                ov = min(pe, e0) - max(ps, s0)
                if ov > bov and pk in pmap:
                    best, bov = pmap[pk], ov
            if best is not None and bov >= 0.5 * (e0 - s0):
                out[i] = best
    # остальные короткие куски — по соседям во времени
    for i in range(len(chunks)):
        if out[i] < 0:
            near = min(idx, key=lambda j: abs((chunks[j][0] + chunks[j][1]) / 2 - (chunks[i][0] + chunks[i][1]) / 2))
            out[i] = out[near]
    return out


def scd_cuts(x, vad_segs, a):
    """Смена спикера без pyannote: эмбеддинги скользящим окном, разрез в провалах близости соседей."""
    path = a.emb or f"{M}/diarization/embedding.onnx"
    cuts = []
    W, H = a.scd_win, a.scd_hop
    for st, n in vad_segs:
        s, e = st / SR, (st + n) / SR
        if e - s < 2 * W:
            continue
        centers, embs = [], []
        t = s
        while t + W <= e + 1e-6:
            embs.append(embed(x, t, t + W, path)); centers.append(t + W / 2); t += H
        k = int(round(W / H))  # соседнее непересекающееся окно
        sims = [float(embs[i] @ embs[i + k]) for i in range(len(embs) - k)]
        # граница между окнами i и i+k — в точке centers[i] + W/2
        for i in range(1, len(sims) - 1):
            if sims[i] < a.scd_thr and sims[i] <= sims[i - 1] and sims[i] <= sims[i + 1]:
                cuts.append(centers[i] + W / 2)
    return [(c, c, -1) for c in cuts]


def hybrid_turns(x, vad_segs, a, words=()):
    turns = diarize(x, a) if a.cuts else []
    if a.scd:
        turns_cut = scd_cuts(x, vad_segs, a)
    else:
        turns_cut = []
    cut_turns = list(turns) + turns_cut
    if a.dash_cuts:
        cut_turns += [(w["start"] - 0.02, w["start"] - 0.02, -1) for w in words if w.get("turn")]
    chunks = make_chunks(vad_segs, cut_turns, a)
    if not chunks:
        return []
    if a.speakers > 0:
        split_for_speakers(chunks, a.speakers, a)
    lab = cluster_chunks(x, chunks, a, turns)
    return [(s, e, int(l)) for (s, e), l in zip(chunks, lab)]


def assign_speakers(words, turns):
    for w in words:
        best, best_ov = None, 0.0
        for s, e, spk in turns:
            ov = min(e, w["end"]) - max(s, w["start"])
            if ov > best_ov:
                best, best_ov = spk, ov
        if best is None and turns:
            mid = (w["start"] + w["end"]) / 2
            best = min(turns, key=lambda t: 0 if t[0] <= mid <= t[1] else min(abs(t[0] - mid), abs(t[1] - mid)))[2]
        w["spk"] = best if best is not None else 0


def runs_of(words):
    runs, i = [], 0
    while i < len(words):
        j = i
        while j + 1 < len(words) and words[j + 1]["spk"] == words[i]["spk"]:
            j += 1
        runs.append((i, j))
        i = j + 1
    return runs


def smooth(words, a):
    """Убираем «дребезг» диаризации внутри непрерывной речи и двигаем смену спикера к концу предложения."""
    changed = True
    while changed:
        changed = False
        runs = runs_of(words)
        for k in range(1, len(runs) - 1):
            i, j = runs[k]
            pi, pj = runs[k - 1]
            ni, nj = runs[k + 1]
            if words[pi]["spk"] != words[ni]["spk"]:
                continue
            dur = words[j]["end"] - words[i]["start"]
            gap_before = words[i]["start"] - words[pj]["end"]
            gap_after = words[ni]["start"] - words[j]["end"]
            whole = (words[i].get("turn") or words[pj]["text"].endswith(PUNCT_END)) and words[j]["text"].endswith(PUNCT_END)
            if a.keep_sentences and whole:
                continue
            if (j - i + 1 <= a.jitter_words or dur < a.jitter_sec) and gap_before < a.jitter_gap and gap_after < a.jitter_gap:
                for t in range(i, j + 1):
                    words[t]["spk"] = words[pi]["spk"]
                changed = True
                break
    # Граница смены спикера → к ближайшему концу предложения (±2 слова)
    if a.snap:
        for b in range(1, len(words)):
            if words[b]["spk"] == words[b - 1]["spk"]:
                continue
            if words[b - 1]["text"].endswith(PUNCT_END) or words[b].get("turn"):
                continue
            cands = []
            for c in range(max(1, b - a.snap), min(len(words), b + a.snap + 1)):
                if words[c].get("turn"):
                    cands.append((abs(c - b) - 0.5, c))
                elif words[c - 1]["text"].endswith(PUNCT_END):
                    cands.append((abs(c - b), c))
            if not cands:
                continue
            _, c = min(cands)
            if c < b:
                for t in range(c, b):
                    words[t]["spk"] = words[b]["spk"]
            else:
                for t in range(b, c):
                    words[t]["spk"] = words[b - 1]["spk"]


def to_utterances(words, a):
    utts = []
    for w in words:
        # длинная реплика одного спикера — новый абзац на ближайшем конце предложения (как maxUttSec в Kotlin)
        para = utts and utts[-1]["text"][-1:] in ".?!…" and w["end"] - utts[-1]["start"] > 20.0
        if utts and utts[-1]["spk"] == w["spk"] and w["start"] - utts[-1]["end"] < a.split_gap and not para:
            u = utts[-1]
            u["text"] += " " + w["text"]
            u["end"] = w["end"]
        else:
            utts.append({"spk": w["spk"], "start": w["start"], "end": w["end"], "text": w["text"]})
    order = {}
    for u in utts:
        order.setdefault(u["spk"], len(order))
    return [{
        "speaker_id": f"speaker_{order[u['spk']] + 1}",
        "start_ms": int(round(max(0, u["start"] - a.lead) * 1000)),
        "end_ms": int(round((u["end"] + a.tail) * 1000)),
        "text": u["text"],
    } for u in utts]


def run(path, a):
    x = load(path)
    t0 = time.time()
    segs = vad_segments(x, a)
    t1 = time.time()
    words = asr_words(x, segs)
    t2 = time.time()
    turns = hybrid_turns(x, segs, a, words) if a.mode == "hybrid" else diarize(x, a)
    t3 = time.time()
    assign_speakers(words, turns)
    smooth(words, a)
    utts = to_utterances(words, a)
    dur = len(x) / SR
    info = dict(dur=dur, vad=t1 - t0, asr=t2 - t1, diar=t3 - t2, rtf=(t3 - t0) / dur,
                vad_segs=len(segs), turns=len(turns), spk=len({t[2] for t in turns}))
    return utts, info


def args(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("inp"); p.add_argument("out", nargs="?")
    p.add_argument("--speakers", type=int, default=0)
    p.add_argument("--mode", default="hybrid", choices=["hybrid", "sherpa"])
    p.add_argument("--cuts", type=int, default=0, help="резать куски VAD по границам pyannote")
    p.add_argument("--dash-cuts", type=int, default=1)
    p.add_argument("--scd", type=int, default=1)
    p.add_argument("--scd-win", type=float, default=1.5)
    p.add_argument("--scd-hop", type=float, default=0.25)
    p.add_argument("--scd-thr", type=float, default=0.3)
    p.add_argument("--keep-sentences", type=int, default=1)
    p.add_argument("--pmap", type=int, default=1)
    p.add_argument("--chunk-max", type=float, default=3.0)
    p.add_argument("--min-piece", type=float, default=0.3)
    p.add_argument("--min-emb", type=float, default=0.5)
    p.add_argument("--ahc-thr", type=float, default=0.55)
    p.add_argument("--min-spk-sec", type=float, default=3.0)
    p.add_argument("--min-spk-frac", type=float, default=0.04)
    p.add_argument("--tiny-frac", type=float, default=0.2, help="доля речи: кластер меньше min(min-spk-sec, доля) — шум")
    p.add_argument("--small-thr", type=float, default=0.4, help="маленький кластер сливаем, если косинус к соседу не ниже")
    p.add_argument("--merge-thr", type=float, default=0.9, help="сливаем кластеры с косинусом центроидов не ниже")
    p.add_argument("--merge-lo", type=float, default=0.65, help="порог слияния для кластеров с малым объёмом речи")
    p.add_argument("--merge-lo-sec", type=float, default=20.0)
    p.add_argument("--merge-hi-sec", type=float, default=120.0)
    p.add_argument("--thr", type=float, default=0.5)
    p.add_argument("--emb", default=None)
    p.add_argument("--vad-thr", type=float, default=0.4)
    p.add_argument("--vad-min-sil", type=float, default=0.25)
    p.add_argument("--vad-max", type=float, default=20.0)
    p.add_argument("--jitter-words", type=int, default=2)
    p.add_argument("--jitter-sec", type=float, default=0.6)
    p.add_argument("--jitter-gap", type=float, default=0.3)
    p.add_argument("--snap", type=int, default=2)
    p.add_argument("--split-gap", type=float, default=1.0)
    p.add_argument("--lead", type=float, default=0.05)
    p.add_argument("--tail", type=float, default=0.0)
    return p.parse_args(argv)


if __name__ == "__main__":
    a = args()
    utts, info = run(a.inp, a)
    print(json.dumps(info, ensure_ascii=False))
    out = {"transcript": utts}
    if a.out:
        json.dump(out, open(a.out, "w"), ensure_ascii=False, indent=2)
    else:
        for u in utts:
            print(f"{u['speaker_id']} [{u['start_ms']/1000:6.2f}-{u['end_ms']/1000:6.2f}] {u['text']}")
