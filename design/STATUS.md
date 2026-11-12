# NNOMM build status

The state of the Kotlin build of NNOMM against the agreed mock. Written for a reader who knows nothing of the work so far. `design/ARCHITECTURE.md` says how the app is built; `core/README.md` and `composeApp/COMPONENT-KIT.md` describe the two modules.

## Works now

- `:core`: domain model, rules, `AppStore` with every action of the mock, settings, demo data, real IO (registry, sizes and dates, release notes, downloads with hash check, mod store, game folder placement, first-start takeover, BepInEx install, launch through `steam://`, Play without mods through Doorstop, packs, saving). The core review findings are fixed (92 tests; a Library recompute with 300 mods takes about 1.5 ms).
- `:composeApp`: identity and icon, window with own title bar, theme, Archivo, icons, kit (lazy table, cards, highlights, focus rings), shell (sidebar, Play box, profiles, downloads, panel slide, toasts, dialogs layer, keyboard, narrow layout, file drop, Servers "Coming later"), and all pages: Library, Browse, mod panel, Settings, dialogs (command search, pack import and update, new profile, share, rename, confirm, licences, broken-mod test, first start).
- See it: `tools/gradlew-locked :composeApp:renderShots`, then `tools/compare-shots` (mock left, app right, in `composeApp/build/shots/compare/`). Run it: `tools/gradlew-locked :composeApp:createDistributable`, then `composeApp/build/compose/binaries/main/app/NNOMM/bin/NNOMM --demo`.

## In progress

- Real mode works in the sandbox (first start with takeover after confirmation, real registry, install from GitHub, switches, profiles, Undo, restart, offline; see `design/notes/real-mode-check.md`). Second safety round of the real IO: the game-folder apply changes one mod entry at a time instead of the whole BepInEx folder, recovery never deletes a game path, game writes wait while the game runs, Clear and Undo cannot race, add-ons wait for their parent. **Until this round is reviewed, do not point NNOMM at a real game folder.**
- Polish: plain Space on every control with the Tao backend on Linux, "Open its settings file" (mod to BepInEx config mapping), small layout differences.

## Done in review rounds

- Whole-app reviews (Opus and Sol, two areas each) and their fix rounds: keyboard model (focused controls first, Space and `/` with Tao), import entry points, selection rules, panel motion and tabs, Play box and "Game is running", downloads popover, broken-mod test phases, pack import numbers, Settings actions wired to the core, command search, Share feedback, caret and focus in dialogs.

## Next

- Merge the IO safety round, then a final review round (whole app, and the real IO again), fixes, status and the workspace pin.

## Deviations from the mock

- Servers remains a "Coming later" page, as agreed for the first version; the mock contains a complete server list.
- Native Archivo text shaping still changes a few line wraps and baselines (notably the import warning); Compose and Chromium use different text layout engines.
- Browse strips reserve a minimum footer line so section headings no longer drift; the final two-card strip is about four pixels taller than the mock.
- Real pack imports add an unchecked mod-settings option when settings exist, so those dialogs can be taller than the mock's settings-free fixture.

## Decided without the user

Choices that change the player's experience and that neither the mock nor `design/DESIGN.md` settles. Each says the other option, so it can be changed later.

- **Servers settings**: the first version keeps only "Show Servers in the sidebar" in the Servers category; the join helper, join choice, ping colours, refresh and favourites settings are left out because there is no server list. Other option: hide the whole category.
- **Servers page text**: title "Servers", empty state "Coming later" with "A list of dedicated servers comes in a later version of NNOMM." Other option: no Servers tab at all until the page exists.
- **Servers settings lead text**: "The server list comes in a later version." instead of the mock's Steam sentence. Other option: no lead text.
- **Missing release metadata**: when NNOMM cannot read the game version, "Made for" marks compare with the newest game version that the registry names. Other option: no marks until the version is known.
- **Mod loader notice**: installing BepInEx shows "Installed the mod loader BepInEx 5.4.23.4. Mods need it to run." Other option: no notice (the mock has none, but its setting text promises one).
- **A missing older version** is shown as "Version X is not available" and never replaced by another download. Other option: none that is safe.
- **Publisher name**: the installer's vendor is "NNOMM". Other option: the owner's name.
