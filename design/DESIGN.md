# NNOMM design

NNOMM ("New Nuclear Option Mod Manager") is a desktop mod manager for the game Nuclear Option. It is a fork of NOMM by Combat787, on the same stack (Kotlin, Compose Desktop), with the UI, the interactions and the settings redone.

This file says what to build. The mock in `design/mock/` shows it: it is the agreed design, and the Kotlin app reproduces its look, its behaviour and its wording. Where this file and the mock disagree, the mock wins, except for the points under "Not in the first version".

## The mock

- `design/mock/nnomm-mock.html`: one self-contained page with invented data. Open it in a browser. Add `#fill=1` to the address for the full window size.
- `design/mock/shots/*.png`: pictures of every screen and state (1360 x 860, some at 760 px width).
- `design/mock/src/`: `data.js` (invented mods, servers, profiles and the complete settings list), `core.js` (state and rules), `views.js` (screens), `app.js` (dialogs, command search, actions, keyboard), `styles.css` (all sizes, colours and spacing), `selftest.js` (the expected result of each action).
- The row of buttons under the window is not part of the app. It switches states: first start, mod list loading, no connection, no mods yet, game is running, a dragged file, a modpack import, light theme.
- Start state through the address: `#fill=1&page=library&panel=mod:minimap`. Keys: `page` (browse, library, servers, settings), `theme` (dark, light), `mock` (normal, first, loading, offline, empty), `prof`, `q`, `view` (grid, list), `tags`, `lf` (all, on, off, upd, local, prob), `sel`, `cat`, `scope` (all, profile), `pid`, `sq`, `panel` (`mod:<id>`, `server:<id>`), `tab` (about, new, needs), `overlay` (palette, import, packupdate, newprofile, share, trouble, drop), `mode` (new, add), `join` (tempplus, temp, new, add), `pq`, `running=1`, `dl`, `trouble=1`.

## Direction

- Useful and quite minimal. Feature-based: the features and a UI that makes them usable are the point. No advertising look, no hero area.
- It must feel good, not only look good: every click answers at once, lists scroll smoothly with hundreds of rows, nothing blocks the window, focus and keyboard work everywhere, and motion is short and only answers an action.
- Plain language for players. Sentence case. A button says what happens. The same action has the same name everywhere.
- The manager never makes Steam show the player as playing Nuclear Option unless the game runs. It never signs in to Steam and has no Steam helper process.

## Colours and type

The four given colours are accents, never large backgrounds. Surfaces are neutral.

| Colour | Hex | Use |
| --- | --- | --- |
| Orange | `#e0552e` | Only two meanings: start the game (Play, Join) and an update |
| Olive | `#4b5320` | A mod or a switch is on; selected filter chips |
| Cream | `#e8e2c8` | Text on dark surfaces; strong buttons |
| Dark olive | `#353b17` | Text and strong buttons in the light theme |

All other tokens (surfaces, lines, the three text levels, warning yellow, danger red) are in `styles.css` under `.nn[data-theme="dark"]` and `.nn[data-theme="light"]`. Yellow means "worse than you asked for" (ping, another game version, a problem badge). Red is for destructive actions and a bad ping.

One typeface: Archivo (variable, SIL Open Font License, `design/mock/Archivo-OFL.txt`). Wide for titles (about 112%), normal for text, narrow (about 88%) with tabular numbers for values in tables. Base size 13 px. No all-capital labels, no monospace labels, no middle dots as separators.

Sizes that matter: title bar 38 px, sidebar 216 px (52 px icon rail in a narrow window), table rows 34 px (40 px "comfortable"), server rows 48 px, detail panel 400 px, controls 28 px high, corner radius 6 px (9 px for cards and menus).

## Structure

- Custom window frame with a title bar: name, command search field (`Ctrl K`), window buttons.
- Sidebar: Browse, Library (with the number of updates), Servers, the profiles, the download queue, the Play box (profile name, "17 of 25 mods on", Play with a menu), Settings.
- A detail panel slides in beside the list for a mod or a server. The list keeps its scroll position and filters. In a narrow window the panel lies over the list.
- One command search for mods, profiles, pages, settings and actions.

