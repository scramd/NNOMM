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
files return with their profile. Each placed entry records its profile owner, so
partial applies cannot misattribute player files on the next retry. Profile changes
are entry changes even when both profiles use the same version. Edited shipped
files also return with the same stored generation, including after switching to
another version and back. `player-edits/<profile>/<mod>/<version>/<generation-hash>`
keeps these edits separate from the current extra-file set. Explicit Repair uses shipped files again, while
keeping edited files aside with a notice giving their location. Unchanged shipped
files do not get copied to normal apply recovery.

## Recovery and startup

Home journals live in `transactions/`; entry swaps use `game-transactions/`.
Preparation is journaled before copying, including partial staging copies. Recovery
persists its direction before restoring targets or deleting staging. Repeated
recovery cannot mistake its own rollback for promotion. IO failure recovers only
the initiating call's journal. A recovered commit counts as committed, even if the
original operation encountered a cleanup error. Unfinished game journals prevent
publication of stale aggregate ownership.
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
identify profile and mod. Game recovery copies live in `.nnomm/recovery` in the game root, on the same drive
as the files they protect. Home indexes list these paths. External-store recovery
also stays on its target drive. Recovery failure becomes a notice and pending
state; each other journal is still considered. Recovery copies are never pruned
merely to meet a storage cap. Beyond 100 copies or 256 MiB a notice directs the
player to Clear in Settings. Current kept
player files are separate from those historical copies. Storage usage includes
recovery and player files. Clear deletes unreferenced payloads and recovery copies
whose bytes also exist elsewhere; it retains unique recovery files. This can leave
Clear with less space to free than total recovery usage.

Takeover copies existing mods without changing game files. Placement names,
including loose files, are retained. Private adoption records are scoped to the
canonical game folder, with the full canonical path checked inside the private
record. Older adoption files without that path require takeover again. A game ownership record without this home's adoption
record cannot authorize removal. Adoption merges versions and preserves existing
profile associations. Missing ownership after a previous apply requires explicit takeover; the notice
has a Take over action. Automatic takeover requires both no game record and no
private adoption. Repeated takeover skips entries this home owns. Disabled NOMM
mods retain placement-name hints but are not recorded as placed. Explicit takeover
creates a new ownership epoch, superseding a foreign home's identities; entry
commits carry that epoch, including across a crash before adoption publication.
Game exit alone does not force an apply. Play with mods on can install a missing loader.

## Store, packs and transfers

AppStore registers Undo protection before removing references. Clear runs in the
disk queue and rechecks retention under the store mutex. Recent puts protect even
unreferenced versions of installed ids. Undo checks that saved versions exist
before publishing restored records. Active queued downloads also protect their versions until publication. Recent
put protection is renewed when put finishes. AppStore renews replacement protection
at toast publication; nine seconds cover the entire eight-second Undo toast.

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

### Doorstop settings

Doorstop 4 reads `enabled` (default true) and `target_assembly` (default
`Doorstop.dll`) from the first case-insensitive `[General]` section. Sectionless
keys do not count. Headers with inner spaces or a trailing comment count; the
section name ends at the last `]`. A line starting with `[` without a closing
`]` is a key line, not a section boundary. Trimming follows Wine PROFILE_isspaceW:
U+0009–U+000D, U+001A and space.
A missing target DLL disables Doorstop itself. A present enabled value is on only
when it equals `true` (case-insensitive, after trimming that whitespace and removing
matched quotes). Other present values, including empty and numeric values, are off;
a missing key or a key without `=` defaults to true.
These rules were checked read-only against UnityDoorstop v4.0.0/v4.3.0
`src/windows/config.c`, v4.3.0 `entrypoint.c`, and Wine's `dlls/kernel32/profile.c`.
Native Windows behaviour remains a platform check.

The line editor splits only CRLF, LF and CR, preserving individual line endings.
Byte 0x85 in a comment is ordinary data. UTF-16 LE/BE files with a BOM are decoded
and re-encoded with the same BOM and byte order. Malformed UTF-16 (odd length or
unpaired surrogates) is refused before editing or replacing loader files; the
message asks the player to restore a valid copy. Other files use ISO-8859-1 as a
byte-preserving mapping, including UTF-8 and its optional BOM.

