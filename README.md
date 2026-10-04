# Perchance Shell

A single-site fullscreen Android wrapper for https://perchance.org/ai-text-to-image-generator.

## What it does
- Fullscreen WebView, no address bar, tabs, history or settings. Main-frame navigation is locked to the generator page
  (iframes and resources are not restricted).
- **No session saving.** Cookies, DOM storage/IndexedDB, cache, history and form data are wiped on every launch and
  when the app is closed. Saved images are not affected.
- A slim tab on the right edge opens a bottom sheet: **Download All** and the private library.
- Download All reads the generated images from every frame (iframes included) via an injected script
  (`app/src/main/assets/capture.js`) and saves them to app-private storage only. Nothing goes to Gallery or Downloads.
- Writes are atomic (temp file, then rename), de-duplicated by SHA-256, format-sniffed, and size/space checked.

## Private library
- Each Download All creates a **folder** (`filesDir/generated_images/<timestamp>/`). Older loose files show as "Earlier".
- Folder list -> image grid (long-press to multi-select and delete) -> full-bleed viewer
  (tap toggles controls, filmstrip, swipe, pinch/double-tap zoom, delete). No share or export.

## Layout
`MainActivity` (WebView, sheet) · `CaptureController` (progress, saving) · `ImageStore` (private storage, folders) ·
`LibraryActivity` (folders/grid/viewer) · `assets/capture.js` (swappable capture strategy)

## Building
JDK 17, Android SDK 34. `gradle assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`.

## TODO / notes for later
- **Workflows still say "Marrow".** `.github/workflows/build.yml` is named "Build Marrow APK" and uploads the artifact
  as `marrow-release`; `release.yml` also needs renaming (release/APK names). Left untouched on purpose.
- `release.yml` calls `./gradlew`, but the repo has no Gradle wrapper. Add one (`gradle wrapper --gradle-version 8.4`)
  before using tag releases.
- Release builds fall back to the debug signing key when the keystore secrets are empty. Add real secrets for a stable key.
- Perchance's embed can show "Waited too long for token" and retry on its own; this comes from the site, not the app.
- Unverified on device: capture of images inside the generator iframe. First thing to test after each change.
