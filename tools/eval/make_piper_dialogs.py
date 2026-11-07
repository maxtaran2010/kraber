#!/usr/bin/env python3
"""
Многоголосый тестовый набор «диалоги котиков» (sherpa-onnx Piper TTS + macOS say).
В отличие от make_test_dialog.py здесь у каждого спикера свой настоящий голос, поэтому
набор годится для настройки диаризации.

Голоса (скачать в .cache/tts/<voice>/, каждый архив в свою папку):
  https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-{denis,dmitri,irina,ruslan}-medium.tar.bz2
  + macOS `say -v Milena` (если доступен).

Пишет в out-dir (по умолчанию .cache/test):
  piper_2spk / piper_3spk / piper_4spk / piper_fast / piper_noisy  (.wav 16 кГц mono + .ref.json)
  piper_noisy.m4a  (44.1 кГц stereo AAC, тот же ref)

  .cache/venv/bin/python -I tools/eval/make_piper_dialogs.py [--out-dir .cache/test] [--tts-dir .cache/tts]

Повторяемость: сид фиксирован для пауз/громкости/темпа; сами клипы Piper недетерминированы,
поэтому кэшируются в --clip-cache (.cache/tts/clips). Тот же кэш — тот же набор бит в бит.
"""
import argparse
import glob
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from math import gcd

import numpy as np
import soundfile as sf

SR = 16000
SEED = 20261106

# ---------------------------------------------------------------- диалоги
# Буквы ролей — в порядке первого появления; голоса задаются в VOICES_* ниже.
# Числа — словами: текст подаётся в TTS как есть и он же идёт в эталон.

DIALOG_2SPK = [
    ("A", "Добрый вечер, Мурка! Давай быстро обсудим, что у нас с запасами корма на эту неделю."),
    ("B", "Добрый вечер. Если коротко, то всё плохо."),
    ("A", "Насколько плохо?"),
    ("B", "Сухого корма осталось три пакета, влажного всего семь баночек. А ещё кто-то ночью открыл пакет с сосисками и съел почти всё."),
    ("A", "Это точно был не я!"),
    ("B", "Угу."),
    ("A", "Ну хорошо, может быть, одну сосиску я и взял. Но только одну, честное кошачье слово."),
    ("B", "Понятно. Тогда предлагаю так. С завтрашнего дня сосиски хранятся на верхней полке, корм выдаём два раза в день, утром и вечером, а за порядком на кухне следит Барсик, потому что он всё равно сидит там с шести утра и смотрит на холодильник."),
    ("A", "Согласен. А что с ветеринаром?"),
    ("B", "Запись на пятницу, на одиннадцать тридцать. Пойдёт Рыжик, у него опять чешется ухо."),
    ("B", "Хорошо. И последнее. В субботу у Пушка день рождения, ему исполняется пять лет."),
    ("A", "Ого! Нужен торт?"),
    ("B", "Нужен праздник. Торт из тунца, новые мячики и, пожалуйста, никаких сосисок."),
    ("A", "Договорились. Всем спасибо, расходимся."),
]

DIALOG_3SPK = [
    ("A", "Коллеги, открываю заседание кошачьего комитета. Сегодня у нас два вопроса: мыши в подвале и охрана дивана."),
    ("B", "Можно я начну с мышей?"),
    ("A", "Конечно, Мурка, пожалуйста."),
    ("B", "За последнюю неделю в подвале видели четырёх мышей. Одна из них очень наглая, она бегает прямо мимо миски и даже не оглядывается. Я дежурила там две ночи подряд, но поймать никого не удалось, потому что Васька всё время громко мурлыкал рядом."),
    ("C", "Я не мурлыкал, я дышал!"),
    ("B", "Ты дышал очень громко."),
    ("A", "Так, давайте без ссор. Васька, что ты предлагаешь?"),
    ("C", "Я предлагаю дежурить по очереди. Понедельник, среда и пятница мои, вторник и четверг Муркины, а в выходные пусть дежурит пёс. Он всё равно спит у двери и ничего не делает."),
    ("B", "Пёс не умеет ловить мышей."),
    ("C", "Зато он умеет лаять. Мыши испугаются и уйдут сами."),
    ("A", "Звучит разумно. Записываю: дежурство в подвале по графику, начинаем с понедельника."),
    ("B", "Угу."),
    ("A", "Теперь диван. Кто вчера оставил на нём столько шерсти?"),
    ("C", "Это была не шерсть, это было искусство."),
    ("B", "Хозяйка так не думает. Она полчаса чистила подушки и сказала, что купит нам отдельную лежанку."),
    ("A", "Отдельную лежанку? Это же прекрасно!"),
    ("C", "А диван тогда кто будет охранять?"),
    ("A", "Диван охраняем все вместе, но спим на нём только днём и только на пледе. Возражения есть?"),
    ("B", "Нет."),
    ("C", "Нет. Хотя плед колючий."),
    ("A", "Принято. Следующее заседание в среду в восемь вечера. Всем спасибо!"),
]

