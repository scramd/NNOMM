# Third real IO safety round

Worktree: `/home/agent/.t3/worktrees/NNOMM/t3-69801a61-a0491e1fce8966fb`.
Branch: `t3/real-io-safety-round3`. Local commits only; no push, tags or GitHub
activity. Implementation performed alone, as requested. `Packs.kt` and unrelated
Compose work were left alone. Compose changes are one Retry dispatcher and the
Remove everything confirmation sentence.

## Findings

Each line refers to the supplied review, rather than the probes' sometimes
different comment numbering.

- Opus N1: fixed. Game recovery stays on the game's drive under `.nnomm/recovery`, indexed in the home. Independent journal failures become notices and pending state.
- Opus N2: fixed. Profile changes reconcile entries and each entry retains its profile owner across partial apply; saved extras return before subsequent capture.
- Opus N3: fixed. Per-entry commits keep the old config owner. Config promotion journals its new owner together with the swap.
- Opus N4: fixed. Disabled entries retain name hints separately from placement. Missing wanted game entries are changes.
- Opus N5: fixed. Takeover skips entries owned by this home; automatic takeover requires no game record and no adoption. An empty installed list alone is insufficient.
- Opus N6: fixed. `applySucceeded` distinguishes full success; AppStore starts without a known applied plan, retries partial plans at apply/Play, and exposes Retry fixes. Off entries hide placement badges.
- Opus N7: fixed. Error recovery is limited to the initiating call's journal.
- Opus N8: fixed. Edited shipped files return with the same stored generation, including after another version's capture; versioned player-edits archives prevent that capture overwriting them. Repair restores shipped bytes and says the edits remain saved separately.
- Opus N9: fixed. Capacity never authorizes deleting unique bytes. Clear removes only copies with matching bytes elsewhere; capacity notice points to Clear.
- Opus N10: fixed. Ownership/takeover notices have a Take over callback. The missing-ownership probe now exercises the explicitly required takeover, rather than expecting repeated apply to take over automatically.
- Opus N11: fixed. Put renews protection at completion; replacement renews it at toast publication. Protection covers the entire Undo toast.
- Opus N12: fixed on Linux; native Windows verification remains. Changed entries are preflighted for links/reparse-other entries before capture or moves; deletion never follows links.
- Opus N13: fixed. Removing a parent removes descendant ownership too; unavailable wanted descendants block parent replacement before any removal.
- Opus N14: fixed for game staging and ordinary failed copies. Copying is journaled before it starts, interrupted staging is cleaned by recovery, cleanup precedes journal deletion, off badges disappear, and capacity scanning runs once per apply. A killed process can still leave redundant home preparation directories; these are retained conservatively.
- Opus two-instance note: fixed. Runtime holds an OS home lock for its entire lifetime; direct GameFolder clients acquire an operation lock. Second-process probe verifies refusal.
- Sol 1 / F1: fixed. Loose-file edits are captured using their original filename and can return through loose-file restoration.
- Sol 2 / F2: fixed. Preflight covers the full affected descendant subtree before parent mutation; missing child payload leaves the parent subtree unchanged.
- Sol 3 / F3: fixed. Routine apply cannot prune unique recovery bytes.
- Sol 4 / F4: fixed. Recovery journals its direction before cleanup; interrupted rollback never becomes a commit, and completed removals can resume metadata publication. Added the equivalent recovery-staged-deleted crash hook and the finer rename/publication hooks.
- Sol 5 / F5: fixed. New owned profiles receive an empty config unless entering ownership for the first time from shared config. Config ownership commits with its promotion; retry cannot recapture incoming config as outgoing.
- Sol 6 / F6: fixed. Successful recovery of a promoted operation reports committed success. Pending game journals stop stale aggregate ownership publication.
- Sol 7 / F7: fixed. Directory symlinks are refused before removal, and the entry/ownership stay consistent.
- Sol 8 / F8: fixed. Capturing an absent placed entry replaces its current saved set with empty; historical recovery is separate.
- Sol 9 / F9: fixed with an external-launch limit. Check running again after staging, immediately before mutation. NNOMM launch is serialized through the same AppStore disk queue; external startup can race the final check.
- Acceptance 1: fixed by unique-copy retention and Clear's duplicate check.
- Acceptance 2: fixed. Explicit takeover starts a new ownership epoch. Adopted identities supersede a foreign record; per-entry commits carry the epoch even if interrupted before adoption publication.
- Acceptance 3: fixed. Save the session and original switches before publishing temporary switches or applying game changes. Normal close and process-halt tests verify restart and restoration.
- Acceptance 7: fixed through explicit confirmation text. Loader files not installed by this home remain, including adopted BepInEx core, winhttp.dll and Doorstop config; player config/logs/unknown files/recovery also remain.

## Player-visible decisions

Unique recovery has no automatic expiry. Crossing the capacity threshold offers
Clear in Settings; Clear can free less than the total because unique bytes stay.
Profile switches also switch player extras for unchanged mod versions. Repair uses
shipped files and keeps edits separately, while ordinary off/on restores edits.
First-time config ownership keeps the shared config; a later new profile starts
empty. Missing ownership requires an explicit Take over action. Failed entries
have Retry and prevent a modded Play until reconciliation succeeds. Guided tests
resume after restart and Stop restores the original switches. Remove everything
now names the unowned loader files that stay.

## Validation

