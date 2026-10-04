# Perchance Shell

A single-site fullscreen Android wrapper for https://perchance.org/ai-text-to-image-generator.

- Fullscreen WebView, no address bar, tabs, history or settings. Navigation is limited to `perchance.org`.
- A small handle at the bottom edge opens a sheet: **Download All** and the private library.
- Download All collects the generated images from every frame (iframes included) via an injected script
  (`assets/capture.js`) and saves them to `filesDir/generated_images/` only. Nothing goes to Gallery or Downloads.
- Writes are atomic (temp file, then rename), de-duplicated by SHA-256, format-sniffed, and size/space checked.
- Library: grid, detail view, delete. No share or export.

## Layout
`MainActivity` (WebView, sheet) · `CaptureController` (progress, saving) · `ImageStore` (private storage) ·
`LibraryActivity` (grid/detail) · `assets/capture.js` (swappable capture strategy)

## Building
JDK 17, Android SDK 34. `gradle assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
CI workflows are unchanged from the Marrow project.

## Status
Not yet built or run. First milestone: confirm capture.js finds images inside the generator iframe.
