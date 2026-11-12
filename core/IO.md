# Real IO and player-file safety

`RealRuntime.open()` owns the real ports. Await `shutdown()` before exit: AppStore
closes and drains its disk queue, flushes state, then the runtime stops its watchers
and HTTP client. No Compose dependency, Steam sign-in or helper is used.

## Private files

`NNOMM_HOME`, or the platform's NNOMM settings directory, contains state.json,
settings.json, registry.json, head.json and release-notes.json. It never uses NOMM's
settings directory. `mods/<id>/<version>` holds payloads, or the configured store
path does. `generations` invalidates same-version repairs. Copies placed in the
game are independent of the store.

JSON writes force a sibling temporary file before rename and attempt directory
fsync (unsupported directory channels on Windows are tolerated). Valid prior JSON
is kept as `.bak`; damaged primaries are preserved. Unreadable primary and backup
stop loading rather than silently reset state.

## Transactions and recovery

Replacement journals live in `transactions/`. All replacement copies are prepared
beside their targets under `.nnomm-staging`, outside plugins, before moving any
original. Each journal records all targets, staging paths, prior paths and whether
an original existed. An interrupted batch rolls back at startup before runtime
loading and before game inspection/apply. Runtime startup must finish before other
code accesses these folders. Successful replacements retain indexed recovery
copies with target, time and reason under `recovery/`.

Apply stages the complete BepInEx tree, including plugins, config and the ownership
record, then promotes that tree. Config snapshots are copied before the game tree
changes; an interrupted apply cannot snapshot partial game config on restart.
Loader replacement batches its files and ownership record. It preserves existing
Doorstop config and refuses a known different loader version. The pinned archive is
BepInEx_win_x64_5.4.23.4.zip, SHA-256
`f881201b79da03e513bf97cdf39607ffa7f9e0d31a519b1aeeca8eb60f8309e7`,
computed from the official GitHub release asset during this fix round.

Takeover copies entries verbatim, including inert archives and unknown loose
files, and writes only private adoption metadata. Repeating it after interruption
is safe. A plain game folder does not gain BepInEx/plugins during takeover. NOMM
metadata supplies identity even before registry loading; differing stored bytes
are left alone with a notice. Duplicate enabled/disabled identities keep the
enabled association and leave both originals. Store associations merge in AppStore.
An empty runtime library adopts existing files at startup. A stale game ownership
record alone cannot authorize removals from a fresh home.

Taken-over placement names stay stable, including loose files. New registry mods
use their id; add-ons go under their parent's `addons/<id>`, matching NOMM.
Unmanaged collisions emit notices and permit unrelated placements. New files inside
managed folders are saved under `player-files/<profile>/<mod>` and return with that
mod and profile. Edits to distributed payload files remain recoverable in recovery;
Repair replaces distributed bytes rather than restoring corrupt edits.

Store usage includes recovery and player files. Clear serializes with store writes
and AppStore's disk queue. It retains referenced/default versions, configured old
versions, and recent install/Undo payloads. Recovery trees can be cleared only after
nine seconds and only if every file also exists with identical bytes outside
recovery. The last copy is retained; Clear can therefore leave substantial recovery
data. Recovery indexes are retained. Full-tree apply snapshots favor file safety
over disk efficiency; large installations need space for staging and recovery.

## Validation and transfers

Roots can have symlinked ancestors; links below managed roots are refused.
Portable paths reject traversal, drive paths, reserved Windows names, trailing
spaces/dots, invalid characters, case aliases and paths of 240 characters or more.
ZIP uses ZipFile, 7z uses Commons Compress, and gzip-compressed TAR uses Commons
Compress. Archive magic selects the reader; loose DLL/NOBP imports also use their
manifest filename. Empty archives, links in TAR, duplicate archive paths and
archives beyond 100,000 entries/2 GiB are rejected. RAR versions show Unavailable
and a reason before transfer; no RAR dependency was added.

Downloads use the manifest filename, keep the socket timeout, override the overall
request deadline, retry three times and emit progress only on percentage changes.
Downloader-owned staging has an explicit close action; pack preview staging remains
owned by its preview. Supplied SHA-256 hashes are verified. Missing registry hashes
remain an upstream limitation.

Registry sources fail independently; official entries take precedence. Invalid
entries/artifacts are skipped. Text responses are limited to 16 MiB. Configured
external addresses must use HTTPS (loopback HTTP is allowed for tests). Metadata
uses its own queue; failures cache unknown size/date and do not toast. Atom tags
are percent-decoded; explicit download tags or declared release prefixes identify
versions without guessing across a monorepo.

Pack settings use an allow-list on write and read: console, prerelease, autoDeps,
warnOld, ownSettings and ownSettingsP. Paths, sources and launch arguments never
cross this boundary. Installed/catalog provenance overrides a pack's claimed kind;
local files require explicit selection and prepared locals stay off.

## Launch and boundaries

Missing saved game folders produce `GameInfo.folderMissing`, not startup failure.
Without-mods launch saves Doorstop's original text before disabling it. Restoration
waits until the game was seen and exited, or a subsequent runtime starts while the
game is absent. Shutdown while waiting leaves the restoration record. There is no
60-second restoration deadline. Process detection checks executable/arguments at
three-second intervals; Proton and Steam behavior still need real platform checks.

Tests use temp sandboxes and local HTTP fixtures. They cover review probes,
transaction boundary failures, real AppStore actions and runtime restart. Fault
injection simulates exceptions between disk steps, not physical power loss or
Windows file locking. Windows checks are in design/WINDOWS-CHECKS.md.