Play without mods changes the first `[General]` and, if present, the first
`[UnityDoorstop]` section to `enabled = false`. The first matching key, including a
bare key without `=`, is replaced. A missing key is added directly after its header.
With neither section, `[General]` is inserted at the top after any BOM. Other sections
and duplicate later sections stay unchanged. Decoding and construction of the
disabled bytes finish before any restore record is written. The original bytes are saved durably as base64 in
one record under `doorstop-restores/`, keyed by canonical game folder, before mutation.
Legacy single `doorstop-restore.json` byte/text records migrate once their recorded
folder exists and can be canonicalised. Until then the legacy path stays intact;
lookups compare the records' current canonical paths, including symlink aliases;
several folders can have pending restores without replacing each other.
Restoration waits for observed exit; startup while the game runs defers it. Startup
with the game closed, a failed edit, or a failed launch restores the exact bytes.
Legacy records containing only `text` still restore using the previous byte mapping.
Restore failure never stops startup or leaves the launcher marked running after
exit. The durable record stays pending; NNOMM retries every three seconds while
the game is closed, at startup and before the next Play. Play refuses to continue
if that retry fails for the selected game folder. A locked restore in another
folder does not block Play. One notice per unresolved failure with evidence that
the INI changed names the affected folder(s) and asks the player to close programs
using them. A missing game folder is never recreated: its
restore record stays pending,
without a write, until the folder returns. This also covers unmounted Steam
libraries. Play in another folder retains the missing folder's record. Restore
writes never create parent directories. If the current INI already equals the
saved bytes, the record is cleared without rewriting the INI. Thus a refused
write that left the original intact does not block normal Play.
If a refused edit also prevents reading the INI, retain the record silently until
a comparison is possible. Never claim that mods were turned off without evidence
of changed bytes. An edit failure retains its original cause and attaches any failed rollback as a
suppressed exception; Play uses loader access, lock or free-space guidance.

Loader Repair accepts only detected BepInEx 5.4.23.4. Unknown/different versions
are refused. A config is healthy if its first `[General]` has a nonempty `target_assembly`;
missing `enabled` is healthy and left alone. A present enabled value other than true in a healthy
file is set true unless a without-mods restore is pending for this game folder.
Without a pending restore, an unhealthy file is replaced with the shipped default,
with the exact current INI kept in transaction recovery under `.nnomm/recovery`
in the game folder and a result notice explaining that. Without a pending restore, a currently unreadable
file fails before any replacement, with instructions to close programs using it.
When Repair needs to change the INI, it places that replacement first in the
loader batch: a refused INI move fails before any loader file is promoted.
After the batch, Install and Repair compare every replacement target with its
staged source, including binaries and any INI change. Existing ownership is kept;
only verified placements add new ownership. This includes a previously unowned
INI changed by Repair; adopted binaries remain unowned. The ownership record is
written atomically after these checks, including for a partial batch, so later Repair or
Uninstall can handle the files already placed. Any mismatch, missing file or
unreadable target fails the operation with access/lock guidance before any success
or "Replaced the damaged mod loader settings" notice. The batch still has per-file
recovery, not global atomicity. Refusal before any promotion leaves ownership
unchanged. Ownership publication itself can fail; that failure is also reported.
While this folder has a pending restore, Repair skips the INI entirely
(even if missing, unhealthy or unreadable), repairs the other eligible loader
files, and asks the player to close programs using the folder and use Repair
again. It does not change the pending original bytes or claim to have repaired
the INI. Those original bytes remain in the private restore record until restore
succeeds; they are not a new game recovery copy. Repair and restore share a
per-canonical-game-folder lock covering the pending check through Repair's
commit, and restore's record read, INI write and record clear. An in-flight
restore finishes before Repair checks the record. Adopted binaries and other
unowned loader files remain unchanged.
The pinned archive SHA-256 is
`f881201b79da03e513bf97cdf39607ffa7f9e0d31a519b1aeeca8eb60f8309e7`.

Tests use temporary sandboxes and loopback HTTP only. Windows locking, Steam,
Proton process detection and actual game launches still require platform checks;
see `design/WINDOWS-CHECKS.md`.