DIALOG_4SPK = [
    ("A", "Добрый день, друзья! Сегодня мы собрались, чтобы спланировать день рождения Пушка. Праздник в субботу, времени осталось мало."),
    ("B", "Добрый день. Сколько будет гостей?"),
    ("A", "Пока записались двенадцать котов и одна черепаха."),
    ("C", "Черепаха? А она успеет дойти до субботы?"),
    ("D", "Она вышла ещё в понедельник, так что должна успеть."),
    ("B", "Тогда я беру на себя меню. Предлагаю торт из тунца в три яруса, паштет из курицы, сливки для взрослых котов и отдельную миску с витаминами для черепахи. Ещё нужно купить сорок мячиков, потому что половину мы, как обычно, закатим под шкаф в первые же десять минут."),
    ("C", "Сорок мячиков это много."),
    ("B", "Это минимум."),
    ("D", "Согласна. Мячиков много не бывает."),
    ("A", "Хорошо, сорок так сорок. Кто отвечает за украшения?"),
    ("D", "Давайте я. Я развешу ленточки на шторах, поставлю коробки по всей гостиной, чтобы каждый гость мог в них посидеть, и сделаю большую надпись из клубков ниток. Главное, чтобы никто не начал их разматывать до начала праздника."),
    ("C", "Ничего не обещаю."),
    ("A", "Рыжик!"),
    ("C", "Ладно, ладно. Обещаю."),
    ("A", "Теперь важный вопрос. В пятницу у Пушка приём у ветеринара. Ему будут делать прививку."),
    ("B", "А он знает?"),
    ("A", "Нет. И пусть пока не знает."),
    ("C", "Я могу отвлечь его лазерной указкой, пока вы достаёте переноску."),
    ("D", "Отличный план. Только не свети ему в глаза, пожалуйста."),
    ("C", "Конечно. Я профессионал."),
    ("A", "Итак, подводим итоги. Мурка отвечает за меню и мячики. Соня занимается украшениями. Рыжик отвлекает Пушка в пятницу перед ветеринаром. А я договариваюсь с хозяйкой, чтобы в субботу нам никто не мешал. Вопросы есть?"),
    ("B", "Нет."),
    ("D", "Всё понятно."),
    ("C", "А торт мне оставят?"),
    ("A", "Оставят. Заседание окончено!"),
]

DIALOG_FAST = [
    ("A", "Так, кто съел мой паштет?"),
    ("B", "Не я."),
    ("C", "И не я."),
    ("A", "Он стоял на столе пять минут назад!"),
    ("B", "Угу."),
    ("C", "Может, это мыши?"),
    ("A", "Мыши не открывают банки!"),
    ("B", "Эти могут. Они очень умные."),
    ("A", "Серьёзно?"),
    ("B", "Да."),
    ("C", "Я видел одну с открывашкой."),
    ("A", "Ну конечно. А ещё она умеет водить машину?"),
    ("C", "Пока нет."),
    ("B", "Но учится."),
    ("A", "Ладно. Тогда так: сегодня ужин без паштета."),
    ("B", "Что?"),
    ("C", "Нет!"),
    ("A", "Да. Пока не найдём виновника."),
    ("B", "Хорошо, хорошо. Это был я. Но только половину."),
    ("A", "А вторую половину?"),
    ("C", "Вторую половину съел я."),
    ("A", "Понятно. Оба без паштета до пятницы."),
    ("B", "Справедливо."),
    ("C", "Мяу."),
]

