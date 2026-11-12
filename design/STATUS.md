# NNOMM build status

The state of the Kotlin build of NNOMM against the agreed mock. Written for a reader who knows nothing of the work so far. `design/ARCHITECTURE.md` says how the app is built; `core/README.md` and `composeApp/COMPONENT-KIT.md` describe the two modules.

## Works now

- `:core`: domain model, rules, `AppStore` with every action of the mock, settings, demo data, and the real IO (registry, sizes and dates, release notes, downloads with hash check, mod store, game folder placement, first-start takeover, BepInEx install, launch through `steam://`, Play without mods through Doorstop, packs, saving). 74 tests pass. Read-only check against the real registry: 278 mods, 1327 versions.
- `:composeApp`: NNOMM identity and icon, window with own title bar, theme, Archivo, icons, component kit (lazy table with selection, cards, grid, strip, highlighted text), shell (sidebar, Play box, profiles, downloads popover, panel host with slide, toasts, dialogs and menus layer, central keyboard, narrow layout, file drop and file arguments, Servers "Coming later"), shots of every mock address (`tools/gradlew-locked :composeApp:renderShots`, then `tools/compare-shots`; pictures in `composeApp/build/shots/compare/`, mock left, app right).
- Builds: `tools/gradlew-locked` (two build slots, no Gradle daemon).

## In progress

- Core fix round for the two core reviews (crashes on real data, packs, settings writing to the game folder, broken-mod test loop, performance, enums).
- Pages in parallel: Library, Browse, mod panel, Settings, dialogs with command search and first start.

## Next

- IO fix round for the two real-IO reviews (blockers: takeover moves and renames mods at the first apply, files that mods create are lost at a profile switch, hard links let a mod change the store copy, Repair does not reach the game copy, downloads over 120 s fail, a stale ownership record removes mods, crash windows in config swap, store replacement and loader repair, Clear races). Real mode stays on demo data until this is done.
- Real mode wiring (`createStore`), then whole-app reviews (Opus and Sol) of every page with pictures, and fixes.
- The workspace pin.

## Deviations from the mock

None yet.

## Decided without the user

Choices that change the player's experience and that neither the mock nor `design/DESIGN.md` settles. Each says the other option, so it can be changed later.

- **Servers settings**: the first version keeps only "Show Servers in the sidebar" in the Servers category; the join helper, join choice, ping colours, refresh and favourites settings are left out because there is no server list. Other option: hide the whole category.
- **Servers page text**: title "Servers", empty state "Coming later" with "A list of dedicated servers comes in a later version of NNOMM." Other option: no Servers tab at all until the page exists.
- **Publisher name**: the installer's vendor is "NNOMM". Other option: the owner's name.
