#!/bin/bash
# Прогон прототипа по всему набору: bench.sh [доп. аргументы proto_pipeline.py]
cd "$(dirname "$0")/../.."
for f in piper_2spk piper_3spk piper_4spk piper_fast piper_noisy dialog1; do
  [ -f .cache/test/$f.wav ] || continue
  info=$(.cache/venv/bin/python tools/eval/proto_pipeline.py .cache/test/$f.wav .cache/test/$f.hyp${TAG}.json "$@" 2>/dev/null | tail -1)
  rtf=$(echo "$info" | python3 -c "import sys,json; print('%.3f'%json.load(sys.stdin)['rtf'])" 2>/dev/null)
  python3 -I tools/eval/eval.py .cache/test/$f.ref.json .cache/test/$f.hyp${TAG}.json | awk -v f=$f -v r=$rtf '
    /^WER/ {w=$2} /^DER/ {d=$2; c=$NF} /^Спикеры/ {s=$3"/"$5} /^Границы/ {b=$3"/"$6}
    END {printf "%-12s WER %6s  DER %6s  spk %-5s  bounds %-12s rtf %s\n", f, w, d, s, b, r}'
done
