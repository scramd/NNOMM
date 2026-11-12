# NNOMM status

The first version of NNOMM is built: every screen of the agreed mock except the Servers page, with real data and real game-folder work. It is checked on Linux in a sandbox game folder only. The parts that need Windows are listed in [Windows checks](WINDOWS-CHECKS.md) and are not checked yet. Until those checks pass, do not point NNOMM at a real game installation.

## What works

- **Screens:** the window with its own title bar, sidebar, Play box, command search (Ctrl K), light and dark theme, narrow layout; Browse (registry, filters, search with match reasons, grid and list, the three rows on top, the state button per mod); Library (table, quick filters, selection and bulk actions, problem badges with fixes, Undo, keyboard, Add from a file, file drop); the mod panel (About, What's new from the release feed, Needs, per-profile switches and versions); Settings (search, profile selector, per-profile values, danger zone); dialogs (command search, pack import and update, new profile, share, rename, licences, the broken-mod test); first start, empty, loading and offline states; the download queue. Servers says "Coming later".
- **Real data and files:** the NOMNOM registry with a saved copy for offline use, sizes and dates from HEAD requests, release notes from `releases.atom`, tag cleaning; verified downloads, one stored copy per version, per-profile versions, packs (share, import with preview, follow an address), the mod loader installed when the first mod is turned on, Play through Steam and Play without mods.
- **The player's files:** NNOMM changes nothing in the game folder until the player installs, uninstalls or switches a mod. The first start takes over existing mods as they are. Changes run one mod at a time with a journal, so a crash leaves the old or the new state, and startup finishes or rolls back. Files a mod or the player creates or edits are kept per mod and profile. Nothing is deleted without a copy; Clear keeps every copy that cannot be downloaded again. No Steam sign-in and no Steam helper: Steam shows the player as playing only while the game runs.
- **Checks:** 286 core tests and 26 UI tests; renders of every mock picture (`tools/gradlew-locked :composeApp:renderShots`, then `tools/compare-shots`; mock left, app right in `composeApp/build/shots/compare/`).

## How it was reviewed

Every part was reviewed by an Opus agent and a Sol agent, then fixed, several rounds deep: the core, the kit, each page, the real IO (five safety reviews), two acceptance checks against the owner's requirements, and closing checks of each later fix round. The review notes are in `design/notes/` and the core's `*FIXES*.md` and `REAL-IO-ROUND*.md` files.

## Known limits

- Windows-only parts are unchecked: native window frame, installer beside NOMM, self-updater, Steam folder search, a real game start with mods, locked files, junctions, home and game on different drives. See [Windows checks](WINDOWS-CHECKS.md).
- Offline pack import can add the selected bundled files and then refuse to make the profile without a connection.
- Loose files taken over from the game folder are not later matched to registry mods.
- The broken-mod test assumes one cause; two mods that only fail together give an inconclusive result.
- "Play without mods" and loader Repair edit `doorstop_config.ini`. Their handling of unusual files (a `[UnityDoorstop]` section before `[General]`, UTF-16 files, headers with comments) is being reworked after the last review; until that is merged, these two actions are not safe on such files.
- No release and no tag exist.

## Differences from the mock

- No Servers page content, join helper or server settings in this version (agreed).
- Font shaping and line wrapping differ slightly from Chromium in some pictures; Browse strips leave room for their scrollbar.
- Safety texts are longer than the mock where the real behaviour needs saying: ownership and recovery notices, "Remove all mods" (local and taken-over mods stay in the Library, turned off; only loader files NNOMM installed are removed), storage numbers.
- Pack previews can show several bundled-file warnings and the pack's mod settings switch.
- Linux uses Swing file choosers; saving over a file asks first and keeps the previous pack.
- The drop layer uses real file drops, without the mock's simulation buttons.

## Decided without the owner

Each can be changed later.

- Servers settings keep only "Show Servers in the sidebar"; the Servers page says "A list of dedicated servers comes in a later version of NNOMM." Other option: no Servers tab until the page exists.
- The installer's publisher is "NNOMM".
- When the game version cannot be read, "Made for" marks compare with the newest game version the registry names.
- Installing the mod loader shows "Installed the mod loader BepInEx 5.4.23.4. Mods need it to run."
- An older version that the registry no longer has is shown as not available; it is never replaced by another download.
- "Remove all mods" keeps local, taken-over and unknown-origin mods in the Library, turned off, and removes only loader files NNOMM installed.
- A replaced pack file is kept beside the new one, without automatic deletion.
- Bundled files in a pack start switched off.
- Hiding profiles selects Default at once; the game folder follows at the next mod action or Play.
- First start without a found game says "Choose your game folder".
- Tags are shown in sentence case; acronyms and all-capital tags keep their case.