## Model

- **Installed mods** are stored once per version in NNOMM's own store. The Library lists every installed mod.
- **A profile** is a list of mods that are on, with the version of each. There is always a profile `Default`. A player can ignore profiles and can hide them (then NNOMM uses Default).
- **A modpack** is a profile that you can share as a file. An import shows a preview first and makes a new profile by default. A file inside a pack that is not from the registry is off by default and gets a warning. A pack can carry an address where its newer versions appear.
- **Uninstall** removes a mod from the computer, for all profiles, with Undo.
- NNOMM changes nothing in the game folder until the player installs, uninstalls or switches a mod. At first start it takes over the mods that are already in the game folder, as they are.
- While the game runs, changes apply at the next start.
- No confirm dialogs for things that Undo can reverse. Confirm only the actions in the danger zone.

## Screens

Read the mock for the details. The points below are the rules that are easy to miss.

**Browse.** Three rows on top only with the default sort (updated this week, new, for mods you have), then all mods. Filters: Show, Kind, Tags (any or all), Game version. A second click on a selected option clears it; a Clear button shows in a heading when something is selected; counts follow the other filters. Grid or list. Search looks in name, tags, author and description and shows why a mod matched. One button per mod that follows its state: Install, Repair, Update, Turn on, On.

**Library.** Dense table: select box, switch, icon, name with problem badges, version, source, size, release date. A mod that is off has a grey icon, a grey name in normal weight and grey values. Quick filters with counts. Shift-click and Ctrl-click select; a bar with actions for the selection. Problem badges open a menu of fixes. Keyboard: arrows, Space, Enter, Delete, Ctrl A. "Find the mod that breaks the game" is a guided test: first all mods off, then halves; mods stay with the mods they need; the profile is restored at the end.

**Mod panel.** Tabs About, What's new, Needs. Per-profile switches and versions. A version selector to go back.

**Settings.** Categories on the left, a search, a danger zone. "All profiles" and a selector for one profile: in the view of one profile, only the settings that can differ are shown, and a setting is locked until "Change for this profile". The complete list is `SETTINGS` in `data.js`.

**First start, empty, loading, offline.** Each has its own state with a next action. Offline shows the saved list with a notice.

**Downloads.** A queue in the sidebar. Installs never block the window. Toasts with Undo or "What's new".

## Not in the first version

- **Servers.** The sidebar has the Servers tab, but the page is empty with a "Coming later" note. The mock's Servers page, the join choices and the temporary server profiles are the design for later. Do not build them now, and do not install the join helper mod (NOSMR).
- **Player-hosted games** are never listed: that needs the Steam helper.
- **Trending** and a **modpack registry** do not exist.

## Data that exists

- **Registry (NOMNOM)**, `https://kopterbuzz.github.io/NOMNOM/manifest/manifest.json`, about 277 mods. Per mod: id, displayName, description, tags, authors, GitHub owner and repository, downloadCount, sometimes an image (about 40 mods), sometimes client or server. Per version ("artifact"): version, category (release, preRelease), type (plugin, addon), gameVersion, download address, hash, dependencies, incompatibilities, extends. Every add-on extends another mod.
- **Tags are untidy**: "mod" on most mods, mixed case, author names as tags. NNOMM merges case, drops "mod" and hides author tags.
- **Dates and sizes** are not in the registry. A HEAD request on a version's download address gives its size and date. Ask once per version and keep the result.
- **Release notes** come from `https://github.com/<owner>/<repo>/releases.atom` (the newest 10 releases, no account needed), loaded when the player opens "What's new" and kept. Do not use the GitHub API without a token: it allows 60 requests per hour.
- **Most mods have no image.** The fallback is a tile with initials on a colour derived from the mod id (see `icon()` in `core.js`).
- The game loads mods through BepInEx 5; mods are files in the game's `BepInEx/plugins` folder. Mod settings are BepInEx config files. Game Steam app ID: 2168680.