Both supplied suites were copied into core tests with safe assertions. Temporary
roots are owned/cleaned by the tests. The promoted-IOException probe accepts a
successful roll-forward. The config crash probe targets the config transaction,
rather than relying on the old transaction count. Additional acceptance tests
cover foreign-home takeover, normal-close guided-test restore, process halt,
home locking, independent broken-journal startup and first-apply crash ownership.

Final test counts, commits and sandbox evidence are recorded below after validation.

### Scope of the remaining acceptance items

- Acceptance 4: not changed here; pack following is in the parallel `Packs.kt` work.
- Acceptance 5: not changed here; pack config/settings application is in the parallel work.
- Acceptance 6: not changed here; launch/window/settings/updater/report UI behavior is outside this IO task.
- Acceptance 8: not changed here except Retry/Take over actions; broader wording is assigned to the parallel UI work.
- Acceptance 9: not changed here; page spacing, mock matching and STATUS corrections are outside this IO task.

### Sandbox evidence

Private Xvfb `:66`, 1360 x 860, with xfwm4. No display `:1` or real game folder.
Run directory: `~/nnomm-sandbox/safety3/run-nSS0`.
The game was copied from `~/nnomm-sandbox/game`; its executable is empty. The built
app used its own home, temporary directory and runtime directory. The final
sequence used a copied distributable under `run-nSS0/app`, protecting the running
app from rebuilds. Screenshots, logs, hash checks and crash journals remain there.

- First-start folder choice and explicit takeover: game-file hashes unchanged.
- Live NOMNOM: 278 catalog records, 275 visible; real BepInEx Configuration Manager
  18.4.1 download/install succeeded. Off removed its placement; on restored it.
- Uninstall removed its placement. Undo within the toast restored identical hashes
  (`undo-check.json`). The first manual Undo attempt missed the toast deadline;
  the repeated timed check passed.
- Created Safety player files with all mods off. Default's unique player-A.dat was
  saved. Turning the same mod on in the other profile did not inherit it. Adding
  player-B.dat there and returning to Default restored A and excluded B.
- `kill -9` during game-entry preparation was repeated against the final copied
  app (`final-crash-journal.json`, phase copying). Restart removed the pending
  game journal and partial game staging. A subsequent on action restored the
  identical 256 MiB crash-player.bin and Default's save. `final-crash-check.json`
  records the byte/hash and staging checks. The most recent unflushed switch was
  off after this abrupt exit; the saved switches and player bytes were intact.
- Play without mods used only an eight-second fake launcher. Doorstop was disabled
  during it and restored byte-for-byte (`launch-check.json`, screenshot 30).
- Added loopback port 9 as a registry, disabled NOMNOM, read again, then closed and
  restarted offline. Cached Browse/Library and profiles remained usable. The
  entire game-file hash map was identical across offline restart
  (`restart-check.json`). First start did not reappear.
- `final-file-check.json`: every original player-file hash remains in the game or
  home. Manager ownership JSON and its backup are excluded because they are
  replaceable metadata, not player bytes.
- One exploratory run was invalidated by rebuilding the packaged runtime while
  it was open, causing late Java security initialization failure. It was closed;
  affected network/launch/Undo/offline checks were repeated using the stable copy.
- The app, private display and window manager were stopped after checks. Sandbox
  files and the offline registry setting remain for reproduction.

The final Linux build additionally covers canonical Windows-junction refusal and
an external-store Clear regression: recovery bytes inside a store cannot count
themselves as a live duplicate, and usage does not count nested recovery twice.
Those final refinements, version-round-trip edited-file restoration and rename-window IO ownership were checked by core tests and the JVM JAR build; the GUI
sequence was not repeated after them. Native Windows junction behavior, drive
letters, file locking and a real game launch still need platform checks.

### Final checks and commits

Final source: `tools/gradlew-locked :core:spotlessApply :core:test :composeApp:jvmJar`
passed. 197 core tests passed, zero failures/errors: Review4ProbeTest 17,
EntryAttackTest 20 (including the extra rename-window IOException regression),
AcceptanceSafetyTest 8, and 152 existing tests. The supplied probes themselves
account for 36 scenarios; the extra regressions extend them. Compose JVM JAR
build passed; the distributable used for the full GUI sequence also built.
Unrelated formatting changes from spotless were reverted before committing.
Two overlapping exploratory test runs produced EOF/missing test-result errors;
those results were discarded. Subsequent validation ran serially and passed.

After the last core changes, the copied sandbox app received the newly built
core.jar and its two-profile player-file check passed again (screenshots 48–49).
A further attempt to trigger a latest-core crash did not observe a matching
transaction; it is not counted as crash evidence. The earlier two recorded SIGKILL
runs passed; the final code's crash behavior is also covered by the core matrices.
The final app and :66 display were closed. No helper or startup change remains.

Implementation commits, all authored as clankagent:

- `fbb6ca5` — durable recovery direction, same-drive history and indexed retention.
- `c0d6ef2` — per-entry profile/epoch ownership, subtree preflight and home locking.
- `5757bdf` — full-success plan tracking, Retry/Take over, durable guided tests and UI confirmation.
- `6733296` — versioned player edits, external-recovery last-copy protection, rename-window ownership and junction guard.

This report, IO/API documentation and Windows checklist are in the following
local documentation commit. Nothing was pushed. Independent Opus/Sol reviews were
not started because the task explicitly required working alone.
