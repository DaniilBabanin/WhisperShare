---
title: WhisperShare Privacy Policy
permalink: /privacy/
---

# Privacy Policy

_Last updated: 2026-05-02_

WhisperShare ("the app") is an open-source Android application that transcribes
voice messages locally on your device using [whisper.cpp][whispercpp]. This
policy explains exactly what data the app does and does not handle.

## Summary

**WhisperShare does not collect, store, or transmit any personal data about
you.** All audio you share into the app is processed on your device and never
leaves it.

## What data the app processes

### Audio you share into the app

When you share a voice message or audio file to WhisperShare, the audio is
decoded in memory and passed to a local Whisper inference engine (whisper.cpp,
running entirely on your device). The audio is **never uploaded** to any
server. Temporary decoded audio buffers may be written briefly to the app's
private cache directory and are deleted immediately after transcription
completes.

### Transcription results

Transcribed text is shown to you on screen. WhisperShare does not save it,
upload it, or share it anywhere unless you explicitly tap **Copy** or **Share**
to send it via another app you choose.

### App preferences

The app stores the following settings in Android's app-private
`SharedPreferences` storage on your device only:

- Selected Whisper model (e.g. `BASE_Q5`)
- Selected language (or empty for auto-detect)
- Translate-to-English toggle (on/off)
- GPU acceleration toggle (on/off)

These never leave your device.

### Whisper model files

Whisper model files (e.g. `ggml-base-q5_1.bin`) are downloaded on demand from
the public [Hugging Face repository for whisper.cpp][hfwhispercpp] over HTTPS
when you tap the download icon next to a model in the app. This is the only
network request the app ever makes. Hugging Face's own privacy practices are
governed by [their privacy policy][hfprivacy] — WhisperShare sends them only
the standard HTTP request needed to fetch the model file.

Downloaded models are stored in the app's private files directory on your
device.

## What data the app does NOT process

- No analytics or telemetry of any kind.
- No crash reporting (no Firebase, no Crashlytics, no Sentry).
- No advertising SDKs.
- No third-party tracking SDKs.
- No accounts, sign-in, or user identifiers.
- No location, contacts, calendar, microphone, camera, or storage access
  beyond the file you explicitly share into the app.

## Permissions the app requests

- **`INTERNET`** — solely to download Whisper model files from Hugging Face
  when you request them.

That is the only permission requested.

## Children's privacy

WhisperShare does not knowingly collect data from anyone, including children
under 13. The app does not collect data at all.

## Open source

WhisperShare is open source under the MIT license. You can audit every line of
the code and the network calls the app makes at:
<https://github.com/DaniilBabanin/WhisperShare>

## Contact

Questions about this policy: <contact@babanin.de>

## Changes to this policy

If this policy ever changes, the updated version will appear in this repository
and the "Last updated" date at the top will be revised.

[whispercpp]: https://github.com/ggerganov/whisper.cpp
[hfwhispercpp]: https://huggingface.co/ggerganov/whisper.cpp
[hfprivacy]: https://huggingface.co/privacy