# роль -> список голосов по предпочтению (первый доступный, не занятый в этом диалоге)
SETS = {
    "piper_2spk": dict(dialog=DIALOG_2SPK, gaps=(0.3, 0.9),
                       voices={"A": ["denis", "dmitri", "ruslan"], "B": ["irina", "milena"]}),
    "piper_3spk": dict(dialog=DIALOG_3SPK, gaps=(0.3, 0.9),
                       voices={"A": ["dmitri", "denis", "ruslan"], "B": ["irina", "milena"],
                               "C": ["ruslan", "denis", "dmitri", "milena"]}),
    "piper_4spk": dict(dialog=DIALOG_4SPK, gaps=(0.3, 0.9),
                       voices={"A": ["denis", "dmitri", "ruslan"], "B": ["irina", "milena"],
                               "C": ["ruslan", "dmitri", "denis"], "D": ["milena", "irina", "dmitri"]}),
    "piper_fast": dict(dialog=DIALOG_FAST, gaps=(0.05, 0.25),
                       voices={"A": ["irina", "milena"], "B": ["denis", "dmitri", "ruslan"],
                               "C": ["dmitri", "ruslan", "denis"]}),
}
PIPER_VOICES = ["denis", "dmitri", "irina", "ruslan"]


# ---------------------------------------------------------------- DSP
def resample(x: np.ndarray, sr_in: int, sr_out: int, half_taps: int = 64, beta: float = 8.6) -> np.ndarray:
    """Полифазный ресемплер с окном Кайзера (windowed sinc), рациональное отношение sr_out/sr_in."""
    if sr_in == sr_out:
        return x.astype(np.float32)
    g = gcd(sr_in, sr_out)
    up, down = sr_out // g, sr_in // g
    fc = 0.5 * min(1.0, sr_out / sr_in) * 0.92  # срез в циклах на входной отсчёт
    k = np.arange(-half_taps + 1, half_taps + 1)
    d = k[None, :] - (np.arange(up) / up)[:, None]  # [phase, tap]: расстояние до точки вывода
    win = np.i0(beta * np.sqrt(np.clip(1 - (d / half_taps) ** 2, 0, None))) / np.i0(beta)
    h = (2 * fc * np.sinc(2 * fc * d) * win).astype(np.float64)
    xp = np.concatenate([np.zeros(half_taps), x.astype(np.float64), np.zeros(half_taps + 1)])
    n_out = int(np.ceil(len(x) * up / down))
    out = np.empty(n_out)
    step = 16384
    for s in range(0, n_out, step):
        n = np.arange(s, min(n_out, s + step))
        pos = n * down
        base, ph = pos // up, pos % up
        idx = base[:, None] + k[None, :] + half_taps
        out[s:s + len(n)] = np.einsum("ij,ij->i", xp[idx], h[ph])
    return out.astype(np.float32)


def trim(x: np.ndarray, rel_db: float = -45.0, abs_floor: float = 2e-4, pad_ms: int = 15) -> np.ndarray:
    """Срезает тишину по краям по энергии 10-мс кадров (порог относительно громкого кадра)."""
    fl = SR // 100
    n = len(x) // fl
    rms = np.sqrt(np.mean(x[: n * fl].reshape(n, fl) ** 2, axis=1) + 1e-12)
    thr = max(rms.max() * 10 ** (rel_db / 20), abs_floor)
    on = np.nonzero(rms > thr)[0]
    if len(on) == 0:
        return x
    pad = SR * pad_ms // 1000
    s = max(0, on[0] * fl - pad)
    e = min(len(x), (on[-1] + 1) * fl + pad)
    return x[s:e]


def active_rms(x: np.ndarray) -> float:
    fl = SR // 100
    n = len(x) // fl
    rms = np.sqrt(np.mean(x[: n * fl].reshape(n, fl) ** 2, axis=1))
    act = rms[rms > rms.max() * 0.05]
    return float(np.sqrt(np.mean(act ** 2))) if len(act) else 1e-6


def colored_noise(n: int, rng: np.random.Generator, slope: float) -> np.ndarray:
    """Шум со спектром 1/f^slope (1 — розовый, 2 — коричневый), единичная дисперсия."""
    spec = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / SR)
    f[0] = f[1]
    spec *= 1 / f ** (slope / 2)
    spec[f < 20] *= (f[f < 20] / 20) ** 2  # без инфразвука
    y = np.fft.irfft(spec, n)
    return y / (y.std() + 1e-12)


