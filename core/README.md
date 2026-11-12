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
and expires after 60 seconds if no process is observed. Deferred changes apply on
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
frequent spelling, and hide registry author/owner names unless shared as a real tag, and drop “mod”.

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

### Real IO safety API changes

- `GamePlan.knownIds` (default: active version ids) carries the full library identity
  set for safe reconciliation. `gamePlan(state)` supplies it automatically.
- `DownloadedFile` is now `AutoCloseable`; its optional cleanup action releases only
  downloader-owned staging. AppStore closes it after storing. Pack payloads use the
  default no-op cleanup. Callers of RealDownloader outside AppStore must close it.
- `GameInfo.folderMissing` (default false) distinguishes a missing configured game
  folder from an unknown game version. Settings help says "Game folder not found."
- `ModVersion.unavailableReason` and `modAction` expose unsupported RAR versions
  before downloading. TAR.GZ is supported through Commons Compress.
- `shutdown()` waits for the disk queue without the former ten-second cutoff.
- Runtime startup may adopt existing mods when the saved library is empty; it does
  not place mods, install the loader or create plugins during this adoption.

### Second IO safety round

`ModStore.protect(ids)` is synchronous and must run before removing references
needed by Undo. `GameFolder.applyProblems` optionally exposes a StateFlow of
per-mod placement messages; `recoverPending()` runs deferred disk recovery after
process detection permits it. `AppState.applyProblems` feeds problem badges and
`gameIoPending` tells the UI that startup game work is deferred.
`AppState.packSource` is a runtime-only handle copied into store state from
`AppIo.packSource`; pack dialogs use this handle, including configured storage and
shared client ownership. It is not persisted. See IO.md for current limitations.
`clearUnused(current: () -> AppState)` lets real storage read the latest references
again after acquiring its mutex; the snapshot overload remains for callers/tests.
Active download versions are included in retention. Pack preview local ids and
versions now derive from the bundled content, so consumers must use preview ids,
not the ids supplied by a pack author.


Acceptance settings additions: `GameFolder.setConsole(folder, enabled)` is a default
port. Real IO writes it at Play. `importPack`
accepts an optional destination id and permitted settings, so the UI can prepare a
private config snapshot before profile selection. `RealPacks.importSettings` never
writes into the game. `problemReport` builds the read-only report on an IO dispatcher.

### Third IO safety round

`GameFolder.applySucceeded` is false for deferred, refused or partial applies. Call
`apply`, then read this property and `applyProblems`; only a full success can cache
the requested plan. `AppStore.retryApply()` retries the current plan. `GameFolder.recoveryPending`
keeps deferred or failed journals visible after a recovery attempt. Placement
problems supply the `retryApply` action, and are hidden when the mod is off.

`SavedState` now includes the serializable `TroubleSession`. Its original enabled
set and temporary switches are saved before applying a guided-test round. Reopen
resumes the test; Stop restores the original switches. Runtime open holds an OS
file lock on `<home>/.lock` until shutdown. Direct `RealGameFolder` operations
also acquire this lock when no runtime owns it in this process.

Disk swaps accept an internal `beforeMutation` gate and recover only their own
journal on an IO failure. Internal crash hooks cover both rename windows,
ownership publication, journal deletion, and interrupted recovery cleanup.

### Final safety API additions

- `AppState.deferredModChange: Boolean = false` is persisted in state. Only an
  explicitly deferred mod action authorizes apply on game exit.
- `Registry.readSnapshot(): RegistrySnapshot` defaults to a live `read()` result
  for existing implementations. `RegistrySnapshot.savedAtMillis` is non-null
  for offline fallback; `mods` carries its list. `read()` remains available.
- `DownloadedFile` adds `origin: PayloadOrigin = UNKNOWN` and
  `verifiedHash: String? = null` before its final `cleanup` callback. Trailing
  cleanup lambdas remain valid; use named `cleanup` for positional callback
  arguments. Real downloads supply REGISTRY_DOWNLOAD and a validated SHA-256.
  Local, takeover and pack callers declare their own origin. Store put rechecks
  the artifact hash before recording verified provenance.
- No UI API changes are required. Offline presentation continues to use
  `CatalogConnection(OFFLINE, savedTime)`.

### Fifth IO review integration

No public model or port signatures changed. `RealModStore.shippedHashes` and
`matchingVersion`, and the disk helper `pendingTouches`, are internal helpers.
Takeover uses verified shipped hashes and stored-content identity matching.
Saved player extras now have a sibling `.version` file alongside `.generation`.
Play failure toasts use existing callback actions: Retry repeats Play; Turn off
switches off the failed entries in the active profile. Loader repair now shares
the disk queue with apply and other mutations. See IO.md for pending-journal
scope and conservative handling of unreadable game journals.

### Last core review API and behavior

- `RealGameFolder` accepts optional `backgroundScope` and `preferredVersions` arguments; the real runtime
  supplies its owned scope and active profile versions for verified takeover-artifact learning. Direct callers
  without it use the conservative same-version-only rule.
- `refreshStoreUsage()` requests the hash-based Clear estimate. Call it when
  Storage settings open. Routine disk actions update sizes and retain the last
  explicit estimate; the UI must present null as unknown, not zero.
- Catalog metadata (newest and oldest versions) loads in the background with one
  request at a time, regardless of visible cards. Existing metadata caches and
  request deduplication are shared with explicit `loadMetadata(ids)` calls.
- Play distinguishes apply errors from launcher errors. Its bulk Turn off action
  excludes folder-name collisions; the player chooses one conflicting mod through
  the existing per-mod problem actions.
- `removeEverything()` commits Library removal only after a successful game apply.
  Local and takeover copies remain available, switched off.
