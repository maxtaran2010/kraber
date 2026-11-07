package app.glagol.ml

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Диаризация по кускам речи:
 *  1. внутри длинных кусков VAD ищем смену спикера — эмбеддинги скользящим окном, разрез в провалах близости;
 *  2. добавляем подсказки из текста (тире GigaAM в начале реплики);
 *  3. для каждого куска — голосовой эмбеддинг (CAM++), агломеративная кластеризация (average linkage, косинус),
 *     уточнение по центроидам;
 *  4. режим «Авто»: мелкие кластеры присоединяются к ближайшим — только если похожи на соседа или совсем крошечные,
 *     затем кластеры с очень близкими центроидами сливаются (один голос, раздробленный AHC);
 *     число задано: AHC до этого числа, уточнение не схлопывает кластеры.
 * Ни один кусок речи не теряется: спикер есть у каждого слова.
 * Python-зеркало для подбора параметров: tools/eval/proto_pipeline.py (cluster_embeddings), перебор — cluster_sweep.py.
 */
class SpeakerClustering(private val embedder: SpeakerEmbedder) {

    data class Params(
        /** Максимальная длина куска для эмбеддинга, с. */
        val chunkMax: Float = 3.0f,
        /** Не режем ближе этого к краю куска, с. */
        val minPiece: Float = 0.3f,
        /** Кусок короче — эмбеддинг ненадёжен, спикера берём у соседей. */
        val minEmb: Float = 0.5f,
        /** Порог косинусной близости для объединения кластеров (режим «Авто»). */
        val ahcThreshold: Float = 0.55f,
        /** Поиск смены спикера: окно, шаг и порог близости соседних окон. */
        val scdWindow: Float = 1.5f,
        val scdHop: Float = 0.25f,
        val scdThreshold: Float = 0.3f,
        /** Кластер меньше max(minSpeakerSec, minSpeakerFrac·речь) — «подозрительный»: присоединяем, если похож на соседа. */
        val minSpeakerSec: Float = 3f,
        val minSpeakerFrac: Float = 0.04f,
        /** Кластер меньше min(minSpeakerSec, tinySpeakerFrac·речь) — шум, присоединяем всегда
         *  (в короткой записи порог — доля речи, иначе фраза в 1 с не выжила бы). */
        val tinySpeakerFrac: Float = 0.2f,
        /** Маленький кластер с косинусом центроида к ближайшему соседу ниже порога — отдельный голос, оставляем. */
        val smallMergeThreshold: Float = 0.4f,
        /** Кластеры с косинусом центроидов не ниже порога — один голос, раздробленный AHC, сливаем. */
        val mergeThreshold: Float = 0.9f,
    )

    data class Chunk(val start: Float, val end: Float) {
        val duration: Float get() = end - start
    }

    fun run(
        samples: FloatArray,
        sampleRate: Int,
        speech: List<Chunk>,
        cuts: List<Float>,
        /** Необязательные внешние отрезки с метками спикеров — подсказка для слишком коротких кусков. */
        hints: List<SpeakerTurn>,
        numSpeakers: Int,
        p: Params = Params(),
        onProgress: (Float) -> Unit = {},
    ): List<SpeakerTurn> {
        // Один спикер задан вручную: делить нечего — ни смены спикера, ни эмбеддингов не считаем
        if (numSpeakers == 1) return speech.map { SpeakerTurn(it.start, it.end, 0) }
        val scd = scdCuts(samples, sampleRate, speech, p) { onProgress(SCD_SHARE * it) }
        val chunks = makeChunks(speech, (cuts + scd).sorted(), p)
        if (chunks.isEmpty()) return emptyList()
        if (numSpeakers > 0) splitForSpeakers(chunks, numSpeakers, p)

        val good = chunks.indices.filter { chunks[it].duration >= p.minEmb }
        if (good.isEmpty()) return chunks.map { SpeakerTurn(it.start, it.end, 0) }

        val emb = embedder.embedAll(samples, sampleRate, good.map { chunks[it].start to chunks[it].end }) {
            onProgress(SCD_SHARE + (1 - SCD_SHARE) * it)
        }.map { normalize(it) }
        val dur = FloatArray(good.size) { chunks[good[it]].duration }

        var lab = ahc(emb, p.ahcThreshold, numSpeakers.takeIf { it > 0 })
        if (numSpeakers > 0) {
            // Число задано: уточнение не должно схлопнуть кластеры ниже того, что дала AHC
            val k0 = count(lab)
            for (i in 0 until 2) {
                val r = refine(emb, dur, lab)
                if (count(r) < k0) break
                lab = r
            }
        } else {
            // Сначала убираем мелочь (AHC на живой речи даёт сотни крошечных кластеров), потом сливаем
            // раздробленные голоса уже среди крупных кластеров и ещё раз уточняем по центроидам
            repeat(2) { lab = refine(emb, dur, lab) }
            lab = mergeSmall(emb, dur, lab, p)
            lab = mergeClose(emb, dur, lab, p.mergeThreshold)
            repeat(2) { lab = refine(emb, dur, lab) }
        }

        val labels = IntArray(chunks.size) { -1 }
        good.forEachIndexed { n, i -> labels[i] = lab[n] }

        // Внешняя метка → наш кластер (голосование по перекрытию)
        val votes = HashMap<Int, HashMap<Int, Float>>()
        for (i in good) for (h in hints) {
            val ov = overlap(chunks[i], h)
            if (ov > 0f) votes.getOrPut(h.speaker) { HashMap() }.merge(labels[i], ov, Float::plus)
        }
        val pmap = votes.mapValues { (_, v) -> v.maxBy { it.value }.key }
        // Короткие куски — по внешней метке, если она уверенно покрывает кусок
        for (i in chunks.indices) if (labels[i] < 0) {
            var best = -1
            var bestOv = 0f
            for (h in hints) {
                val m = pmap[h.speaker] ?: continue
                val ov = overlap(chunks[i], h)
                if (ov > bestOv) { bestOv = ov; best = m }
            }
            if (best >= 0 && bestOv >= 0.5f * chunks[i].duration) labels[i] = best
        }
        // Остальные — по ближайшему во времени надёжному соседу
        for (i in chunks.indices) if (labels[i] < 0) {
            val mid = (chunks[i].start + chunks[i].end) / 2
            val near = good.minBy { j -> kotlin.math.abs((chunks[j].start + chunks[j].end) / 2 - mid) }
            labels[i] = labels[near]
        }
        return chunks.mapIndexed { i, c -> SpeakerTurn(c.start, c.end, labels[i]) }
    }

