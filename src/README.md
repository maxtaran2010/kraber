# Исходный код

Платформа: mobile / android, папка [`android/`](android/) (Kotlin, Jetpack Compose).
Это корень Gradle-проекта (`settings.gradle.kts`) с двумя модулями:

- `app/`: приложение для телефона Крабер (импорт и запись, расшифровка, диаризация, LLM, экспорт);
- `wear/`: приложение для часов Wear OS (запись с микрофона, анимация, поток звука на телефон), необязательно.

## Инструкция по запуску и сборке проекта

Требования:

- macOS, Linux или Windows с Android Studio 2026.1+ (JDK берётся из неё, `jbr`)
- Android SDK Platform 37, Build-Tools 36. NDK не нужен: нативные библиотеки поставляются готовыми (AAR)
- ~10 ГБ свободного места (модели и промежуточные файлы сборки)
- Телефон: Android 9+, arm64-v8a, ≥ 4 ГБ ОЗУ, ~3.6 ГБ свободного места
- Часы (необязательно): Wear OS 3+, сопряжённые с телефоном

## 1. Модели

Один раз, нужен интернет на машине сборки.

```bash
scripts/download_models.sh
```

Скрипт скачивает AAR sherpa-onnx 2.0.1 в `src/android/app/libs/`, а GigaAM v3, Silero VAD, CAM++, Qwen3 1.7B и 0.6B
в `src/android/app/src/main/assets/models/`. Модели не хранятся в Git (≈ 1.5 ГБ).

## 2. Сборка

```bash
cd src/android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS
./gradlew :app:assembleRelease      # результат: app/build/outputs/apk/release/app-release.apk (~1.6 ГБ, модели внутри)
./gradlew :wear:assembleRelease     # результат: wear/build/outputs/apk/release/wear-release.apk (~2 МБ, часы)
```

Оба release подписаны debug-ключом. APK ставится без дополнительных шагов, а часы и телефон с одной подписью видят друг
друга по Wearable Data Layer. Можно также открыть папку `src/android/` в Android Studio и нажать Run.

## 3. Установка

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
# или из корня репозитория: scripts/dev_install.sh   (VARIANT=release для release-сборки)
# часы (adb по Wi-Fi: adb connect <ip>:<порт>): scripts/wear_install.sh   (VARIANT=release)
```

## 4. Тесты

```bash
./gradlew :app:testDebugUnitTest :wear:testDebugUnitTest
```

## Отладочные возможности (только debug-сборка)

```bash
# Обработать файл без UI (для автотестов):
adb shell am start -n app.glagol/.MainActivity -a android.intent.action.VIEW \
  -d file:///data/data/app.glagol/files/test/x.wav -t audio/wav --ez autostart true [--ei speakers N]
# Пересчитать конспект записи / задать вопрос:
adb shell am start -n app.glagol/.MainActivity --es resummarize <id>
# Живой режим без микрофона (WAV вместо микрофона, затем кнопка записи):
adb shell am start -n app.glagol/.MainActivity --es live_wav /data/local/tmp/x.wav
# Часы-эмулятор без пары по Bluetooth: мост через Mac (делает scripts/wear_install.sh)
adb -s <телефон> forward tcp:7788 tcp:7788
adb -s <часы> shell am start -n app.glagol/app.glagol.wear.MainActivity --es live_wav /data/local/tmp/x.wav --ez autostart true
```

Автоматический прогон тестового набора с подсчётом метрик: `tools/eval/device_eval.sh` (см. `docs/TEST_RESULTS.md`).

## Структура `android/app/src/main/java/app/glagol/`

```
audio/     декодер (MediaCodec), ресемплер, WAV, запись с микрофона, плеер
ml/        VAD, ASR, эмбеддинги, диаризация, сборка реплик, пайплайн, живой режим, имена, LLM (процесс :llm)
service/   foreground-сервис обработки, очередь файлов и конспектов
watch/     приём записей с часов (Wearable Data Layer), протокол
data/      модели данных, хранилище записей (filesDir/recordings/<id>/)
export/    JSON по ТЗ, TXT, ZIP, Поделиться
ui/        Compose-экраны: главная, запись, запись с часов, расшифровка; тема Material 3
```

## Структура `android/wear/src/main/java/app/glagol/wear/`

```
MicRecorder.kt          запись с микрофона часов в WAV 16 кГц
PhoneLink.kt            поиск телефона (CapabilityClient) и канал ChannelClient
RecordingController.kt  состояния, живой поток, досылка файлом, очередь неотправленных
RecordService.kt        foreground-сервис микрофона
ui/                     экран часов, круговая волна, тема
WatchProtocol.kt        протокол между часами и телефоном (копия в app/…/watch/)
```
