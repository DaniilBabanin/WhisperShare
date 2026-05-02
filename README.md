# WhisperShare

A small Android app that transcribes voice messages on-device using whisper.cpp,
wired into the share sheet. I built it on a Pixel 9, so that's where it's been
tested most.

- **Local only.** Audio never leaves the phone.
- **Share to transcribe.** Long-press a voice note in WhatsApp / Telegram / Signal /
  Voice Recorder, hit Share, pick WhisperShare, get text.
- **GPU path** via whisper.cpp's Vulkan backend (Mali-G715 on Pixel 9). CPU is
  fast enough on the small and base models that I don't usually bother flipping it on.
- **Multilingual.** German, English, etc., or auto-detect. Optional translate-to-English.

<p align="center">
  <img src="img/00_Settings.jpg" width="240" alt="WhisperShare home screen with model selection">
  <img src="img/01_Share.jpg" width="240" alt="WhatsApp share sheet showing WhisperShare as a target">
  <img src="img/02_Result.jpg" width="240" alt="Transcription result with copy and share buttons">
</p>

---

## Privacy

Audio never leaves your device. The only network call the app makes is the
opt-in download of a Whisper model from `huggingface.co`, which happens the first
time you tap the cloud icon next to a model. No analytics, no crash reporters,
no third-party SDKs. `INTERNET` (for that model download) is the only permission
requested.

Full privacy policy: <https://daniilbabanin.github.io/WhisperShare/privacy/>

---

## Install

Pick whichever you prefer:

- **GitHub release.** Grab `WhisperShare.apk` from the
  [latest release](https://github.com/DaniilBabanin/WhisperShare/releases/latest)
  and sideload it.
- **Obtainium.** Add `https://github.com/DaniilBabanin/WhisperShare` as a source.
  You'll get update notifications whenever a new tag is published.

arm64-v8a only (Pixel 9 / any modern Android device). Android 12 or newer.

---

## Usage

1. **Launch WhisperShare** once. Tap the cloud icon next to **Base (multilingual)**
   to download the default model (~60 MB). Pick it with the radio button.
2. **Open WhatsApp** (or Telegram / Signal). Long-press a voice message, share to
   WhisperShare.
3. Wait a second or two. The text appears on screen with **Copy** / **Share** buttons.

Settings:

- **Use GPU when available.** Only does anything if you built with `whispershare.vulkan=true`.
- **Language.** Leave empty for auto-detect, or set `de` / `en` / `fr` etc.
- **Translate to English.** If on, German/Russian/etc. audio comes out as English text.

---

## Build it

You need **Android Studio Ladybug (2024.2)** or newer with the NDK side-by-side installed.

```bash
# 1. Get the project
git clone git@github.com:DaniilBabanin/WhisperShare.git
cd WhisperShare

# 2. Open in Android Studio. It will prompt you to install:
#    - Android SDK 35
#    - NDK 27.x
#    - CMake 3.22.1
#    Accept all.

# 3. (Optional) Enable Vulkan GPU backend.
#    Edit gradle.properties:
#      whispershare.vulkan=true
#    Rebuild. APK gains ~3 MB and uses the Mali-G715 for inference.

# 4. Run on device. First launch downloads the chosen model from HuggingFace.
```

CLI build:

```bash
./gradlew assembleRelease
# unsigned APK ends up at:  app/build/outputs/apk/release/app-release-unsigned.apk
```

To install a debug build directly:

```bash
./gradlew installDebug
```

---

## Models

Pulled from `huggingface.co/ggerganov/whisper.cpp`:

| Model | Size | Speed on Pixel 9 (CPU) | Quality |
|-------|------|------------------------|---------|
| `tiny-q5_1` | 31 MB | ~15× realtime | OK for clear English |
| `base-q5_1` | 60 MB | ~8× realtime | **Recommended default** |
| `small-q5_1` | 190 MB | ~3× realtime | Best for accents / noise / mixed languages |
| `base.en-q5_1` | 60 MB | ~10× realtime | English-only, slightly faster than `base-q5_1` |

For German voice notes, use `base-q5_1` or `small-q5_1`. The English-only models
won't help you there.

With Vulkan turned on, the small model runs roughly 1.5–2× faster than CPU on a
Pixel 9. On tiny and base the gap is smaller because dispatch overhead eats most
of the win.

---

## How it works

```
[Share intent] ─▶ TranscribeActivity
                     │
                     ▼
        AudioDecoder (MediaCodec)
        decodes OPUS/AAC/MP3 → PCM 16 kHz mono float
                     │
                     ▼
        WhisperEngine (JNI → whisper.cpp)
        runs whisper_full() in a coroutine
                     │
                     ▼
              TranscribeScreen
              (selectable text + Copy/Share)
```

The native library (`libwhispershare.so`) is built by CMake during gradle sync.
CMake's `FetchContent` pulls whisper.cpp v1.7.4 from GitHub the first time.

---

## Project layout

```
app/
├── build.gradle.kts          # NDK ABIs (arm64-v8a only), Compose, CMake glue
├── CMakeLists.txt            # Fetches whisper.cpp, builds JNI .so
├── src/main/
│   ├── AndroidManifest.xml   # Share intent filters
│   ├── cpp/whisper_jni.cpp   # JNI bridge to whisper_full()
│   ├── kotlin/io/whispershare/
│   │   ├── WhisperApp.kt
│   │   ├── WhisperEngine.kt  # Kotlin wrapper of JNI calls
│   │   ├── AudioDecoder.kt   # MediaCodec → 16 kHz float PCM
│   │   ├── ModelManager.kt   # Download & list ggml models
│   │   ├── AppPreferences.kt
│   │   ├── MainActivity.kt   # Home / settings
│   │   ├── TranscribeActivity.kt   # Share-target activity
│   │   ├── ShareUtils.kt
│   │   └── ui/               # Compose screens + theme
│   └── res/                  # Strings, themes, launcher icon
└── proguard-rules.pro
```

---

## Things that might get added

- Caching: keep transcriptions in a Room database. Right now they vanish when you close the app.
- Foreground service for transcribing very long files (>5 min) so Android won't kill the activity.
- VAD pre-trimming with `ggml-silero-vad` for faster runs on long files.
- Notification with text so you don't have to keep the app foregrounded.
- Translate to languages other than English.

---

## Caveats

- **First run on a fresh build is slow.** whisper.cpp gets cloned and compiled
  for arm64. Subsequent builds are incremental and quick.
- **Vulkan on Mali drivers can crash** with extremely old quantizations. If you
  hit a crash, flip `whispershare.vulkan=false` and rebuild.

---

## License
MIT