# ---------------------------------------------------------------- TTS
class Voices:
    def __init__(self, tts_dir: str, clip_dir: str, deterministic: bool = False):
        # Piper/VITS сэмплирует шум внутри ONNX-графа, сид из Python не задать, поэтому
        # повторяемость обеспечивает дисковый кэш клипов: первый запуск синтезирует,
        # следующие берут те же самые клипы. --deterministic обнуляет шум (ровнее интонация).
        self.engines, self.cache = {}, {}
        self.available = []
        self.clip_dir = clip_dir
        self.det = deterministic
        os.makedirs(clip_dir, exist_ok=True)
        import sherpa_onnx
        for v in PIPER_VOICES:
            onnx = glob.glob(os.path.join(tts_dir, v, "*", f"ru_RU-{v}-medium.onnx"))
            if not onnx:
                print(f"[warn] голос {v} не найден в {tts_dir}/{v}", file=sys.stderr)
                continue
            d = os.path.dirname(onnx[0])
            cfg = sherpa_onnx.OfflineTtsConfig(
                model=sherpa_onnx.OfflineTtsModelConfig(
                    vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                        model=onnx[0], tokens=os.path.join(d, "tokens.txt"),
                        data_dir=os.path.join(d, "espeak-ng-data"),
                        **({"noise_scale": 0.0, "noise_scale_w": 0.0} if deterministic else {})),
                    num_threads=4, provider="cpu"),
                max_num_sentences=1)
            try:
                self.engines[v] = sherpa_onnx.OfflineTts(cfg)
                self.available.append(v)
            except Exception as e:  # noqa: BLE001
                print(f"[warn] голос {v} не загрузился: {e}", file=sys.stderr)
        if sys.platform == "darwin":
            r = subprocess.run(["say", "-v", "?"], capture_output=True, text=True)
            if any(line.split()[:1] == ["Milena"] for line in r.stdout.splitlines()):
                self.available.append("milena")

    def synth(self, voice: str, text: str, speed: float = 1.0) -> np.ndarray:
        key = (voice, text, round(speed, 3))
        if key in self.cache:
            return self.cache[key]
        h = hashlib.sha1(f"{voice}|{text}|{key[2]}|{int(self.det)}".encode()).hexdigest()[:16]
        path = os.path.join(self.clip_dir, f"{voice}_{h}.wav")
        if os.path.exists(path):
            x, _ = sf.read(path, dtype="float32")
            self.cache[key] = x
            return x
        if voice == "milena":
            with tempfile.TemporaryDirectory() as d:
                aiff, wav = os.path.join(d, "a.aiff"), os.path.join(d, "a.wav")
                subprocess.run(["say", "-v", "Milena", "-r", str(int(185 * speed)), "-o", aiff, text], check=True)
                subprocess.run(["afconvert", "-f", "WAVE", "-d", f"LEI16@{SR}", "-c", "1", aiff, wav], check=True)
                x, sr = sf.read(wav, dtype="float32")
            assert sr == SR
        else:
            a = self.engines[voice].generate(text, sid=0, speed=speed)
            x = resample(np.asarray(a.samples, dtype=np.float32), a.sample_rate, SR)
        sf.write(path, x, SR, subtype="FLOAT")
        self.cache[key] = x
        return x


# ---------------------------------------------------------------- сборка
def pick_voices(spec_voices: dict, available: list) -> dict:
    used, out = set(), {}
    for role, prefs in spec_voices.items():
        v = next((p for p in prefs if p in available and p not in used), None)
        if v is None:
            v = next((p for p in available if p not in used), None)
        if v is None:
            raise RuntimeError(f"не хватает голосов для роли {role}")
        used.add(v)
        out[role] = v
    return out


def build(name: str, spec: dict, voices: Voices, rng: np.random.Generator):
    vmap = pick_voices(spec["voices"], voices.available)
    # у каждого голоса свой уровень записи (-26..-18 dBFS) — как разные микрофоны/расстояния
    level = {r: 10 ** (rng.uniform(-26, -18) / 20) for r in vmap}
    order = []
    for r, _ in spec["dialog"]:
        if r not in order:
            order.append(r)
    spk_id = {r: f"speaker_{i + 1}" for i, r in enumerate(order)}

    audio = [np.zeros(int(rng.uniform(0.5, 1.0) * SR), np.float32)]
    pos = len(audio[0])
    ref = []
    lo, hi = spec["gaps"]
    for i, (role, text) in enumerate(spec["dialog"]):
        speed = float(rng.uniform(0.94, 1.08))
        seg = trim(voices.synth(vmap[role], text, speed))
        seg = seg / active_rms(seg) * level[role] * 10 ** (rng.uniform(-1.5, 1.5) / 20)
        ref.append({"speaker_id": spk_id[role], "start_ms": int(round(pos * 1000 / SR)),
                    "end_ms": int(round((pos + len(seg)) * 1000 / SR)), "text": text})
        audio.append(seg.astype(np.float32))
        pos += len(seg)
        gap = rng.uniform(lo, hi) if i + 1 < len(spec["dialog"]) else rng.uniform(0.6, 1.0)
        g = np.zeros(int(gap * SR), np.float32)
        audio.append(g)
        pos += len(g)
    x = np.concatenate(audio)
    # еле слышный «тихий» фон, чтобы паузы не были цифровым нулём
    x = x + 10 ** (-70 / 20) * colored_noise(len(x), rng, 1.0).astype(np.float32)
    peak = np.abs(x).max()
    if peak > 0.95:
        x *= 0.95 / peak
    return x.astype(np.float32), ref, vmap


