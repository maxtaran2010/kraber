#!/usr/bin/env bash
# Сборка APK и установка на подключённое устройство/эмулятор: dev_install.sh  (VARIANT=release — release-сборка)
set -euo pipefail
cd "$(dirname "$0")/../src/android"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
LOG=$(mktemp)
VARIANT=${VARIANT:-debug}
TASK=":app:assemble$(echo "${VARIANT:0:1}" | tr a-z A-Z)${VARIANT:1}"
if ! ./gradlew "$TASK" --console=plain -q >"$LOG" 2>&1; then
  # без «|| tail» пустой grep при pipefail молча завершал скрипт
  { grep -E "^e: |error:|FAIL|What went wrong" -A2 "$LOG" || tail -20 "$LOG"; } | head -40 || true
  echo "BUILD FAILED"; rm -f "$LOG"; exit 1
fi
rm -f "$LOG"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
APK=app/build/outputs/apk/$VARIANT/app-$VARIANT.apk
install() { { "$ADB" install ${1:-} "$APK" 2>&1 || true; } | grep -vE '^[[:space:]]+at ' | tail -1 || true; }
no_space() { [[ $OUT == *INSUFFICIENT_STORAGE* || $OUT == *"not enough space"* ]]; }
# Удалённые старые APK, которые ещё держат открытыми лаунчер и SystemUI (перезапускаются сами).
# system_server не трогаем — его kill перезагружает Android
kill_apk_holders() {
  for pid in $("$ADB" shell "lsof 2>/dev/null | grep '==deleted==.*base.apk' | grep -E '^(ndroid.systemui|s.nexuslauncher|.*launcher[0-9]*) ' | awk '{print \$2}' | sort -u" | tr -d '\r'); do
    "$ADB" shell kill "$pid" || true
  done
}
OUT=$(install -r)
if no_space; then
  # APK ~1.4 ГБ: на эмуляторе место держат удалённые старые APK и копия LLM
  echo "Мало места на устройстве — освобождаю…"
  "$ADB" root >/dev/null 2>&1 || true; sleep 1
  kill_apk_holders
  "$ADB" shell am force-stop app.glagol || true
  # Копия модели LLM и её кэш (~2 ГБ) пересоздадутся при первом конспекте
  "$ADB" shell run-as app.glagol rm -rf cache/llm files/models/model.litertlm 2>/dev/null \
    || "$ADB" shell "rm -rf /data/data/app.glagol/cache/llm /data/data/app.glagol/files/models/model.litertlm" || true
  sleep 3
  OUT=$(install -r)
fi
if [[ $OUT != *Success* ]]; then
  # install -r держит старый APK до конца установки (нужно ~2×1.4 ГБ). Удаляем приложение, сохраняя данные (-k):
  # записи и настройки остаются и подхватываются новой установкой
  echo "Всё ещё мало места — переустанавливаю с сохранением данных…"
  "$ADB" shell pm uninstall -k app.glagol || true
  sleep 2; kill_apk_holders; sleep 3
  OUT=$(install)
  "$ADB" shell pm grant app.glagol android.permission.RECORD_AUDIO 2>/dev/null || true
  "$ADB" shell pm grant app.glagol android.permission.POST_NOTIFICATIONS 2>/dev/null || true
fi
echo "$OUT"
