# NNOMM

NNOMM (New Nuclear Option Mod Manager) is a desktop mod manager for Nuclear Option, built with Kotlin and Compose Desktop. It keeps installed mods in one store and lets each profile choose which mods and versions to use.

NNOMM is a fork of [NOMM by Combat787](https://github.com/Combat787/NOMM), starting at version 5.5.0. The interface, interactions and settings are being rebuilt against the agreed mock in `design/mock/`. The mod registry is [NOMNOM by KopterBuzz](https://github.com/KopterBuzz/NOMNOM). Both credits are retained. NNOMM and the reused NOMM code are licensed under the [MIT licence](LICENSE).

This version has no server list. The Servers page says “Coming later”. NNOMM never signs in to Steam or runs a Steam helper. It has its own settings directory and can be installed beside NOMM.

## Development

Gradle needs a JDK to start and downloads the JetBrains JDK 25 toolchain. Use the build wrapper to limit concurrent builds:

```sh
tools/gradlew-locked :composeApp:jvmJar
tools/gradlew-locked :composeApp:jvmTest
tools/gradlew-locked :composeApp:renderShots
tools/compare-shots
```

To run, set `JAVA_HOME` to JetBrains JDK 25:

```sh
tools/gradlew-locked :composeApp:run --args=--demo
```

`NNOMM_DEMO=1` also enables demo mode. `--kit` opens the component gallery. Until real IO is integrated, `createStore(args)` also uses the demo store in real mode. Page and dialog implementations are still in progress.

Never use a real game installation for development. Use `NNOMM_HOME` for a sandbox settings directory and a sandbox game folder containing an empty `NuclearOption.exe`.

See `design/DESIGN.md`, `design/ARCHITECTURE.md`, `composeApp/COMPONENT-KIT.md` and `core/README.md` for the design and APIs. Windows-only verification is tracked in `design/WINDOWS-CHECKS.md`.
