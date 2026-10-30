# Contributing to NTSFireTV

Thanks for your interest! NTSFireTV is a small personal project, so please keep changes focused.

## Before you start

- For anything bigger than a small fix, **open an issue first** to discuss it.
- The project targets the **Fire TV Stick 4K (Fire OS 6, Android 7.1 / API 25)** and a 1080p TV.
  Other Fire TV devices are welcome, but changes must not break this one.

## Ground rules

- **No new runtime dependencies** (no AndroidX, AppCompat, Material, Compose, image libraries...).
  The app uses only the Android framework. Test-only dependencies are fine if really needed.
- **Lightweight first**: no heavy work on the main thread, decode images at TV resolution.
- **No telemetry, no tracking, no ads.** Network access only for opt-in features.
- Every source file starts with `// SPDX-License-Identifier: GPL-3.0-or-later` (XML: an equivalent comment).
- Code comments in English; user-facing strings go in `res/values/strings.xml`
  (the UI is Italian today; translations in `values-<lang>/` are welcome).
- Put pure logic (parsing, formatting, rules) in plain Kotlin functions and cover it with JVM unit tests.

## Building and testing

You need JDK 17 and the Android SDK (compileSdk 36).

```bash
./gradlew test lintDebug assembleDebug
./gradlew installStick -Pstick=<IP-FIRE-TV>:5555   # install on a stick with all permissions
```

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for details.

## Pull requests

- One topic per PR, with a short description of what changes and how you tested it on a device.
- Make sure `./gradlew test lintDebug assembleDebug` passes.
- By contributing you agree that your code is released under the [GPL-3.0-or-later](LICENSE) license.
