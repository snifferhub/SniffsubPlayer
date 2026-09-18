# Repository Agent Rules

## Android build and test boundary

- The Android implementation is the Kotlin/Jetpack Compose code under `app/`.
- Do not run local Gradle compilation, unit tests, lint, APK assembly, emulator tests, or real-device tests.
- Push Android changes to the `Android` branch and use GitHub Actions as the build and test environment.
- CI must run JVM tests, lint, and APK compilation before artifacts are considered buildable.
- After all CI checks pass, publish the signed release APK to GitHub Releases.
- Real-device installation and behavioral testing are performed by the maintainer after CI artifacts are published.
- Do not claim build or device-test success from source inspection alone; report CI and device validation separately.

## Flutter boundary

- Flutter files remain historical compatibility material and are not the Android implementation source.
- New Android features must be implemented in the native Kotlin/Compose app.
