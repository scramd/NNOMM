# Core integration

Kotlin/JVM domain and IO module with no Compose dependency. Use JetBrains JDK 25.
Build and test only through `tools/gradlew-locked :core:test`.
`tools/gradlew-locked :core:spotlessApply :core:spotlessCheck` formats/checks core Kotlin.
The formatter does not target `composeApp/`.

## Runtime and UI integration

Use `demoStore()` for invented data and fake IO. For real IO use
`RealRuntime.open()`, then await `runtime.shutdown()` before exiting. `AppStore`
owns a child coroutine job even with an injected scope. `close()` starts a bounded
queue drain; suspending `shutdown()` waits for it and reports flush failures.

Collect `store.state` for transient state and player actions. Prefer these stable
observable views for lists: `libraryRows` (complete `LibraryRow` values),
`libraryCounts`, `browseRows` (including each row's action), `browseTagCounts`, and
`sidebarNumbers` (on count, update count). Toasts, download progress and note-load
status do not invalidate their inputs. Filtered views use the pure `libraryView`,
`libList`, `libCounts`, `browseList`, `tagCounts` and `browseSections` functions;
recompute these when the query/filter or domain inputs change.

Actions return at once. Disk mutations are serialized; pending applies use the
latest state. Shared transfers are keyed by mod/version; each requesting profile
has its own completion. Progress changes at most ten times per second. Failed
rows expose `DownloadStatus.FAILED`, `retryDownload(id)` and `cancelDownload(id)`;
`clearFinished()` clears both failed and completed rows.

Only the launcher supplies `gameRunning`. `gameStarting` covers the launch request
and expires after 30 seconds if no process is observed. Deferred changes apply on
observed game exit and before launch. Loader preparation uses the same disk queue.
Preference changes save settings without applying game files.

## Catalog, packs and diagnostics

Catalog lookups and newest artifacts are indexed/precomputed. Installed records
survive registry removal and restart. Unknown ids have a safe “Not in the
registry” record. A prerelease-only mod has an installable artifact. Updates use
ordered versions and the profile's prerelease preference; a newer installed
version is never offered a downgrade as an Update.

An exact requested artifact or known installed copy must exist. No version is
fabricated from another download URL. `dependencyClosure` includes dependencies
and add-on parents, follows the selected artifact and stops cycles. Problems use
the installed profile version, including reverse incompatibilities.

Pack previews classify id/version pairs against current installed state, rather
than trusting a fixture's kind. Local payloads remain off and require explicit
selection through real pack IO and `addPreparedLocalMods`. A new profile uses the
pack's exact versions. Existing profiles keep equal versions and preserve newer
versions unless the player explicitly selects the pack version. Full pack updates
compare against `PackInfo.memberVersions`; legacy delta items remain readable.
Unrelated Undo toasts survive both import and update.

The broken-mod test uses dependency-closed subsets and strictly reduces suspects.
It never guesses a culprit. An inseparable group reports an inconclusive result
and restores the saved switches. The single-cause assumption remains: interacting
mods can produce an inconclusive result. No production culprit is predefined.

Unknown dates format as a dash (`ago(Int.MAX_VALUE)`); unknown sizes remain null.
`updOf` uses the installed artifact's actual date. `effectiveGameVersion` falls back
to the highest registry game version. Versions without a game version get no
“Made for null” warning. Tags merge case and known variants, retain the most
frequent spelling, and hide only each mod's own authors/owner and “mod”.

## IO and settings

See [IO.md](IO.md) for ownership and recovery rules. Game placement receives a
resolved `GamePlan`; it contains only folder, profile, active id/version pairs and
config ownership. First takeover copies files into the store and leaves the game
unchanged. Uninstall remains reversible. Expired Undo protection makes unused
versions eligible for explicit Clear; it does not automatically delete them.

`loadMetadata(ids)` lazily requests version metadata with bounded concurrency and
caches successful requests. `loadReleaseNotes(id)` exposes notes in state. Both
have independent `LoadStatus` maps; failure does not mark the registry offline.
The injected `Logger` records technical errors; player text does not expose them.

`findGameFolder` discovers through the real Steam-folder finder and selects only a
validated folder. Choosing `gamePath` validates before changing either settings
or `GameInfo`. `refreshStoreUsage`, `clearUnusedFiles`, and `moveStore` use the
store port. Moving copies to the chosen empty destination and keeps the original
recoverable. Reset preserves the selected game/store paths. `removeEverything`
is a danger-zone action: the UI must obtain confirmation before calling it. It
uninstalls managed mods and removes only loader files recorded as created by this
manager, keeping recovery copies. Existing unowned loader files are preserved.

The real runtime starts `startRegistrySchedule()`. “Only when I ask” disables
scheduled reads; “When NNOMM starts” reads once; the hourly option reads while
NNOMM is open. A successful refresh obeys the active profile's “Update by itself”
choice. `lookForUpdates()` reads the registry first. Pack reading/writing and
follow-address fetching remain in `RealRuntime.packs`/`PackSource`; file choosers
and preview confirmation belong in the UI. Per-profile BepInEx config snapshots
remain in the real game-folder implementation.

Settings definitions live in `settings.json`, independently from invented mod
records. `core/tools/regenerate-demo.cjs` regenerates both resources from the mock.
Use `settingHelp(definition, state)` for live loader, storage and version wording,
not the definition's mock help text. `AppState.setting(key)` reads the profile in
use; `SettingsValues.value(key, profile)` supports another profile explicitly.

The real-manifest fixture is a trimmed copy of the reviewer's captured registry.
Tests are offline except the existing loopback HTTP fixtures. No real game folder
is used. The 300-mod benchmark includes counts, sorted rows and row problems,
after warm-up, with a 5 ms median budget. See [REVIEW-FIXES.md](REVIEW-FIXES.md)
for the per-finding disposition, validation and remaining platform checks.

## API changes

Names remain stable except for the typed arguments/properties below. Use named
arguments when constructing models: optional state fields have been added.

| Old | New |
| --- | --- |
| `Download.kind: String` | `DownloadKind` (`INSTALL`, `UPDATE`, `REPAIR`, `REPAIRPROBLEM`) |
| Download completion inferred from percent alone | `Download.status: DownloadStatus`; `error` and `percent` remain available |
| `UiAction.kind: String` | `ActionKind`; e.g. `"openMod"` → `ActionKind.OPENMOD`. The String constructor remains as a validating adapter. |
| `TroubleSession.phase: String` | `TroublePhase` (`BASE`, `TEST`, `NOMOD`, `FOUND`, `INCONCLUSIVE`) |
| `importPack(pack, mode: String, keep)` and `importRows(..., mode: String)` | `mode: ImportMode = ImportMode.NEW`; `"add"` → `ImportMode.ADD` |
| `endBrokenModTest(choice: String)` | `TroubleChoice = TroubleChoice.OFF`; `KEEP` / `REMOVE` |
| `BrowseFilter.sort: String` | `BrowseSort` (`DOWNLOADS`, `UPDATED`, `NEWEST`, `NAME`) |
| `LibraryFilter.sort: String` | `LibrarySort` (`NAME`, `SIZE`, `UPD`, `SOURCE`, `ON`, `VER`) |
| `PackItem.kind`, `ImportRow.kind`, `PackUpdateRow.kind`: String | `PackKind` (`VER`, `LOCAL`, `ADD`, `SAME`, `UP`, `CONFLICT`, `OTHER`, `REM`, `CHG`, `NEWER`, `UNAVAILABLE`) |
| `GameFolder.apply(AppState, profileId)` only | Added `apply(GamePlan)`; the store uses it. The old overload remains as a compatibility adapter. Delegating fake implementations must override the new overload to intercept store applies. |
| `ModStore` original four methods | Added default `clearUnused`, `unusedBytes`, `usedBytes`, `moveTo` ports; real implementation supports them |
| `GameFolder` original methods | Added default `find(settings)` and `removeLoader(folder)` ports |
| `AppIo(...)` | Added optional `logger: Logger` at the end |
| `CatalogMod` metadata | Added `urls`, `imageHash`, `installable`; newest artifacts precomputed |
| `ModVersion` metadata | Added `fileName`, `releaseTagPrefix`, `releaseAssetName` |
| `RegistryEntry` mapping | Explicit GitHub owner/repository and release fields retained; string/boolean AI flag accepted as `JsonPrimitive?` |
| `PackInfo` membership ids only | Added `memberVersions: Map<String,String>`; `mods` retained |
| `BrowseRow(mod, match)` | Added optional `action: ModAction?` |
| `AppState` | Added `setting(key)`, artifact/index helpers, `gameStarting`, `releaseNotes`, `notesStatus`, `metadataStatus`, `storeUnusedMb` |
| `AppStore` | Added stable list/count flows, `shutdown`, `loadReleaseNotes`, `retryDownload`, `findGameFolder`, `resetSettings`, `removeEverything`, `clearUnusedFiles`, `refreshStoreUsage`, `moveStore`, `startRegistrySchedule`; `loadMetadata` accepts optional visible ids |
| `RealModStore(home, catalog)` | Store-path option added before the trailing catalog lambda; trailing-lambda and named catalog calls remain valid. Positional second-argument lambdas must use `catalog = ...`. |

JSON enum names preserve the previous lower/camel-case wire values. A concrete UI
compile adaptation is `composeApp/src/jvmMain/kotlin/nnomm/ui/AppModel.kt`,
`perform`: replace the `"openMod"` when branch with `ActionKind.OPENMOD` and import
`ActionKind`. No `composeApp` files were edited or built in this task.

`gameClosed()` remains callable but now reads the launcher's observed state; it
cannot force a running game to appear closed. Demo callers stop `DemoLauncher`
through its existing `stop()` method. `ModStore.hasVersion(id, version)` is an
additional suspending default port; the real store verifies a stored folder before
selecting a previously downloaded version.

`Problem.kind: String` → `ProblemKind` (`INFO`, `WARNING`, `DANGER`), and
`MatchReason.kind: String` → `MatchKind` (`NAME`, `TAG`, `AUTHOR`, `DESCRIPTION`).
`refreshRegistry()` now accepts optional `announce = false`; the explicit
`lookForUpdates()` action requests the count toast without a Show button.
