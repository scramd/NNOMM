# NNOMM build status

The state of the Kotlin build of NNOMM against the agreed mock. Written for a reader who knows nothing of the work so far. `design/ARCHITECTURE.md` says how the app is built; `core/README.md` and `composeApp/COMPONENT-KIT.md` describe the two modules.

## Works now

- `:core`: domain model, rules, `AppStore` with every action of the mock, settings, demo data, real IO (registry, sizes and dates, release notes, downloads with hash check, mod store, game folder placement, first-start takeover, BepInEx install, launch through `steam://`, Play without mods through Doorstop, packs, saving). The core review findings are fixed (92 tests; a Library recompute with 300 mods takes about 1.5 ms).
- `:composeApp`: identity and icon, window with own title bar, theme, Archivo, icons, kit (lazy table, cards, highlights, focus rings), shell (sidebar, Play box, profiles, downloads, panel slide, toasts, dialogs layer, keyboard, narrow layout, file drop, Servers "Coming later"), and all pages: Library, Browse, mod panel, Settings, dialogs (command search, pack import and update, new profile, share, rename, confirm, licences, broken-mod test, first start).
- See it: `tools/gradlew-locked :composeApp:renderShots`, then `tools/compare-shots` (mock left, app right, in `composeApp/build/shots/compare/`). Run it: `tools/gradlew-locked :composeApp:createDistributable`, then `composeApp/build/compose/binaries/main/app/NNOMM/bin/NNOMM --demo`.

## In progress

- Whole-app reviews (Opus and Sol, two areas each) with pictures and the running app.
- IO fix round for the two real-IO reviews (copies instead of hard links, takeover keeps names, files that mods create are kept, crash-safe transactions, long downloads, stale records adopted, pack settings allow-list).

## Next

- Fix rounds for the whole-app reviews and the lead's polish list (pack import demo data, demo dates, plain Space on Linux, Settings width, wiring the Settings actions to the fixed core).
- Real mode: `createStore` uses the real runtime; first start with takeover; check against the sandbox game folder.
- Final reviews, status, workspace pin.

## Deviations from the mock

None yet.

## Decided without the user

Choices that change the player's experience and that neither the mock nor `design/DESIGN.md` settles. Each says the other option, so it can be changed later.

- **Servers settings**: the first version keeps only "Show Servers in the sidebar" in the Servers category; the join helper, join choice, ping colours, refresh and favourites settings are left out because there is no server list. Other option: hide the whole category.
- **Servers page text**: title "Servers", empty state "Coming later" with "A list of dedicated servers comes in a later version of NNOMM." Other option: no Servers tab at all until the page exists.
- **Servers settings lead text**: "The server list comes in a later version." instead of the mock's Steam sentence. Other option: no lead text.
- **Missing release metadata**: when NNOMM cannot read the game version, "Made for" marks compare with the newest game version that the registry names. Other option: no marks until the version is known.
- **Mod loader notice**: installing BepInEx shows "Installed the mod loader BepInEx 5.4.23.4. Mods need it to run." Other option: no notice (the mock has none, but its setting text promises one).
- **A missing older version** is shown as "Version X is not available" and never replaced by another download. Other option: none that is safe.
- **Publisher name**: the installer's vendor is "NNOMM". Other option: the owner's name.