The console switch is an explicit safe game-folder operation, serialized with other
game writes. It preserves unrelated BepInEx.cfg sections and keeps the previous file
under `console-backups/` before replacing it. Changes while the game runs wait until
Play applies the profile's selected value. Report building is read-only; the log tail
is bounded to 64 KiB and the UI shows the report before copying it.

## Third-round safety boundaries

`applySucceeded` reports full success; false means deferred, refused or partial,
with per-mod details in `applyProblems`. The applied plan starts unknown on every
AppStore open and is cached only after full success. Retry and Play repeat failed
plans. NNOMM launch and apply share AppStore's disk queue. A running-process check
runs again after staging immediately before mutations. An external game launch
can still race this final check; NNOMM cannot lock the game's own startup.

All changed entries and their descendants are checked for symbolic links and
Windows reparse/other entries or entries whose canonical target differs from their
canonical parent plus filename before capture or rename. Deletion walks without
following links. Native Windows junction detection still needs a Windows check.
A missing wanted descendant's payload blocks replacement of its parent subtree.
Missing wanted game folders are changes, even when the record says they are placed.

The home `.lock` file uses an OS lock, released by normal exit or process death.
A second runtime refuses to open the same home. A damaged home journal does not stop
startup; it is set aside with its files retained. Unknown game journals remain pending. Guided-test original switches
and session are durable before game changes and survive close or process death.

Remove everything removes only loader files recorded as installed by this home.
The Settings row and confirmation explain which adopted loader files and player
files remain. Retained mods can be turned on again; removed profile entries must
be added again. No unowned loader deletion is inferred.

## Final core safety findings

Game exit recovers authorized journals, then applies only when
`AppState.deferredModChange` is true. Mod actions refused during play or by IO set
and persist that flag; a successful apply clears it. An unknown in-memory applied
plan after restart does not authorize any game write. The next mod action or Play
resolves it. Exit never installs BepInEx. Turning on a mod, or Play with mods on,
can install it. Hidden profiles do not change this authorization rule. Explicit
takeover preserves an already completed first start.

Each stored version has durable provenance under `provenance/`: verified
registry download and artifact SHA-256, takeover, local file, pack, or unknown.
It survives uninstall and Clear. Clear compares actual payload hashes with the
recorded verified payload hashes before deleting a registry download. A takeover
can be cleared only if its entire payload matches a previously verified registry
payload recorded under `registry-payloads/`. Identity and a download URL alone
never authorize deletion. Legacy payloads without provenance stay. Local files
and pack payloads stay even when their IDs match the registry. An interrupted
replacement leaves conservative unknown provenance until put finishes.
Loose takeover files are not automatically re-identified after the registry loads;
they retain their local identity.

A saved official registry fallback carries its original read time through
`RegistrySnapshot`. If every source fails, the app reports offline and does not
rewrite the cache with the current time. A live source can still serve a merged
list if another source fails.

A refused link at one mod root is reported for that entry; independent safe
entries continue. Under the runtime home lock, startup removes abandoned
`staging/player-*`, `staging/edits-*`, and `staging/config-*` preparation copies
before recovery. Committed player files and journal-owned staging remain separate.

## Fifth IO review

Takeover snapshots are not proof that a file shipped with a mod. For TAKEOVER
payloads, only recorded verified registry payload hashes classify shipped files;
all other files are captured as player extras at the first removal/replacement.
Takeover checks both ownership epochs before trusting an applied record. A moved
game folder or lost record can recover stored identities by matching tree hashes;
a stale record disambiguates identical stored versions only when its bytes match.
Ambiguous matches are left unchanged. Duplicate wanted placement names, including
case aliases, are refused with entry problems instead of silently dropping a mod.
Takeover recovers pending game journals before reading ownership.

Saved extras record their source version as well as generation. If a newer
payload ships the same filename, its player copy is also saved in player-edits
for the original version/generation before promotion. Returning to that version
restores it, even after the current extra-file set changes.

