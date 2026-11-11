# NNOMM agent notes

NNOMM ("New Nuclear Option Mod Manager") is a fork of NOMM, Combat787's Nuclear Option Mod Manager (MIT), started at upstream 5.5.0 (`3dacae5`). It is almost a fresh project on the same stack: the UI, the interactions and the settings are redone, and NOMM's code is reused only where it fits.

Read `design/DESIGN.md` first. The mock in `design/mock/` is the agreed design. The app reproduces its look, behaviour and wording.

## Fixed decisions

- Kotlin with Compose Desktop (JVM). Keep the packaging and the self-updater (Nucleus).
- No Material 3 and no MaterialKolor theme. NNOMM has its own small component set on Compose's foundation layer.
- Logic lives in code without a Compose dependency, so that a Compose import there fails the build. File dialogs and window handling stay in the UI.
- Own app identity, installed beside NOMM: the name NNOMM, its own package name, its own installer IDs (a new Windows upgrade UUID), its own settings folder, and updates from `scramd/NNOMM`. NNOMM never writes to NOMM's settings folder.
- No Steam sign-in and no Steam helper process, ever. Steam must show the player as playing only while the game runs. Remove the Steamworks worker and its dependency.
- The first version has no Servers page: the tab exists and says "Coming later" (see `design/DESIGN.md`, "Not in the first version").
- Keep the MIT licence and the credits to NOMM (Combat787) and to the NOMNOM registry (KopterBuzz).

## Limits

- Work on `master`, in small commits that say what changed and why. Push each commit. Commits are authored as `clankagent`.
- No releases and no tags: `.github/workflows/release.yaml` publishes a release for every pushed tag. Do not add GitHub workflows.
- No issues, pull requests, comments or posts anywhere, also not to `Combat787/NOMM` or `KopterBuzz/NOMNOM`.
- Never point the app at a real game installation during development. Use a sandbox folder that contains an empty file `NuclearOption.exe`.
- Do not give time estimates in reports.

## Review

Every part is reviewed by an Opus agent and a Sol agent before it counts as done: overall quality, and "stupid shit", meaning anything silly, contradictory, misleading, confusing or broken. A reviewer gets the code, the running result (pictures) and the matching mock pictures, not the author's conclusion.

A screen is done when it matches the mock in pictures at 1360 x 860 and at 760 px width, in both themes, when every interaction of that screen in the mock works, when the keyboard works, and when nothing blocks the window while files download or the registry loads.

## Build

- The Gradle toolchain needs JetBrains JDK 25 and downloads it. Gradle itself needs a JDK to start.
- Build: `./gradlew :composeApp:jvmJar`. Tests: `./gradlew :composeApp:jvmTest`. At `3dacae5`, two upstream tests in `TagFilterTest` fail because tests and code disagree.
- Run: `./gradlew :composeApp:run`. The run task uses `JAVA_HOME`, not the toolchain, so point `JAVA_HOME` at the JDK 25.

## Checks that need Windows

These cannot be checked on Linux and are checked later on Windows: the custom window frame, the installer, the self-updater, finding the game folder through Steam, and starting the real game with mods. Keep NOMM's working code paths for them where possible, and list every change to them in `design/WINDOWS-CHECKS.md`.
