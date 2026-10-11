# Repository Agent Rules

## git limit

- strict to the commit [principle](https://www.conventionalcommits.org/en/v1.0.0/).
- every time you finish your code modification, generate the commit message as reference for me.

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

- CI generates Android release notes with git-cliff from commits since the last publicly published APK. Do not maintain `release-notes/android.md`; it is historical material and is not a release input.
- Use clear Conventional Commit descriptions, preferably Chinese user-facing wording. Notes use the commit description without AI translation or code analysis; do not include private source links, credentials, or unverified real-device claims in commit descriptions.
- `.github/cliff.toml` controls categories and Android production/build path filtering. Keep documentation, tests, and CI maintenance out of user-facing notes; a maintenance-only release gets a brief generic note.
- Private and public releases must use the same generated notes. The public snapshot contains `distribution/release-notes.md`, and the app's update dialog reads the public Release body. Generation must use published public releases, not the previous push or unpublished source tags, so skipped/cancelled/failed builds do not lose changes.
- If the maintainer explicitly requests skipping CI for an authorized commit/push, use `[skip ci]` for that operation only and do not manually dispatch a build unless subsequently requested. Existing Release text can be corrected without rebuilding an APK when that edit is explicitly authorized. Follow the Git authorization and CI scope rules above.
