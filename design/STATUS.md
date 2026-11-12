# NNOMM build status

The state of the Kotlin build of NNOMM against the agreed mock. Written for a reader who knows nothing of the work so far. `design/ARCHITECTURE.md` says how the app is built; `core/README.md` and `composeApp/COMPONENT-KIT.md` describe the two modules.

## Works now

- `:core`: domain model, rules, `AppStore` with every action of the mock, settings, IO interfaces, the mock's data as demo data. 63 tests pass. Two reviews found blockers (real registry data crashes some rules, packs from real files install nothing, a setting change writes to the game folder, no saving of profiles and library, broken-mod test can loop); a fix round follows after the real IO is merged.
- `:composeApp`: NNOMM identity (name, package, new Windows upgrade UUID, own settings folder, updater on scramd/NNOMM), Nucleus window with the own title bar, theme tokens for dark and light, Archivo variable font with its width axis, all mock icons, a first component kit, a headless render harness (`tools/gradlew-locked :composeApp:renderShots`, pictures in `composeApp/build/shots/`).
- Builds: `tools/gradlew-locked` (two build slots, no Gradle daemon). A full build with tests and renders takes under a minute.

## In progress

- Real disk, network and game IO in `:core` (registry, sizes and dates, release notes, downloads, mod store, game folder, takeover, BepInEx, launch, packs, saving).
- Kit completion: lazy lists and tables, cards, text truncation and highlights, control fixes from the kit reviews.
- Shell: overlay layer and keyboard, dialogs, menus, toasts, sidebar, Play box, detail panel, narrow layout, drop, demo mode, shots by mock address, README and app icon.

## Next

- Core fix round (both reviews), then the pages in parallel: Library, Browse, mod panel, Settings, dialogs with command search and the first-start, empty, loading and offline states.

## Deviations from the mock

None yet.

## Decided without the user

Choices that change the player's experience and that neither the mock nor `design/DESIGN.md` settles. Each says the other option, so it can be changed later.

- **Servers settings**: the first version keeps only "Show Servers in the sidebar" in the Servers category; the join helper, join choice, ping colours, refresh and favourites settings are left out because there is no server list. Other option: hide the whole category.
- **Servers page text**: title "Servers", empty state "Coming later" with "A list of dedicated servers comes in a later version of NNOMM." Other option: no Servers tab at all until the page exists.
- **Publisher name**: the installer's vendor is "NNOMM". Other option: the owner's name.
