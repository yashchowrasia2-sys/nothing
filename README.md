# Glyph Studio & HUD — Nothing Phone (4a) Pro

Jetpack Compose app + Glyph Toy service for the 13x13 / 137-LED Glyph Matrix (`Glyph.DEVICE_25111p`).

## Setup
1. Put the Glyph Matrix SDK `.aar` in `app/libs/`.
2. Build & install: `./gradlew installDebug` (generate the wrapper with `gradle wrapper` if missing).
3. Glyph settings → Glyph Toys → enable **Glyph Studio HUD** (AOD supported). Or use **Live Mirror** in the Smart HUD tab while the app is open.

## Self-updater
Publish a GitHub Release on `yashchowrasia2-sys/nothing` with tag `vX.Y.Z` and an `.apk` asset signed with the same key and a higher `versionCode`. The updater refuses APKs with a different package name, signing cert, or a non-higher versionCode.

## Layout
- `data/` models, 5x7 + 3x5 fonts, DataStore storage, share codes (`GLY1:` = Base64URL(gzip(json)))
- `engine/` frame renderers (art, marquee, visualizer, pet, pomodoro) + `GlyphEngineService` + app-side `GlyphAppLink`
- `ui/` Studio, Smart HUD, Gallery screens; `update/AppUpdater.kt`
