#!/bin/bash
# Пересчитать конспекты записей (debug-APK) по одной и напечатать результат: llm_compare.sh <id> [<id>...]
# Вопрос к записи: llm_compare.sh --ask <id> "Кто что пообещал?" [--ask <id> "вопрос"]...
ADB=${ADB:-~/Library/Android/sdk/platform-tools/adb}
meta() { $ADB shell run-as app.glagol cat files/recordings/$1/meta.json; }
status() { meta $1 | python3 -c "import sys,json; print(json.load(sys.stdin).get('summaryStatus'))"; }
qa_count() { meta $1 | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('qa') or []))"; }
print_summary() {
  meta $1 | python3 -c "
import sys,json
m=json.load(sys.stdin); d=m.get('digest')
print('Название:', m.get('title'))
if not d: print(m.get('summary') or '(конспекта нет) ' + str(m.get('summaryStatus'))); sys.exit()
if d.get('gist'): print('Суть:', d['gist'])
for p in d.get('points') or []: print('  •', p)
if d.get('agreed'): print('Договорились:'); [print('  ✓', a) for a in d['agreed']]
for c in d.get('chapters') or []:
  f=lambda ms: '%d:%02d' % (ms//60000, ms//1000%60)
  print('  [%s–%s] %s' % (f(c['startMs']), f(c['endMs']), c['text']))"
}
while [ $# -gt 0 ]; do
  if [ "$1" = "--ask" ]; then
    id=$2; q=$3; shift 3
    n0=$(qa_count $id); $ADB logcat -c
    # Вопрос передаётся через отладочный extra «resummarize» с префиксом ask: (см. ProcessingManager.retrySummary)
    $ADB shell "am start -n app.glagol/.MainActivity --es resummarize 'ask:$id:$q'" >/dev/null 2>&1
    t0=$(date +%s)
    for i in $(seq 1 300); do [ "$(qa_count $id)" != "$n0" ] && break; sleep 1; done
    echo "== $id ? $q  ($(( $(date +%s) - t0 )) s) $($ADB logcat -d | grep -E 'LLM ready|I LLM *: ask' | sed -E 's/.*LLM *: //' | tr '\n' ' ')"
    meta $id | python3 -c "import sys,json; print('  →', (json.load(sys.stdin).get('qa') or [{}])[-1].get('a'))"
    continue
  fi
  id=$1; shift
  $ADB logcat -c
  $ADB shell am start -n app.glagol/.MainActivity --es resummarize "$id" >/dev/null 2>&1
  for i in $(seq 1 20); do [ "$(status $id)" != "Done" ] && break; sleep 0.5; done
  t0=$(date +%s)
  for i in $(seq 1 1800); do s=$(status $id); [ "$s" = "Done" -o "$s" = "Failed" ] && break; sleep 1; done
  echo "== $id  ($(( $(date +%s) - t0 )) s) $($ADB logcat -d | grep -E 'LLM ready|I LLM *: summary:' | sed -E 's/.*LLM *: //' | tr '\n' ' ')"
  print_summary $id
done
