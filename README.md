# WhisperBoard

Android keyboard with **on-device voice transcription** powered by [whisper.cpp](https://github.com/ggerganov/whisper.cpp). Your voice never leaves your phone.

Built on top of [HeliBoard](https://github.com/Helium314/HeliBoard) (privacy-focused FOSS keyboard) with a native JNI bridge to whisper.cpp for real-time speech-to-text.

[Download latest APK](https://github.com/david-digitis/WhisperBoard/releases/latest)

## Screenshots

<p align="center">
  <img src="screenshots/recording.jpg" width="200" alt="Recording — mic button turns red"/>
  <img src="screenshots/transcribing.jpg" width="200" alt="Transcribing — mic button turns orange"/>
  <img src="screenshots/result.jpg" width="200" alt="Result — text inserted"/>
  <img src="screenshots/whisper-settings.jpg" width="200" alt="Whisper settings — model and language selection"/>
</p>

**Left to right:** Recording (red mic) | Transcribing (orange mic) | Result | Settings

## Features

- 100% on-device transcription — no internet required, no data sent anywhere
- 3 Whisper models: **Tiny** (75 MB, fast), **Base** (142 MB, balanced), **Small** (488 MB, accurate)
- In-app model download from HuggingFace
- Language support: French, English, Dutch, German, Auto-detect
- Visual feedback: red mic while recording, orange while transcribing
- Haptic feedback on start/stop recording
- Full HeliBoard keyboard: AZERTY/QWERTY, autocorrect, themes, gesture typing

## How it works

1. Tap the **mic button** in the toolbar (short vibration)
2. Speak
3. Tap the mic button again (longer vibration)
4. Text appears in the input field

The first use loads the Whisper model into memory (~2-3 seconds). Subsequent uses are instant.

## Install

### From APK (recommended)

1. Download the latest APK from [Releases](https://github.com/david-digitis/WhisperBoard/releases/latest)
2. Install on your Android device (enable "Install from unknown sources" if needed)
3. Go to **Settings > System > Languages & Input > On-screen keyboard** and enable WhisperBoard
4. Switch to WhisperBoard in any text field
5. Open WhisperBoard settings > **Whisper Voice Input** > download a model

### Build from source

Requirements: Android Studio, NDK 28+, CMake 3.22+

```bash
git clone --recurse-submodules https://github.com/david-digitis/WhisperBoard.git
cd WhisperBoard
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/WhisperBoard_3.7-debug.apk`.

## Models

| Model | Size | Speed | Accuracy | Best for |
|-------|------|-------|----------|----------|
| Tiny  | 75 MB | ~1s | Good | Quick notes, simple phrases |
| Base  | 142 MB | ~2-3s | Very good | Daily use (recommended) |
| Small | 488 MB | ~5-8s | Excellent | Long dictation, technical terms |

Models are downloaded from [ggerganov/whisper.cpp on HuggingFace](https://huggingface.co/ggerganov/whisper.cpp) and stored locally on your device.

## Architecture

```
WhisperBoard/
├── app/                          # HeliBoard keyboard app
│   └── src/main/java/.../whisper/
│       ├── WhisperManager.kt     # Recording + transcription orchestrator
│       ├── WhisperModelManager.kt # Model download from HuggingFace
│       └── AudioRecorder.kt      # 16kHz PCM mono recording
├── whisperlib/                   # Android library module
│   └── src/main/jni/whisper/
│       ├── jni.c                 # C bridge (14 JNI functions)
│       └── CMakeLists.txt        # whisper.cpp build config
└── whisper.cpp/                  # Git submodule (v1.8.3)
```

## Credits

- [HeliBoard](https://github.com/Helium314/HeliBoard) — the keyboard (GPL-3.0)
- [whisper.cpp](https://github.com/ggerganov/whisper.cpp) — C/C++ port of OpenAI Whisper (MIT)
- [kaiboard](https://github.com/kaisoapbox/kaiboard) — JNI bridge reference

## License

- Keyboard (HeliBoard fork): [GPL-3.0](LICENSE)
- whisper.cpp: [MIT](whisper.cpp/LICENSE)
