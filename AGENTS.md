# Repository Agent Rules

## git limit

- strict to the commit [principle](https://www.conventionalcommits.org/en/v1.0.0/).

## Development boundary

- If the feature can come true in official library or third-party library, prioritize to use them, avoid coding yourself. Using library before the user confirmation.

## Android build and test boundary

- The Android implementation is the Kotlin/Jetpack Compose code under `app/`.
- Run JVM unit tests and lint locally. Gradle compilation required by these checks is allowed.
- Do not assemble APKs locally or run emulator/real-device tests unless the maintainer requests them.
- Push Android changes to the `Android` branch and use GitHub Actions for APK compilation, signing, and release publishing.
- Local JVM tests and lint must pass before pushing; CI must compile the APKs before artifacts are considered buildable.
- After CI compilation passes, publish the signed release APK to GitHub Releases.
- Real-device installation and behavioral testing are performed by the maintainer after CI artifacts are published.
- Do not claim build or device-test success from source inspection alone; report local checks, CI compilation, and device validation separately.

## Flutter boundary

- Flutter files remain historical compatibility material and are not the Android implementation source.
- New Android features must be implemented in the native Kotlin/Compose app.
