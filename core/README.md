# Core integration

This Kotlin/JVM module has no Compose dependency. JetBrains JDK 25 compiles it.
Run checks from the repository root with tools/gradlew-locked :core:test.

## UI entry point

Use nnomm.core.demo.demoStore() for the demo or AppStore(initialState, AppIo(...))
for real mode. Collect store.state: StateFlow<AppState>. Close the store when the
app exits. An optional coroutine scope supports virtual-time tests; production
defaults to a SupervisorJob on Dispatchers.IO.

The store exposes:

- Mods: install, update, updateAll, setOn, toggle, uninstall, changeVersion,
  repair, addLocal, paletteToggle, lookForUpdates.
- Profiles: useProfile, newProfile, copyProfile, renameProfile, deleteProfile,
  shareProfile.
- Packs: importPack, updatePack. Pass the selected pack data explicitly.
- Settings: setSetting, ovset, ovreset, ovclear. Pass an optional profile ID to
  setSetting; an inherited setting is locked until ovset.
- Downloads: cancelDownload, clearFinished.
- Game: play(withoutMods), gameClosed, repairLoader, takeOverExistingMods,
  completeFirstStart.
- Guided test: startBrokenModTest, answerBrokenModTest(stillThere),
  stopBrokenModTest, endBrokenModTest("keep" | "off" | "remove"),
  closeBrokenModTest.
- Registry: refreshRegistry.
- Toasts: dismissToast, runToast, undo.

runToast executes its optional callback, removes the toast, and returns an optional
UiAction. For example, UiAction("openMod", id, "new") opens What's new.
The UI owns navigation, panels, view filters, selection, dialogs, file writing,
opening folders or links, and keyboard wiring. No Compose function is stored in
the domain model. Undo callbacks retain the profile and affected records.

shareProfile returns pack data; the UI/platform layer chooses a file and writes
it. It includes local files only when requested. includeSettings marks the pack;
the real config-file payload and archive writer are future IO work.

## Pure functions

Browse: browseList(state, BrowseFilter, skip), tagCounts, browseFiltered,
browseSections. Filter skip names are have, type, tags. The three top sections
appear only for the default sort without filters.

Search: fuzzy, matchMod, palItems. Reasons contain name indices, plain tag or author
text, or a description snippet and inclusive highlight range. Palette items
contain grouped ranks, hints, primary UiAction, and optional Ctrl Enter alternative.
The UI dispatches action data to the matching store method or opens a page/dialog.
Servers are excluded from command search.

Library: libList(state, LibraryFilter), libCounts, sourceOf, problems, problemFixes,
hasUpdate, updates, verOf, onCount, usedIn, modAction. Problem badges with an empty
kind (old game version) do not count as Problems.

Packs/test: importRows, packUpdateRows, startTrouble, troubleRound, troubleAnswer,
restoreTrouble.

Formatting/data: ago, size (KB input), num, plural, compareVersions, cleanTags, cleanCatalogTags, hash,
daysAgo. Pack comparisons use the mock's numeric component comparison: suffixes do
not affect equality. Versions are strings so registry pre-release labels remain
intact. Release category is separate.

## IO contracts

All IO methods suspend, except the injected Clock and observable running state.

| Interface | Responsibility |
| --- | --- |
| Registry | Fetch list, read saved offline list and timestamp, save list |
| MetaSource | Cached HEAD-derived size/date for one version |
| ReleaseNotesSource | Cached newest release notes from Atom |
| Downloader | Transfer with progress; coroutine cancellation aborts it |
| ModStore | Put versions, reversible remove/restore, inspect local input |
| GameFolder | Inspect, apply managed profile, take over files, install/repair loader |
| GameLauncher | Launch with/without mods, expose running StateFlow |
| SettingsFile | Read/write general and per-profile manager settings |
| Clock | Milliseconds since epoch |

RegistryEntry and RegistryArtifact preserve the upstream NOMNOM wire fields,
including both download URL spellings, references, hashes, category and side.
Convert entries with toCatalogMod(). Configure the real decoder to ignore unknown
fields. Tags are cleaned during conversion; refreshRegistry also merges tag spelling and hides author tags across the entire list.

Disk mutations are processed in order, so an immediate Undo cannot race the
removal. Download transfers run concurrently with at most three slots. Actions
retain the profile they started in. Transfers fail independently. A running game
defers profile application until launch. The launcher observes real game exit;
gameClosed() is also available to the demo harness.

## Demo and mock decisions

- Demo.state(), Demo.io(), Demo.PACK_IMPORT, Demo.PACK_UPDATE, Demo.notes(id)
  provide fixed data, no network and no game/disk writes. The clock is fixed at
  2026-06-01 12:00 UTC. All relative ages match the mock.
- The bundled JSON is an exact extraction of data.js except the seven excluded
  server settings. Regenerate with node core/tools/regenerate-demo.cjs.
- Demo historical artifacts are invented, as are the mock's release histories.
  A previous version carries the first-release date so added/updated remain
  derived from version dates. Real registry metadata remains nullable.
- The mock's pack-import local-file checkbox has no effect: doimport filters
  out local entries unconditionally. This port exposes a local warning row,
  off by default, and preserves that omission during import. Local files can
  still be added through addLocal.
- The mock's Repair only clears the missing-file flag. The store downloads
  through the injected IO first, retaining the mock's success toast. On failure
  the file stays marked missing.
- The mock's version comparison ignores pre-release suffixes, and its update
  rule means “different from the newest release”, including a newer installed
  version. Both behaviours are preserved.
- The mock can label an imported version equal to the installed version as a
  conflict. importRows preserves this comparison.
- The broken-mod test has the mock's single-cause limitation. It cannot find a
  failure that requires two mods together. The mock fallback culprit is retained for its demo answer path. With an empty
  initial profile, found can be null.
- The mock's UI-only no-ops (copying, file save, danger actions and window/page
  rendering) have no fabricated effects in the store. Real archive/config export
  and durable installed/profile persistence still belong to the next IO layer.
- Mod-loader installation is called before applying a profile if needed.
  The mock supplies no loader-install toast; none is invented here.
- No Opus/Sol review was run: this implementation task explicitly says to work
  alone. The parent must arrange the project-required independent reviews.

Tests port the domain steps of selftest.js; page/panel/dialog rendering, selection,
keyboard and theme painting are UI responsibilities. Server and join steps are
excluded. Additional tests cover screenshot counts, search reasons, queue
concurrency/cancellation, toast expiry, registry mapping, tags and versions.