    /** Точки смены спикера внутри длинных кусков речи. */
    private fun scdCuts(samples: FloatArray, sr: Int, speech: List<Chunk>, p: Params, onProgress: (Float) -> Unit): List<Float> {
        val w = p.scdWindow
        val h = p.scdHop
        val k = kotlin.math.round(w / h).toInt().coerceAtLeast(1)
        // Все окна всех длинных кусков — одним пакетом (считаются параллельно)
        val windows = ArrayList<Pair<Float, Float>>()
        val owner = ArrayList<Int>()
        speech.forEachIndexed { si, seg ->
            if (seg.duration < 2 * w) return@forEachIndexed
            var t = seg.start
            while (t + w <= seg.end + 1e-4f) {
                windows += t to t + w
                owner += si
                t += h
            }
        }
        val embs = embedder.embedAll(samples, sr, windows, onProgress).map { normalize(it) }
        val cuts = ArrayList<Float>()
        var i0 = 0
        while (i0 < windows.size) {
            var i1 = i0
            while (i1 < windows.size && owner[i1] == owner[i0]) i1++
            // Близость окна i и следующего непересекающегося окна i+k; граница между ними — в конце окна i
            val n = i1 - i0
            val sims = FloatArray((n - k).coerceAtLeast(0)) { dot(embs[i0 + it], embs[i0 + it + k]) }
            for (i in 1 until sims.size - 1) {
                if (sims[i] < p.scdThreshold && sims[i] <= sims[i - 1] && sims[i] <= sims[i + 1]) cuts += windows[i0 + i].second
            }
            i0 = i1
        }
        return cuts
    }

    private fun overlap(c: Chunk, t: SpeakerTurn) = minOf(c.end, t.end) - maxOf(c.start, t.start)

    /**
     * Число спикеров задано, а кусков с эмбеддингом меньше (вся речь — одна короткая фраза):
     * делим самый длинный кусок пополам, пока половинки не короче minEmb. Иначе «2 спикера» дали бы одного.
     */
    private fun splitForSpeakers(chunks: MutableList<Chunk>, n: Int, p: Params) {
        while (chunks.count { it.duration >= p.minEmb } < n) {
            val i = chunks.indices.maxBy { chunks[it].duration }
            val c = chunks[i]
            if (c.duration < 2 * p.minEmb) return
            val mid = (c.start + c.end) / 2
            chunks[i] = Chunk(c.start, mid)
            chunks.add(i + 1, Chunk(mid, c.end))
        }
    }

    private fun makeChunks(speech: List<Chunk>, cuts: List<Float>, p: Params): MutableList<Chunk> {
        val out = ArrayList<Chunk>()
        for (seg in speech) {
            val pts = ArrayList<Float>()
            pts += seg.start
            cuts.filterTo(pts) { it > seg.start + p.minPiece && it < seg.end - p.minPiece }
            pts += seg.end
            for (k in 0 until pts.size - 1) {
                val a = pts[k]
                val b = pts[k + 1]
                val n = ceil((b - a) / p.chunkMax).toInt().coerceAtLeast(1)
                val step = (b - a) / n
                for (i in 0 until n) out += Chunk(a + i * step, a + (i + 1) * step)
            }
        }
        return out
    }

