# Repository Agent Rules

## git limit

- strict to the commit [principle](https://www.conventionalcommits.org/en/v1.0.0/).

## Git authorization and CI scope

- By default, implement and verify requested changes in the working tree only. Do not stage, commit, push, create or switch branches/worktrees, merge, rebase, reset, amend, tag, or otherwise mutate Git state unless the maintainer explicitly requests the relevant operation. Read-only Git inspection is allowed.
- Requests to implement a feature, fix a bug, edit documentation, or update `AGENTS.md` do not authorize Git operations. Earlier Git authorization does not automatically extend to a new task unless the maintainer explicitly grants ongoing authorization.
- Keep authorization scoped to the requested operations: permission to commit does not imply permission to push, publish a release, or manually dispatch CI. Leave completed changes uncommitted when no Git operation was requested.
- When a push is explicitly authorized, let the repository's normal CI triggers run by default. Do not add `[skip ci]` or otherwise suppress CI unless the maintainer explicitly requests it for that push or clearly defines a broader scope.
- Instructions such as "skip CI this time" apply only to the specified commit/push or current explicitly authorized operation. Do not carry them into later features or commits, and do not infer CI suppression from a previous skipped commit.
- Manually dispatching CI, rerunning a build, or manually publishing/editing a Release requires explicit authorization for that action. An explicit request to build, run CI, or produce a test APK authorizes the established build/sign/release workflow for the requested revision.
- Before an authorized commit/push or CI dispatch, check the current request's Git and CI scope. If instructions conflict or the intended scope is genuinely ambiguous, clarify that specific ambiguity before acting; do not ask again when authorization is already clear.

## Development boundary

- If the feature can come true in official library or third-party library, prioritize to use them, avoid coding yourself. Using library before the user confirmation.

## Android build and test boundary

- The Android implementation is the Kotlin/Jetpack Compose code under `app/`.
- Run JVM unit tests and lint locally. Gradle compilation required by these checks is allowed.
- Do not assemble APKs locally or run emulator/real-device tests unless the maintainer requests them.
- When pushing is explicitly authorized, push Android changes to the `Android` branch. Use GitHub Actions for authorized APK compilation, signing, and release publishing; development requests alone do not authorize a push or CI dispatch.
- Local JVM tests and lint must pass before pushing; CI must compile the APKs before artifacts are considered buildable.
- For an authorized build/release, let the established CI workflow publish the signed release APK to GitHub Releases after compilation passes. Do not manually publish a Release without explicit authorization.
- Real-device installation and behavioral testing are performed by the maintainer after CI artifacts are published.
- Do not claim build or device-test success from source inspection alone; report local checks, CI compilation, and device validation separately.

## Flutter boundary

- Flutter files remain historical compatibility material and are not the Android implementation source.
- New Android features must be implemented in the native Kotlin/Compose app.

## Android release notes

- Before every Android release, update `release-notes/android.md` in the same commit as the changes with a brief Chinese summary for users: visible improvements, behavior changes, and fixes.
- Describe this release's changes; do not reuse the previous release's summary or expose private source links, credentials, or internal implementation details.
- Distinguish code fixes from real-device confirmation; do not claim a reported device issue is resolved before it is verified.
- Both private and public APK releases must use this file as their Release body. The app's update dialog reads the public Release body.
- If the maintainer explicitly requests skipping CI for an authorized commit/push, use `[skip ci]` for that operation only and do not manually dispatch a build unless subsequently requested. Existing Release text can be corrected without rebuilding an APK when that edit is explicitly authorized. Follow the Git authorization and CI scope rules above.
