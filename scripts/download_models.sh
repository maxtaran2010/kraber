#!/usr/bin/env bash
# Скачивает нативную библиотеку sherpa-onnx (AAR) и все модели, которые встраиваются в APK.
# Запуск из корня репозитория:  ./scripts/download_models.sh
# Повторный запуск пропускает уже скачанное.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/src/android/app"
ASSETS="$APP/src/main/assets/models"
CACHE="$ROOT/.cache/downloads"
mkdir -p "$CACHE" "$APP/libs" "$ASSETS"/{asr,diarization,vad,llm}

SHERPA_VER="2.0.1"
GH="https://github.com/k2-fsa/sherpa-onnx/releases/download"
ASR_NAME="sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16"
LLM_URL="https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"

fetch() { # url dst
  if [ -s "$2" ]; then echo "✓ $(basename "$2")"; return; fi
  echo "↓ $(basename "$2")"
  curl -fL --retry 5 --retry-delay 3 -o "$2.part" "$1"
  mv "$2.part" "$2"
}

# 1. sherpa-onnx AAR (JNI .so + Kotlin API)
fetch "$GH/v$SHERPA_VER/sherpa-onnx-$SHERPA_VER.aar" "$APP/libs/sherpa-onnx-$SHERPA_VER.aar"

# 2. ASR: GigaAM v3 RNNT с пунктуацией и регистром
if [ ! -s "$ASSETS/asr/tokens.txt" ]; then
  fetch "$GH/asr-models/$ASR_NAME.tar.bz2" "$CACHE/$ASR_NAME.tar.bz2"
  tar -xjf "$CACHE/$ASR_NAME.tar.bz2" -C "$CACHE"
  src="$CACHE/$ASR_NAME"
  # берём int8-версии, если есть
  pick() { ls "$src"/$1*int8*.onnx 2>/dev/null | head -1 || true; }
  enc=$(pick encoder); [ -z "$enc" ] && enc=$(ls "$src"/encoder*.onnx | head -1)
  dec=$(pick decoder); [ -z "$dec" ] && dec=$(ls "$src"/decoder*.onnx | head -1)
  joi=$(pick joiner);  [ -z "$joi" ] && joi=$(ls "$src"/joiner*.onnx | head -1)
  cp "$enc" "$ASSETS/asr/encoder.onnx"
  cp "$dec" "$ASSETS/asr/decoder.onnx"
  cp "$joi" "$ASSETS/asr/joiner.onnx"
  cp "$src/tokens.txt" "$ASSETS/asr/tokens.txt"
  mkdir -p "$ROOT/.cache/test-wavs" && cp "$src"/test_wavs/*.wav "$ROOT/.cache/test-wavs/" 2>/dev/null || true
  rm -rf "$src" "$CACHE/$ASR_NAME.tar.bz2"
else echo "✓ asr"; fi

# 3. Диаризация: эмбеддинги спикеров CAM++ (в APK).
#    pyannote segmentation 3.0 — только для сравнения в tools/eval (в APK не входит).
if [ ! -s "$ROOT/.cache/models/segmentation.onnx" ]; then
  mkdir -p "$ROOT/.cache/models"
  fetch "$GH/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" "$CACHE/seg.tar.bz2"
  tar -xjf "$CACHE/seg.tar.bz2" -C "$CACHE"
  seg=$(ls "$CACHE"/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx 2>/dev/null || ls "$CACHE"/sherpa-onnx-pyannote-segmentation-3-0/model.onnx)
  cp "$seg" "$ROOT/.cache/models/segmentation.onnx"
  rm -rf "$CACHE/sherpa-onnx-pyannote-segmentation-3-0" "$CACHE/seg.tar.bz2"
else echo "✓ segmentation"; fi
fetch "$GH/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx" "$ASSETS/diarization/embedding.onnx"

# 4. VAD
fetch "$GH/asr-models/silero_vad_v5.onnx" "$ASSETS/vad/silero_vad.onnx"

# 5. Локальная LLM (LiteRT-LM): Qwen3 1.7B, int4
# Если лежит другая модель (по размеру) — перекачиваем
LLM_SIZE=977184032
if [ -s "$ASSETS/llm/model.litertlm" ] && [ "$(stat -f%z "$ASSETS/llm/model.litertlm" 2>/dev/null || stat -c%s "$ASSETS/llm/model.litertlm")" != "$LLM_SIZE" ]; then
  rm -f "$ASSETS/llm/model.litertlm"
fi
fetch "$LLM_URL" "$ASSETS/llm/model.litertlm"

echo
du -sh "$ASSETS"/* "$APP/libs"/*.aar
