# NNOMM agent notes

NNOMM ("New Nuclear Option Mod Manager") is a fork of NOMM, Combat787's Nuclear Option Mod Manager (MIT). It started at upstream 5.5.0 (`3dacae5`) and is in sync with upstream 5.6.0 (`8786356`, merged 2026-11-12). It is almost a fresh project on the same stack: the UI, the interactions and the settings are redone, and NOMM's code is reused only where it fits.

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
- On the shared development machine, run Gradle only through `tools/gradlew-locked` (it limits parallel builds; memory is tight).
- Build and tests: `tools/gradlew-locked :core:test :composeApp:jvmJar :composeApp:jvmTest`. Renders of every mock picture: `tools/gradlew-locked :composeApp:renderShots`, then `tools/compare-shots`.
- Run: `tools/gradlew-locked :composeApp:createDistributable`, then `composeApp/build/compose/binaries/main/app/NNOMM/bin/NNOMM` (`--demo` for the mock's data). `:composeApp:run` uses `JAVA_HOME`, not the toolchain, so point `JAVA_HOME` at the JDK 25 for it.

## Syncing with upstream NOMM

NNOMM replaced most of NOMM's code, but it keeps NOMM's history and merges upstream so that the fork is never behind it and the upstream contributors stay credited.

1. `git fetch --no-tags https://github.com/Combat787/NOMM.git master`. Never fetch or push upstream tags: `.github/workflows/release.yaml` publishes a release for every pushed tag.
2. Look at the changes since the last synced commit (now `8786356`): `git log <last>..FETCH_HEAD` and `git diff <last> FETCH_HEAD`.
3. `git merge --no-ff --no-commit FETCH_HEAD`. Resolve for NNOMM: files that NNOMM removed stay removed, NNOMM keeps its own version and change list, and old NOMM code, the Material UI and the Steam helper never come back.
4. Port each upstream behaviour change by hand into NNOMM's own code, with a test, or record why it does not apply.
5. Commit the merge with a message that lists the upstream pull requests and what NNOMM did with each; push; update the synced commit in this file.

## Checks that need Windows

These cannot be checked on Linux and are checked later on Windows: the custom window frame, the installer, the self-updater, finding the game folder through Steam, and starting the real game with mods. Keep NOMM's working code paths for them where possible, and list every change to them in `design/WINDOWS-CHECKS.md`.