    /** Агломеративная кластеризация (average linkage) с кэшем лучшего соседа в каждой строке. */
    private fun ahc(e: List<FloatArray>, threshold: Float, n: Int?): IntArray {
        val k = e.size
        if (k == 1) return IntArray(1)
        val c = Array(k) { i -> FloatArray(k) { j -> if (i == j) NEG else dot(e[i], e[j]) } }
        val size = IntArray(k) { 1 }
        val active = BooleanArray(k) { true }
        val parent = IntArray(k) { it }
        val best = FloatArray(k)
        val arg = IntArray(k)
        fun recompute(i: Int) {
            var b = NEG
            var a = -1
            val row = c[i]
            for (j in 0 until k) if (active[j] && j != i && row[j] > b) { b = row[j]; a = j }
            best[i] = b; arg[i] = a
        }
        for (i in 0 until k) recompute(i)
        var clusters = k
        while (clusters > 1) {
            var i = -1
            var b = NEG
            for (m in 0 until k) if (active[m] && best[m] > b) { b = best[m]; i = m }
            if (i < 0) break
            if (n != null) { if (clusters <= n) break } else if (b < threshold) break
            val j = arg[i]
            val si = size[i].toFloat()
            val sj = size[j].toFloat()
            for (m in 0 until k) if (active[m] && m != i && m != j) {
                val v = (c[i][m] * si + c[j][m] * sj) / (si + sj)
                c[i][m] = v; c[m][i] = v
            }
            active[j] = false
            size[i] += size[j]
            parent[j] = i
            clusters--
            recompute(i)
            for (m in 0 until k) if (active[m] && m != i) {
                if (arg[m] == i || arg[m] == j) recompute(m)
                else if (c[m][i] > best[m]) { best[m] = c[m][i]; arg[m] = i }
            }
        }
        fun root(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        return compact(IntArray(k) { root(it) })
    }

    /** Пересчёт центроидов (с весом по длительности) и переназначение каждого куска к ближайшему. */
    private fun refine(e: List<FloatArray>, dur: FloatArray, lab: IntArray): IntArray {
        val cent = centroids(e, dur, lab)
        return compact(IntArray(e.size) { i -> cent.indices.maxBy { dot(e[i], cent[it]) } })
    }

    /** Против дробления одного голоса: пока есть пара кластеров с косинусом центроидов ≥ порога — сливаем самую близкую. */
    private fun mergeClose(e: List<FloatArray>, dur: FloatArray, lab0: IntArray, threshold: Float): IntArray {
        var lab = lab0
        while (true) {
            val cent = centroids(e, dur, lab)
            if (cent.size <= 1) return lab
            var b = NEG
            var bi = -1
            var bj = -1
            for (i in cent.indices) for (j in i + 1 until cent.size) {
                val s = dot(cent[i], cent[j])
                if (s > b) { b = s; bi = i; bj = j }
            }
            if (b < threshold) return lab
            lab = compact(IntArray(lab.size) { if (lab[it] == bj) bi else lab[it] })
        }
    }

    /**
     * Маленькие кластеры (меньше max(minSpeakerSec, minSpeakerFrac·речь)) — от меньшего к большему:
     * похожий на соседа (косинус центроидов ≥ smallMergeThreshold) или крошечный — раздаём его куски ближайшим,
     * непохожий — оставляем: это отдельный голос, даже если он сказал одну фразу.
     */
    private fun mergeSmall(e: List<FloatArray>, dur: FloatArray, lab0: IntArray, p: Params): IntArray {
        val lab = lab0.copyOf()
        val total = dur.sum()
        val soft = maxOf(p.minSpeakerSec, p.minSpeakerFrac * total)
        val hard = minOf(p.minSpeakerSec, p.tinySpeakerFrac * total)
        val kept = HashSet<Int>()
        while (true) {
            val kk = (lab.maxOrNull() ?: 0) + 1
            val tot = FloatArray(kk)
            lab.forEachIndexed { i, l -> tot[l] += dur[i] }
            val alive = (0 until kk).filter { tot[it] > 0f }
            if (alive.size <= 1) break
            val small = alive.filter { it !in kept && tot[it] < soft }.minByOrNull { tot[it] } ?: break
            val cent = centroids(e, dur, lab)
            val others = alive.filter { it != small }
            val near = others.maxOf { dot(cent[small], cent[it]) }
            if (tot[small] >= hard && near < p.smallMergeThreshold) {
                kept += small
                continue
            }
            for (i in lab.indices) if (lab[i] == small) lab[i] = others.maxBy { dot(e[i], cent[it]) }
        }
        return compact(lab)
    }

    private fun count(lab: IntArray) = lab.toSet().size

    private fun centroids(e: List<FloatArray>, dur: FloatArray, lab: IntArray): List<FloatArray> {
        val kk = (lab.maxOrNull() ?: 0) + 1
        val dim = e[0].size
        val cent = List(kk) { FloatArray(dim) }
        e.forEachIndexed { i, v -> val c = cent[lab[i]]; for (d in 0 until dim) c[d] += v[d] * dur[i] }
        return cent.map { normalize(it) }
    }

    private fun compact(lab: IntArray): IntArray {
        val map = HashMap<Int, Int>()
        return IntArray(lab.size) { map.getOrPut(lab[it]) { map.size } }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        val inv = 1f / (sqrt(n) + 1e-9f)
        return FloatArray(v.size) { v[it] * inv }
    }

    private companion object {
        const val NEG = -1e9f
        /** Доля прогресса этапа на поиск смены спикера. */
        const val SCD_SHARE = 0.7f
    }
}
