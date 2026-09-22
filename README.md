# SHIR — Offline Stem Separator

Android 4.4.4 / API 19 compatible music stem separator with an internal player.

## Goal
- Offline vocal / instrumental separation
- 4 stems: vocals, drums, bass, other
- Internal A/B playback with solo/mute/volume per stem
- Physical-key navigation
- Background processing and cached results
- No cloud processing of the audio

The inference layer is designed around the C++ Demucs implementation and local model files. Demucs.cpp is fetched by the build pipeline rather than copied into the app repository.

## Build
The GitHub Actions workflow builds a debug APK with Android API 19 as the minimum/target compatibility baseline.
