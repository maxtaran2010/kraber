#!/bin/bash
# Прогон тестовых файлов через приложение на устройстве/эмуляторе (debug-сборка) и оценка.
#   tools/eval/device_eval.sh piper_2spk piper_3spk ...   (файлы .cache/test/<name>.wav + .ref.json)
cd "$(dirname "$0")/../.."
ADB=${ADB:-~/Library/Android/sdk/platform-tools/adb}
for f in "$@"; do
  src=.cache/test/$f.wav; [ -f "$src" ] || src=.cache/test/$f
  ext="${src##*.}"; ref=.cache/test/${f%.*}.ref.json
  cat "$src" | $ADB shell "run-as app.glagol sh -c 'mkdir -p files/test && cat > files/test/$f.$ext'"
  $ADB logcat -c
  $ADB shell am start -n app.glagol/.MainActivity -a android.intent.action.VIEW \
    -d "file:///data/data/app.glagol/files/test/$f.$ext" -t audio/wav --ez autostart true >/dev/null 2>&1
  id=""; for i in $(seq 1 300); do
    line=$($ADB logcat -d -s Processing:I | grep -E "done |pipeline failed" | tail -1)
    [ -n "$line" ] && break; sleep 1
  done
  id=$(echo "$line" | sed -nE 's/.*done ([a-z0-9]+):.*/\1/p')
  echo "== $f: $(echo "$line" | sed -E 's/.*Processing: //')"
  [ -z "$id" ] && continue
  $ADB shell run-as app.glagol cat files/recordings/$id/transcript.json > .cache/test/${f%.*}.dev.json
  python3 -I tools/eval/eval.py "$ref" .cache/test/${f%.*}.dev.json
done
