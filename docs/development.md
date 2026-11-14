# Developing NNOMM

This page is for people who build, test or change NNOMM. Players need only the [README](../README.md) and the [user guide](user-guide.md).

NNOMM is a Kotlin app with Compose Desktop (JVM). It is a fork of [NOMM](https://github.com/Combat787/NOMM) 5.5.0 by Combat787 and is in sync with upstream 5.6.0. The interface, the interactions and the settings are new; NOMM's code is used only where it fits.

## Requirements

- A JDK, version 17 or newer, to start Gradle (Gradle 9.8).
- The build downloads the JetBrains JDK 25 toolchain by itself. Packaging and `:composeApp:run` use that toolchain, whatever `JAVA_HOME` is.
- Git.

## Build and run

On Linux, run Gradle through `tools/gradlew-locked`. It is the normal Gradle wrapper with a limit of two parallel builds, for machines with little memory. On Windows use `gradlew.bat` with the same tasks.

```sh
# Build the app and start it with the demo data
tools/gradlew-locked :composeApp:createDistributable
composeApp/build/compose/binaries/main/app/NNOMM/bin/NNOMM --demo     # Linux
# composeApp\build\compose\binaries\main\app\NNOMM\NNOMM.exe --demo   # Windows

# Or start it from Gradle
tools/gradlew-locked :composeApp:run --args=--demo
```

Close a running NNOMM before `createDistributable`, or the task cannot replace its files.

### Start options

| Option | Effect |
| --- | --- |
| `--demo` or `NNOMM_DEMO=1` | Demo mode: the invented data of the design mock, fake downloads, no work on mods or the game folder. Window size and pane widths are still saved. |
| `--kit` | The component gallery (also in demo mode). |
| `NNOMM_HOME=<folder>` | Use this folder for settings and the mod store, in place of `%APPDATA%\NNOMM` or `$XDG_CONFIG_HOME/NNOMM` (`~/.config/NNOMM`). |
| `NNOMM_SANDBOX_LAUNCHER=<path of NuclearOption.exe>` | Play starts this file in place of Steam. Works only when `NNOMM_HOME` is below `nnomm-sandbox` in the user folder. |

Without `--demo`, `--kit` or `NNOMM_DEMO=1`, NNOMM runs in real mode: persistent settings, the real registry and real file operations. Set up a sandbox first (next section).

### Never use your real game folder

During development, use a sandbox:

```sh
mkdir -p ~/nnomm-sandbox/home ~/nnomm-sandbox/game
touch ~/nnomm-sandbox/game/NuclearOption.exe
NNOMM_HOME=~/nnomm-sandbox/home composeApp/build/compose/binaries/main/app/NNOMM/bin/NNOMM
```

Then choose `~/nnomm-sandbox/game` as the game folder. On Windows, set `NNOMM_HOME` to a folder outside `%APPDATA%` and use a copy of the game folder.

## Tests

```sh
# Normal tests: core and UI
tools/gradlew-locked --no-daemon :core:test :composeApp:jvmTest

# Exhaustive crash runs (slow)
tools/gradlew-locked --no-daemon :core:crashTests

# Format and check the core's Kotlin
tools/gradlew-locked :core:spotlessApply :core:spotlessCheck
```

Disk tests force writes to disk. A memory-backed `java.io.tmpdir` makes them faster.

### Screen renders

```sh
tools/gradlew-locked :composeApp:renderShots   # renders to composeApp/build/shots/
tools/compare-shots                            # mock left, app right, in composeApp/build/shots/compare/
```

`renderShots` draws named states of the app in demo mode without a window. The names follow the mock pictures in `design/mock/shots/`. The pictures in `docs/images/` come from these renders, except `browse.png`, which is a screenshot of the real app on Windows.

## Windows installer

```bat
gradlew.bat :composeApp:packageMsi -Pcompose.electronBuilder.nodePath=<full path of node.exe>
```

It needs Node.js 18 or newer with npm beside `node.exe`. After a version change, delete `composeApp/build/compose/binaries/main/msi` and run it with `--rerun-tasks`, or the old package is kept.

Releases: `.github/workflows/release.yaml` builds and publishes a release for **every pushed tag**. Do not push tags unless you want a release.

## How the code is organised

| Module | Contents |
| --- | --- |
| `:core` | Kotlin/JVM, **no Compose dependency** (a Compose import fails the build). Domain model, `AppStore` and its actions, all rules (filters, search, problems, updates, packs, the broken-mod test), settings, downloads, disk and network work. |
| `:composeApp` | Compose Desktop app: window, theme, NNOMM's own component kit (no Material), screens, dialogs, the render harness. |

The UI collects `store.state` and calls store actions. Actions return at once; slow work runs on `Dispatchers.IO`. Disk changes run one at a time with a journal, so a crash leaves the old or the new state.

## Documents

| Document | What it says |
| --- | --- |
| [`AGENTS.md`](../AGENTS.md) | Fixed decisions, limits, review rules, build notes, and how to merge upstream NOMM. |
| [`design/DESIGN.md`](../design/DESIGN.md) | What to build: direction, colours and type, structure, model, screens. |
| [`design/mock/`](../design/mock/) | The agreed design: a click-through HTML mock and pictures of every screen. The app reproduces its look, behaviour and wording. |
| [`design/ARCHITECTURE.md`](../design/ARCHITECTURE.md) | Modules, packages, state and threads, disk layout, modes, render harness. |
| [`design/STATUS.md`](../design/STATUS.md) | What works, known limits, differences from the mock, decisions made without the owner. |
| [`composeApp/COMPONENT-KIT.md`](../composeApp/COMPONENT-KIT.md) | The component kit. |
| [`core/README.md`](../core/README.md) | The core's API for the UI. |
| [`core/IO.md`](../core/IO.md) | File ownership, recovery and the rules for the game folder. |
| [`design/WINDOWS-CHECKS.md`](../design/WINDOWS-CHECKS.md), [`design/WINDOWS-RESULTS.md`](../design/WINDOWS-RESULTS.md) | Checks that need Windows, and the results of the first Windows run. |
| [`design/SHELL-INTEGRATION.md`](../design/SHELL-INTEGRATION.md) | How the UI shell works: navigation, the panel host, overlays, downloads, toasts, keyboard and file routing. |

## Data sources

- **Registry:** [NOMNOM](https://github.com/KopterBuzz/NOMNOM), `https://kopterbuzz.github.io/NOMNOM/manifest/manifest.json`.
- **Sizes and dates:** a HEAD request on each version's download address, asked once and kept.
- **Release notes:** `https://github.com/<owner>/<repo>/releases.atom`, no account needed. Do not use the GitHub API without a token (60 requests per hour).

## Licence

MIT, see [LICENSE](../LICENSE). Keep the credits to NOMM (Combat787) and the NOMNOM registry (KopterBuzz).
