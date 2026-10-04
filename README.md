# Perchance Shell

A minimal Android client for the Perchance AI Text-to-Image Generator.

Fullscreen WebView, three-finger swipe up to reveal download controls, images saved only to private app storage.

## Features

- Fullscreen WebView of `https://perchance.org/ai-text-to-image-generator`
- Restricted navigation (only perchance.org origins)
- Three-finger swipe up → Download All + Private Library
- Private storage only (`filesDir/generated_images/`) – nothing goes to Gallery
- Atomic writes + basic SHA-256 dedupe
- Minimal UI using system defaults

## Building

Requirements: JDK 17, Android SDK 34+

```bash
git clone <your-repo>
cd perchance-shell
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions

- Push to `main` → builds signed release APK (artifact)
- Tag `v*` or manual dispatch → creates GitHub Release with APK

Required secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASS`
- `KEY_ALIAS`
- `KEY_PASS`

## Stack

- Kotlin
- Android WebView + AndroidX WebKit
- Coroutines
- No third-party UI libraries

## Status

Personal project. Expect rough edges. The capture pipeline is a working skeleton; real iframe image extraction can be improved later.
