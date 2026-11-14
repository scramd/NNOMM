# NNOMM status

The first version of NNOMM is built: every screen of the agreed mock except the Servers page, with real data and real game-folder work. It was built and checked on Linux in a sandbox game folder, and then run on Windows 11 against copies of a real game install: [Windows results](WINDOWS-RESULTS.md). On Windows it starts, finds the game, takes over mods, installs real mods, installs the mod loader, and the game loads those mods. One step is not proven: the start of the game through Steam itself, which needs the owner's Steam install (the steps are in the results). Use NNOMM on a real game folder only after that step, and with a copy of the folder kept the first time.

## What works

- **Screens:** the window with its own title bar, sidebar, Play box, command search (Ctrl K), light and dark theme, narrow layout; Browse (registry, filters, search with match reasons, grid and list, the three rows on top, the state button per mod); Library (table, quick filters, selection and bulk actions, problem badges with fixes, Undo, keyboard, Add from a file, file drop); the mod panel (About, Readme from the GitHub repository, What's new from the release feed, Needs, per-profile switches and versions); Settings (search, profile selector, per-profile values, danger zone); dialogs (command search, pack import and update, new profile, share, rename, licences, the broken-mod test); first start, empty, loading and offline states; the download queue. Servers says "Coming later".
- **Real data and files:** the NOMNOM registry with a saved copy for offline use, sizes and dates from HEAD requests, release notes from `releases.atom`, readmes from raw.githubusercontent.com, tag cleaning; verified downloads, one stored copy per version, per-profile versions, packs (share, import with preview, follow an address), the mod loader installed when the first mod is turned on, Play through Steam and Play without mods.
- **The player's files:** NNOMM changes nothing in the game folder until the player installs, uninstalls or switches a mod. The first start takes over existing mods as they are. Changes run one mod at a time with a journal, so a crash leaves the old or the new state, and startup finishes or rolls back. Files a mod or the player creates or edits are kept per mod and profile. Nothing is deleted without a copy; Clear keeps every copy that cannot be downloaded again. No Steam sign-in and no Steam helper: Steam shows the player as playing only while the game runs.
- **Checks:** 405 normal core tests (14 of them new from the Windows run), 11 separate crash tests and 24 UI tests. The Linux run before the Windows work passed; the 14 new tests and the changes were run on Windows only. On Windows the 24 UI tests pass and 385 of the 405 core tests that run there pass (4.4 minutes on a disk, crash tests not run); the other 20 fail because their fixtures assume Linux (see [Windows results](WINDOWS-RESULTS.md)). Also renders of every mock picture (`tools/gradlew-locked :composeApp:renderShots`, then `tools/compare-shots`; mock left, app right in `composeApp/build/shots/compare/`).

## Upstream NOMM

NNOMM started at upstream NOMM 5.5.0 and is merged with upstream 5.6.0 (`8786356`, merge `249c469`), without its tag. Its changes were carried over by hand where they apply: an update keeps a mod's disabled state, download progress and Cancel, and the Flatpak settings. NNOMM keeps its own version and changelog. How to sync next time: the "Syncing with upstream NOMM" section in `AGENTS.md`.

## How it was reviewed

Every part was reviewed by an Opus agent and a Sol agent, then fixed, several rounds deep: the core, the kit, each page, the real IO (five safety reviews), two acceptance checks against the owner's requirements, and closing checks of each later fix round. The review notes are in `design/notes/` and the core's `*FIXES*.md` and `REAL-IO-ROUND*.md` files.

The Windows run used another method: run the real app first, change code only where a check fails, one independent review per batch of fixes, then stop. It found nine defects that no test and no earlier review had found, among them an app that did not start. Findings that need two rare events together are listed under "Known limits" and are not worked on.

## Known limits

- Loader changes recheck each file before its rename; another program can still change it in the instant between the check and rename. See `core/IO.md`.

- On Windows these parts are still unchecked: a game start through Steam with start options, the automatic update of an installed build (no release exists), an install for all users, display scales other than 100 %, a second Steam library, settings and game on different drives, file drop, and the rare file cases of the check list. See [Windows results](WINDOWS-RESULTS.md).
- The rare cases of [Windows checks](WINDOWS-CHECKS.md) (Doorstop file encodings and unusual values, interrupted loader work followed by hand changes) are not run on Windows. They need two rare events together.
- A hostile 7z file can make the unpacker use much memory before NNOMM's size limit applies. Registry downloads are checked against the registry's hash first.
- A game version with a letter at its end (for example `0.35.0b`) shows as unknown; NNOMM then compares with the newest game version the registry names.
- NNOMM and NOMM both register the `.nommpack` file type. The program installed last owns it, and uninstalling that program leaves the file type without a program until the other one is repaired.
- Repair of the mod loader works only for BepInEx 5.4.23.4. A game folder with another version (5.4.23.5 was seen in a real install) is used as it is.
- When a mod change fails because of a locked file or a junction, a toast names the mod, but the Problems filter of the Library still shows 0.
- Arrow keys of the number pad do not move a selection.
- Offline pack import can add the selected bundled files and then refuse to make the profile without a connection.
- Loose files taken over from the game folder are not later matched to registry mods.
- The broken-mod test assumes one cause; two mods that only fail together give an inconclusive result.
- "Play without mods" and loader Repair edit `doorstop_config.ini` the way Doorstop 4 and Wine read it (first `[General]` section, the file's own encoding and line endings), put back the exact original bytes, retry a failed restore and never block NNOMM or Play because of it. Rules and checks: `core/IO.md`. How real Windows reads unusual files (UTF-8 byte-order mark, UTF-16, locked files) still needs the Windows checks.
- No release and no tag exist.

## Unchecked Linux packaging

- Flatpak uses `org.freedesktop.Platform` / `org.freedesktop.Sdk` 24.08.
  Nucleus 2.5.18's `FlatpakSettings.finishArgs` adds network, home and
  `xdg-data/Steam` access to its defaults, plus an explicit grant for Flatpak Steam's
  `~/.var/app/com.valvesoftware.Steam` library. Home access alone excludes
  `~/.var/app`. Other libraries outside these grants need a separate filesystem grant.
  Build and start the package with `flatpak-builder`; no release or tag is needed.
  Check registry/download access, game-folder selection and both native and Flatpak
  Steam libraries. Check process detection inside the sandbox: `ProcessHandle` may
  not see `NuclearOption.exe`, so the close-the-game guards and the Play-without-mods
  wait may not work. This package has not been built or run here.

## Closing small fixes

- A recorded recovery rollback accepts a missing previous copy as an already completed move;
  a later player deletion cannot leave recovery pending forever.
- Without the detection DLL, changed or foreign loader binaries (`winhttp.dll` or
  files under `BepInEx/core`) block Install and Play before launch. Foreign-file
  guidance does not blame the player. A present incomplete loader with changed
  binaries still allows mod placement and Play, with one warning per game folder
  per app session; NNOMM adds no loader files. An edited or foreign INI alone permits
  Install and stays untouched. Repair without the detection DLL offers Install only
  if no loader files remain except unchanged files this home owns; otherwise it
  refuses the unidentified loader. Complete unowned BepInEx 5.4.23.4 stays usable.
- Interrupted loader installs recover before Play, mod actions and Retry apply the
  changed/foreign binary guards. A failed recovery keeps recovery-specific guidance;
  the guards apply again once recovery finishes. A present loader that allows resume
  gets its missing files even beside extra unknown core files, which stay unchanged.
  A complete loader is never downloaded again after a mod recovery, so Play works
  offline; an install that recovery completes is announced like a normal install, and
  recovering an interrupted Repair of a complete loader announces nothing.
- Immediate download Retry replaces a cancelled transfer during its cleanup. Cancelled
  return handover deletes staging; tests await worker cleanup before deleting fixtures.
- Responses without Content-Length show an indeterminate download bar and “Downloading”.
  The bar uses the kit's loading animation and respects reduced motion.

## Differences from the mock

- No Servers page content, join helper or server settings in this version (agreed).
- The mod panel has a fourth tab, **Readme**, between About and What's new (owner's request, 2026-11-14): the GitHub readme, because most registry descriptions are short. The mock has no picture of it; `renderShots` draws `readme-*` with the demo readme. It draws headings, paragraphs with bold, italic, code and links, lists, quotes and GitHub alerts, code blocks, tables, rules, `<details>` sections (closed, as on GitHub) and pictures, including moving GIFs. Code and tables with more than three columns scroll sideways, with a fade and a scrollbar. Layout HTML is reduced to its content (only centring is kept), badges are left out (a badge with a link keeps the link as text), and a first heading that repeats the mod's name is not shown twice. Pictures load when they come into view and let go of their pixels when they leave; pictures over 25 million pixels (moving: 4 million) are not drawn. A GIF plays only while it is on screen, the window is not minimised and the game does not run; it keeps its own repeat count. Not drawn: SVG pictures (their alt text shows), videos (GitHub's bare video links and `<video>` become a "Watch the video" link), syntax colours, task lists, footnotes, `colspan`. Links inside the readme to its own sections open the GitHub page. Tests: `ReadmeParserTest`, `ReadmeParserVideoAndBadgeTest`, `ReadmeLoadTest`, `RealReadmesTest`. Measured on bram (software rendering, no GPU): one visible GIF costs about 50-60 % of one core and memory stays flat; with no GIF in view, or the window minimised, the app is idle.
- The sidebar, the Browse filters, the mod panel and the Settings section list can be resized by dragging their inner edge (owner's request, 2026-11-13); a double-click on the edge restores the mock's width. Widths are kept in `window.properties`. The page between them keeps at least 480 px, the Browse results 300 px; a pane gives way first when the window is too small. The narrow layouts keep their fixed widths.
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