Pending recovery is checked against entry paths and their private payload/player
file paths. A journal that touches shared ownership metadata can still block all
entries using that metadata. Malformed home journals are set aside in
`transactions/damaged-records`, with one notice. Malformed game journals
conservatively block game changes because their targets are unknown. No recovery copy is discarded to unblock an apply. Recovery messages distinguish
unavailable storage from merely running the game.

Play failures show entry-specific reasons with working Retry and Turn off toast
actions. Turn off disables the failed entries; the next Play uses the remaining
mods. Loader repair uses AppStore's serialized disk queue. Unknown placement
errors no longer claim another program necessarily holds a file open.

## Last core review

Takeover builds one operation-local store index, filters by file size and hashes
matching candidates once. Identical copies under one mod id prefer the recorded
version, otherwise the newest version. Different mod identities remain ambiguous.
For registry takeovers, the real runtime downloads the original verified artifact
once in its background scope to learn shipped file hashes without replacing the
player's snapshot. Offline or unknown takeovers restore unproven files only with
the same version. On a version change, those files are set aside under
`player-edits` with one notice. NOMM metadata is not carried to another version.
Legacy extras without a `.version` record use their saved generation to identify
the source version. If no unique version matches, they are copied to identified
unknown-version recovery storage before applying the requested version.

Malformed home journals are moved to `transactions/damaged-records` with one plain
notice. Malformed game journals continue to block game mutation because their
unknown targets cannot be safely ignored. Folder-name collisions report a problem
for both mods and leave those entries alone; unrelated entries still apply.
Remove all mods applies the proposed game plan before changing Library records.
A failed apply leaves Library records available for retry.

Routine disk operations refresh file sizes only. The hash-based Clear estimate is
computed only by `refreshStoreUsage` or Clear. The estimate remains the last explicit scan;
`storeUnusedMb = null` means no successful estimate is available. Clear still verifies duplicate bytes before
removing recoverable files.
Unproven takeover snapshots themselves supply the baseline for detecting later
player edits. An unchanged switch-off does not duplicate the whole mod as edits.
Those edits return with the original version and generation; a version change
keeps an identified recovery snapshot of the unproven original files.

## Closing fixes

Removal without a selected game folder updates only the Library. With a folder,
mod apply must succeed before publishing and saving the Library change. Loader
removal is a separate step: failure leaves the completed mod removal visible and
provides a loader-only Retry. Local, taken-over and unknown-origin stored mods
stay in the Library, off; verified registry entries leave every profile.

Unproven takeover recovery parks only captured player differences, never another
copy of the stored payload. Identical parks are reused. An empty capture produces
no recovery notice. Version-specific edited files still restore only to their
original version. Existing recovery files from older builds remain recoverable.

Visible metadata requests have a separate slot from the serial catalog scan.
Shipped-file learning is serial, waits for foreground downloads before starting,
and announces its background check once per takeover. An already running check
is not interrupted by a newly requested foreground download. Startup removes
abandoned `staging/learn-shipped-*` preparation only while holding the home lock.

Storage exposes `storeScanStatus` (IDLE, LOADING, READY, FAILED) and retains the
last successful estimate across refreshes and failures. `storeUsedBytes` supplies
KB formatting below one MB. Player search uses computed setting help, not demo
storage estimates. Play refuses missing folders before launch and keeps loader
installation failures distinct from mod placement and launch failures. Technical
exception messages remain in logs, not Play toasts.

## Final loader and metadata fixes

Repair changes existing loader files only when this home recorded ownership.
For detected BepInEx 5.4.23.4, it also repairs Doorstop as described above:
a missing enabled key alone is healthy, an empty or ineffective target configuration is
replaced with the shipped default and retained in recovery, and unreadable files
are left alone. Adopted loader binaries and other unowned files stay unchanged.
Other loader versions require their original installer; Repair offers
instructions without Retry. Loader removal reports actual removed files and
remaining loader files; an empty ownership record cannot claim removal.

Download-phase loader failures carry a distinct exception. Full-disk IO failures
point to free space on game and storage drives; connection/TLS failures point to
the connection. First-mod installation uses the same guidance as Play.
Visible metadata consumers register independently and dispose their registrations
when leaving composition. Multiple consumers of one mod share a counted identity;
queued requests for departed consumers are skipped, while in-flight work finishes.
