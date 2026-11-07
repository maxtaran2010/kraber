#!/bin/bash
# Средние WER/DER по набору Piper для набора вариантов параметров
cd "$(dirname "$0")/../.."
run() {
  tools/eval/bench.sh "$@" | grep piper | awk -v args="$*" '{w+=$3; d+=$5; split($7,s,","); if (s[1]==substr($7,length($7))) ok++; n++} END {printf "%-28s WER %5.2f  DER %5.2f  spk_ok %d/%d\n", args, w/n, d/n, ok, n}'
}
run
for t in 0.35 0.4 0.5 0.55; do run --ahc-thr $t; done
for m in 0.5 0.65 1.0; do run --min-emb $m; done
for c in 2.0 4.0; do run --chunk-max $c; done
for v in 0.15 0.35; do run --vad-min-sil $v; done
