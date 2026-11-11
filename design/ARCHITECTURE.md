# NNOMM architecture

This file says how the Kotlin app is built. `AGENTS.md` has the fixed decisions, `design/DESIGN.md` says what to build, the mock in `design/mock/` is the agreed look and behaviour. Where this file is silent, follow the mock.

## Modules and packages

| Gradle module | Kind | Packages | Contents |
| --- | --- | --- | --- |
| `:core` | Kotlin/JVM library, **no Compose dependency** | `nnomm.core.*` | Domain model, the store and its actions, all rules (filters, search, problems, updates, versions per profile, pack diffs, broken-mod test), settings, downloads, disk and network work. |
| `:composeApp` | Compose Desktop app | `nnomm.app` (start, window, platform work), `nnomm.ui.*` | Theme, component kit, shell, screens, dialogs, render harness. |

`:composeApp` depends on `:core`. `:core` must never depend on Compose, so a Compose import there fails the build. File dialogs, drag and drop, the window and opening folders or links stay in `:composeApp`.

Packages in `:core`:

- `nnomm.core.model`: plain data: `CatalogMod`, `ModVersion`, `InstalledMod`, `Profile`, `Pack`, `Download`, `Toast`, `GameInfo` and so on.
- `nnomm.core.rules`: pure functions over the state: Browse list and counts, tag counts, search with match reasons, Library list, quick-filter counts, problems, updates, pack import and pack update diffs, command-search ranking (`fuzzy`), formatting (`ago`, `size`, `num` exactly as in `design/mock/src/data.js`).
- `nnomm.core.store`: `AppStore`: holds `StateFlow<AppState>` and has one function per player action (install, update, turn on/off, uninstall with Undo, profiles, packs, settings, broken-mod test, play). Actions return at once; slow work runs on `Dispatchers.IO` and updates the state when done.
- `nnomm.core.settings`: the settings list (the mock's `SETTINGS`), values, per-profile values, saving.
- `nnomm.core.io`: interfaces for everything that touches the disk, the network or the game (`Registry`, `MetaSource` for sizes and dates, `ReleaseNotesSource`, `Downloader`, `ModStore`, `GameFolder`, `GameLauncher`, `SettingsFile`, `Clock`), and their real implementations.
- `nnomm.core.demo`: the invented data of the mock (`design/mock/src/data.js`) as a seed for the store, with fake IO (downloads that tick like the mock's) and a fixed clock. Used by tests, the render harness and the demo mode of the app.

Packages in `:composeApp`:

- `nnomm.app`: `main`, the window (Nucleus decorated window, Tao backend, custom title bar), single instance, file associations, updater, platform helpers.
- `nnomm.ui.theme`: colour tokens for dark and light (from `styles.css`), Archivo type styles (wide, normal, narrow with tabular numbers), sizes, motion (reduce motion turns it off).
- `nnomm.ui.kit`: the component set on Compose foundation (no Material): buttons (primary, strong, upd, line, ghost, danger; sizes sm, normal, lg), icon button, switch, check box, radio, chip, filter option, segmented control, text field, select, menu, dialog with scrim, toast, badge, tag, progress bar, tooltip, mod icon tile, icons.
- `nnomm.ui.shell`: title bar, sidebar, Play box, detail-panel host, toasts layer, overlay layer, narrow layout.
- `nnomm.ui.browse`, `nnomm.ui.library`, `nnomm.ui.panel`, `nnomm.ui.settings`, `nnomm.ui.dialogs`, `nnomm.ui.palette`, `nnomm.ui.states` (first start, empty, loading, offline).
- `nnomm.ui.UiState`: view state that is not domain state: page, open panel and its tab, overlay, menu, Browse query, Library query and selection, Settings view. Plain Kotlin with Compose snapshot state.

## State and threads

- One `AppStore` per app. The UI collects `store.state` and calls store actions. Nothing slow runs on the UI thread: network, hashing, copying, unpacking and directory scans run on `Dispatchers.IO`.
- Domain state (catalog, installed mods, profiles, settings, downloads, toasts, game running, connection state, broken-mod test) lives in `AppState` in `:core`. View state lives in `UiState` in `:composeApp`.
- Rules are pure functions in `nnomm.core.rules`, tested without UI.
- Undo: an action that Undo can reverse puts a `Toast` with an Undo action into the state. Ctrl Z runs the newest Undo toast.

## Disk layout (real mode)

- Settings folder: `%APPDATA%\NNOMM` on Windows, `$XDG_CONFIG_HOME/NNOMM` (or `~/.config/NNOMM`) elsewhere. Override for tests and the sandbox: environment variable `NNOMM_HOME`. Never NOMM's folder.
- Mod store: `<settings folder>/mods/<mod id>/<version>/` keeps each version once. Local files: `<settings folder>/mods/local-<name>/<version>/`.
- Game folder: NNOMM puts each mod that is on in the profile in use into `BepInEx/plugins/<folder>`; a mod that is off is not in `plugins`. A file `BepInEx/plugins/.nnomm-state.json` records what NNOMM placed, so NNOMM never touches files it did not place or take over.
- First start takes over the mods already in the game folder as they are (on in `plugins`, off in NOMM's `disabledPlugins`), copies them into the store, and changes nothing in the game folder.
- NNOMM never deletes a player's file without a way back: before it removes anything from the game folder, a copy is in the store.

## Modes

- **Real**: real registry, the settings folder, the game folder from settings.
- **Demo**: `--demo` on the command line (or `NNOMM_DEMO=1`): the mock's data, fake downloads, no disk or network work. Used for the render harness and to feel the app on the desktop.
- Development never uses a real game folder: use `NNOMM_HOME=~/nnomm-sandbox/home` and the game folder `~/nnomm-sandbox/game` (with an empty `NuclearOption.exe`).

## Render harness

`tools/gradlew-locked :composeApp:renderShots` renders named states of the app (demo mode) to `composeApp/build/shots/<name>.png` without a window, with Compose's `ImageComposeScene`. A state is described by a `ShotSpec` (page, theme, size, view state, store actions). Names follow `design/mock/shots/` (for example `04-library`). Sizes: the window inside the mock pictures, 1328 x 792 for the wide pictures and 728 x 720 for the narrow ones. `tools/compare-shots` puts each render beside its mock picture in `composeApp/build/shots/compare/`.

## Builds on the development machine

Memory is tight: at most two Gradle builds at a time. Always run Gradle through `tools/gradlew-locked` (it waits for one of two build slots).
