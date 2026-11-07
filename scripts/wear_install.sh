#!/usr/bin/env bash
# Сборка и установка приложения для часов (Wear OS): wear_install.sh
#   VARIANT=release — release-сборка; WATCH=<serial> — если часов подключено несколько.
# Реальные часы: включить «Отладку по Wi-Fi» в настройках разработчика часов, затем `adb connect <ip>:<порт>`.
# Два эмулятора (телефон + часы) без пары по Bluetooth: скрипт поднимает мост `adb forward tcp:7788` на телефоне —
# debug-сборка часов стучится на 10.0.2.2:7788 (Mac) и попадает в Крабер на телефоне.
set -euo pipefail
cd "$(dirname "$0")/../src/android"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
VARIANT=${VARIANT:-debug}
TASK=":wear:assemble$(echo "${VARIANT:0:1}" | tr a-z A-Z)${VARIANT:1}"
LOG=$(mktemp)
if ! ./gradlew "$TASK" --console=plain -q >"$LOG" 2>&1; then
  { grep -E "^e: |error:|FAIL|What went wrong" -A2 "$LOG" || tail -20 "$LOG"; } | head -40 || true
  echo "BUILD FAILED"; rm -f "$LOG"; exit 1
fi
rm -f "$LOG"
APK=wear/build/outputs/apk/$VARIANT/wear-$VARIANT.apk

PHONE=""
for s in $("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'); do
  kind=$("$ADB" -s "$s" shell getprop ro.build.characteristics | tr -d '\r')
  if [[ $kind == *watch* ]]; then WATCH=${WATCH:-$s}; else PHONE=${PHONE:-$s}; fi
done
if [[ -z ${WATCH:-} ]]; then
  echo "Часы не найдены в adb devices. Реальные часы: adb connect <ip>:<порт> (Отладка по Wi-Fi)."; exit 1
fi
echo "Часы: $WATCH"
"$ADB" -s "$WATCH" install -r "$APK" | tail -1
"$ADB" -s "$WATCH" shell pm grant app.glagol android.permission.RECORD_AUDIO 2>/dev/null || true

if [[ $VARIANT == debug && $WATCH == emulator-* && $PHONE == emulator-* ]]; then
  "$ADB" -s "$PHONE" forward tcp:7788 tcp:7788 >/dev/null
  echo "Мост эмуляторов: часы → 10.0.2.2:7788 → $PHONE (Крабер, debug-сборка)"
fi
