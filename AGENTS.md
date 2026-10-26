# Repository agent rules

- This root project contains both Android and iOS. `shared` owns business logic and platform services; `composeApp` owns shared browser/settings/bookmark UI and native view adapters; `app` is the Android application; `iosApp` contains the iOS host source and Xcode project.
- Migration means replacing duplicated business logic with shared Kotlin and reusable UI with Compose commonMain. Moving the complete Swift implementation into `iosApp` is directory organization, not a completed KMP migration.
- Keep WKWebView integration in `composeApp/iosMain` and iOS persistence/Cookie services in `shared/iosMain`. The Xcode host owns launch, signing, assets, and native bridges. Swift player/subtitle-search and ad-block processing remain transitional until their responsibilities are replaced; delete obsolete Swift implementations after replacing their callers.
- Keep platform-specific host files inside their platform directories. Open `iosApp/SniffSubPlayer.xcodeproj` for iOS; its Gradle build phase runs from the repository root.
- Maintain both platforms in this root project. Do not recreate separate platform worktrees.
- Preserve existing working-tree changes. Do not stage, commit, push, create or switch branches/worktrees, merge, rebase, reset, tag, publish, or dispatch CI without operation-specific authorization.
- Provide a suggested Conventional Commit message after code changes.
- Do not write or add tests. Preserve existing tests unless the maintainer explicitly requests their removal; do not run tests without the maintainer's requested procedure.
- All Android compilation and packaging must run on GitHub CI, never locally. This includes Kotlin compilation, APK builds, and tasks that implicitly compile Android sources. CI dispatch still requires operation-specific authorization.
- Build iOS locally for now. Do not launch apps, simulators, or physical-device tests without the maintainer's requested procedure.
- After the migration is verified usable, the intended initial branch layout is `main` only; do not create `dev` yet. Branch deletion requires operation-specific authorization. Removal of the old Android worktree does not establish migration or runtime validation.
- Keep Android application ID, signing configuration, preference keys, and monotonically increasing release version codes intact.
- Do not introduce Flutter or Dart. Preserve the existing visual design when extracting shared Compose UI.
