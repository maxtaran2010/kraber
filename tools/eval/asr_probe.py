"""Быстрая проверка: VAD → ASR по всем кускам речи, печать токенов и таймстемпов."""
import sys, time
import numpy as np, soundfile as sf, sherpa_onnx

M = "src/android/app/src/main/assets/models"

def load(path):
    x, sr = sf.read(path, dtype="float32", always_2d=True)
    x = x.mean(axis=1)
    assert sr == 16000, sr
    return x

def recognizer():
    return sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f"{M}/asr/encoder.onnx", decoder=f"{M}/asr/decoder.onnx", joiner=f"{M}/asr/joiner.onnx",
        tokens=f"{M}/asr/tokens.txt", num_threads=4, sample_rate=16000, feature_dim=64,
        decoding_method="greedy_search", model_type="nemo_transducer")

def vad_segments(x, min_sil=0.3, max_speech=20.0, thr=0.5):
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = f"{M}/vad/silero_vad.onnx"
    cfg.silero_vad.threshold = thr
    cfg.silero_vad.min_silence_duration = min_sil
    cfg.silero_vad.min_speech_duration = 0.2
    cfg.silero_vad.max_speech_duration = max_speech
    cfg.silero_vad.window_size = 512
    cfg.sample_rate = 16000
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=600)
    out = []
    for i in range(0, len(x), 512):
        vad.accept_waveform(x[i:i + 512])
        while not vad.empty():
            out.append((vad.front.start, np.array(vad.front.samples))); vad.pop()
    vad.flush()
    while not vad.empty():
        out.append((vad.front.start, np.array(vad.front.samples))); vad.pop()
    return out

if __name__ == "__main__":
    x = load(sys.argv[1])
    rec = recognizer()
    t0 = time.time()
    segs = vad_segments(x)
    print(f"VAD: {len(segs)} segments in {time.time()-t0:.2f}s")
    for st, s in segs:
        stream = rec.create_stream(); stream.accept_waveform(16000, s); rec.decode_stream(stream)
        r = stream.result
        print(f"[{st/16000:6.2f}-{(st+len(s))/16000:6.2f}] {r.text}")
        if "-v" in sys.argv:
            print("   ", list(zip(r.tokens, [round(t, 2) for t in r.timestamps]))[:40])
