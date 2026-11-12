# Real IO and player-file safety

`RealRuntime.open()` owns the real ports. Await `shutdown()` before exit. AppStore
drains its disk queue and flushes state before the runtime stops its watchers and
HTTP client. Core has no Compose dependency or Steam sign-in.

## Private files

`NNOMM_HOME`, or NNOMM's platform settings directory, contains state, settings,
registry, HEAD and release-note caches. NOMM's settings are not used. Payloads live
in the configured store, once per id/version. Generations invalidate repaired
payloads. `hashes/<id>/<version>.json` records payload file hashes at storage time.
Game copies are independent files.

JSON writes force a sibling temporary file before rename and attempt directory
fsync. Windows does not support directory channels. Valid previous JSON is kept
as `.bak`. Unreadable primary and backup stop loading rather than reset state.

## Apply model

Apply compares placed entries with the requested profile's id/version pairs and
store generations. It leaves unchanged entries, BepInEx logs, cache, config and
unknown entries alone. Profile-owned config transitions remain a separate snapshot
operation when the player explicitly switches config ownership/profile.

Each changed entry is copied into staging, then promoted by rename through its own
journal. Final swap staging is a hidden sibling of BepInEx, outside plugin scanning.
Portable path lengths are checked against the actual game destination. Removing
an entry renames it out first. Other entries can proceed if one entry fails.
`AppState.applyProblems` supplies mod problem messages and notices explain failures.
A missing wanted payload leaves its current placement intact. Add-ons wait for a
placed parent. Child files are captured before parents move, and NNOMM tracks and
removes empty ancestor folders it created.

Before removal/replacement, player-created and edited files are compared with the
stored payload hashes and saved under `player-files/<profile>/<mod>`. The saved set
mirrors the most recent removal: deleted extras are not reintroduced. Saved extra
files return with their profile. Explicit Repair uses shipped files again, while
keeping edited files aside with a notice giving their location. Unchanged shipped
files do not get copied to normal apply recovery.

## Recovery and startup

Home journals live in `transactions/`; entry swaps use `game-transactions/`.
Home-only journals recover during startup. Journals with game targets wait for the
game to close. `inspect()` reads only. `AppState.gameIoPending` exposes deferred
startup work, with a notice. A completed but empty library can open during play;
adoption waits until exit. First-start confirmation still controls initial takeover.

A promoted target rolls forward. Recovery never deletes an existing target to put
an older copy back: a conflicting target moves to recovery first. Previous copies
from interrupted promotions are also retained. Legacy multi-target batches can
recover to a mixture of promoted and unpromoted targets; this is not a global
all-or-nothing game transaction. Fault tests simulate process interruption, not
physical power loss or Windows locks.

Recovery indexes record target, time and reason. Normal player-file recovery paths
identify profile and mod. Recovery directories are trimmed oldest first beyond
100 copies or 256 MiB after apply, with a notice before removal. Current kept
player files are separate from those historical copies. Storage usage includes
recovery and player files. Clear deletes unreferenced payloads and recovery copies
whose bytes also exist elsewhere; it retains unique recovery files. This can leave
Clear with less space to free than total recovery usage.

Takeover copies existing mods without changing game files. Placement names,
including loose files, are retained. Private adoption records are scoped to the
canonical game folder, with the full canonical path checked inside the private
record. Older adoption files without that path require takeover again. A game ownership record without this home's adoption
record cannot authorize removal. Adoption merges versions and preserves existing
profile associations. Missing ownership after a previous apply requires takeover.
Game exit alone does not force an apply. Play alone does not install a loader.

## Store, packs and transfers

AppStore registers Undo protection before removing references. Clear runs in the
disk queue and rechecks retention under the store mutex. Recent puts protect even
unreferenced versions of installed ids. Undo checks that saved versions exist
before publishing restored records. Active queued downloads also protect their versions until publication. Recent
put/Undo protection lasts nine seconds.

Pack IO uses the runtime's store and HTTP client. Bundled local payload identities
come from a content hash; a selected pack cannot overwrite the id/version supplied
by its author. Existing installed associations are kept. Pack settings use an
allow-list; paths, sources and launch arguments do not travel with packs. Config export uses the selected mods' BepInPlugin GUIDs and the existing conservative
name mapping to find generated `.cfg` files. Unmatched files are omitted. Imported
settings are opt-in and go into a new profile's private snapshot before it is selected;
the normal config ownership transition places them in the game. Existing-profile
imports do not offer settings replacement. Following uses the same bounded ZIP/JSON
reader as file previews and removes its staging files after reading.

Portable names reject traversal, drive paths, reserved Windows names, trailing
spaces/dots, invalid characters, case aliases and symlinks below managed roots.
ZIP, 7z and gzip TAR are supported with extraction limits. Loose DLL imports need
a DOS header and a PE signature at its declared offset. This checks the container,
not whether its code can run. Unsupported RAR
artifacts remain unavailable with a reason.

Downloads trim manifest filenames and clean failed staging. Transfers retry up to
three times, except HTTP 4xx. HTTPS is required for external addresses; loopback
HTTP is permitted for offline fixtures. Redirect destinations are checked before
following them. Text responses are bounded to 16 MiB. Successful official registry
reads have a separate cache and take precedence over third-party entries if the
official source later fails. Failed HEAD metadata is retried after five minutes.

## Launch and platform limits

Without-mods launch saves Doorstop text durably before disabling it. Restoration
waits until observed exit, including when NNOMM reopens while the game runs.
Loader Repair accepts only detected BepInEx 5.4.23.4. Unknown/different versions
are refused. Existing Doorstop config is preserved. The pinned archive SHA-256 is
`f881201b79da03e513bf97cdf39607ffa7f9e0d31a519b1aeeca8eb60f8309e7`.

Tests use temporary sandboxes and loopback HTTP only. Windows locking, Steam,
Proton process detection and actual game launches still require platform checks;
see `design/WINDOWS-CHECKS.md`.


The console switch is an explicit safe game-folder operation, serialized with other
game writes. It preserves unrelated BepInEx.cfg sections and keeps the previous file
under `console-backups/` before replacing it. Changes while the game runs wait until
Play applies the profile's selected value. Report building is read-only; the log tail
is bounded to 64 KiB and the UI shows the report before copying it.