def make_noisy(x: np.ndarray, ref: list, rng: np.random.Generator, snr_db: float = 15.0) -> np.ndarray:
    speech = np.concatenate([x[s["start_ms"] * SR // 1000: s["end_ms"] * SR // 1000] for s in ref])
    p_speech = float(np.mean(speech ** 2))
    noise = 0.6 * colored_noise(len(x), rng, 1.0) + 0.4 * colored_noise(len(x), rng, 2.0)
    noise /= noise.std()
    y = x + noise * np.sqrt(p_speech / 10 ** (snr_db / 10))
    # смена усиления: на ~45% длины за 1.5 с уровень падает на 7 дБ (как будто телефон отодвинули)
    n = len(y)
    t0, t1 = int(0.45 * n), int(0.45 * n) + int(1.5 * SR)
    gain = np.ones(n)
    gain[t0:t1] = np.linspace(1.0, 10 ** (-7 / 20), t1 - t0)
    gain[t1:] = 10 ** (-7 / 20)
    y = y * gain * 1.6  # плюс общий подъём уровня — проверка на «громкую» запись
    peak = np.abs(y).max()
    if peak > 0.98:
        y *= 0.98 / peak
    return y.astype(np.float32)


def write(out_dir: str, name: str, x: np.ndarray, ref: list):
    sf.write(os.path.join(out_dir, name + ".wav"), x, SR, subtype="PCM_16")
    with open(os.path.join(out_dir, name + ".ref.json"), "w", encoding="utf-8") as f:
        json.dump({"transcript": ref}, f, ensure_ascii=False, indent=2)


def write_m4a(path: str, x: np.ndarray):
    y = resample(x, SR, 44100)
    st = np.stack([y, y * 0.97], axis=1)  # стерео, каналы чуть различаются по уровню
    with tempfile.TemporaryDirectory() as d:
        wav = os.path.join(d, "a.wav")
        sf.write(wav, st, 44100, subtype="PCM_16")
        if os.path.exists(path):
            os.remove(path)
        subprocess.run(["afconvert", "-f", "m4af", "-d", "aac", "-b", "128000", wav, path], check=True)


def summary(name: str, x: np.ndarray, ref: list, vmap: dict):
    spk = sorted({s["speaker_id"] for s in ref})
    longest = max((s["end_ms"] - s["start_ms"]) / 1000 for s in ref)
    print(f"{name:12s} {len(x) / SR:6.1f} s  спикеров {len(spk)}  реплик {len(ref):2d}  "
          f"самая длинная {longest:4.1f} s  голоса {vmap}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default=".cache/test")
    ap.add_argument("--tts-dir", default=".cache/tts")
    ap.add_argument("--clip-cache", default=".cache/tts/clips", help="кэш синтезированных клипов")
    ap.add_argument("--deterministic", action="store_true", help="noise_scale=0: повторяемо без кэша")
    a = ap.parse_args()
    os.makedirs(a.out_dir, exist_ok=True)
    voices = Voices(a.tts_dir, a.clip_cache, a.deterministic)
    print("доступные голоса:", voices.available)
    built = {}
    for i, (name, spec) in enumerate(SETS.items()):
        rng = np.random.default_rng(SEED + i)
        try:
            x, ref, vmap = build(name, spec, voices, rng)
        except RuntimeError as e:
            print(f"[skip] {name}: {e}", file=sys.stderr)
            continue
        write(a.out_dir, name, x, ref)
        built[name] = (x, ref, vmap)
        summary(name, x, ref, vmap)
    if "piper_3spk" in built:
        x, ref, vmap = built["piper_3spk"]
        y = make_noisy(x, ref, np.random.default_rng(SEED + 100))
        write(a.out_dir, "piper_noisy", y, ref)
        write_m4a(os.path.join(a.out_dir, "piper_noisy.m4a"), y)
        summary("piper_noisy", y, ref, vmap)
        print("             + piper_noisy.m4a (44.1 кГц stereo AAC, тот же ref)")


if __name__ == "__main__":
    main()
