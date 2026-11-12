# Core review fixes

Implementation worktree: `/home/agent/.t3/worktrees/NNOMM/t3-7cdfaa6a-86bc31ab396a544b`.
Branch: `t3/fix-core-review-findings`. No push, tag, release, PR, issue or external
message. No Compose files changed. All game-folder tests use temporary sandboxes
with an empty NuclearOption.exe, never a real installation.

## Findings

1. **Fixed** — Catalog lookup is indexed and safe for missing ids; installed catalog records survive refresh/restart; prerelease-only entries have an installable artifact; trimmed real-manifest regression fixture added.
2. **Fixed** — No fabricated artifact fallback; exact registry artifacts or checked stored-version folders only; unavailable versions retain the selected version and show “Version X is not available”.
3. **Fixed** — Import rows classify actual installed versions, exact missing versions install, equal versions are same, unknown ids have unavailable rows, and full pack updates compare saved member versions.
4. **Fixed** — Settings save independently; preference/override changes do not prepare the game folder; mod/profile changes compare resolved plans; missing game folders are skipped.
5. **Already fixed, completed here** — Real IO supplied persistence; saved defaults now merge, installed catalog records persist, profile ids are UUIDs, and deleted profile data expires after its Undo window.
6. **Fixed** — Initial launcher state is observed; launch requests have a separate starting flag/timeout; real running state starts on process detection; exit applies deferred changes; loader/apply/launch share the disk ordering barrier.
7. **Fixed** — Indexed catalog, cached newest artifacts/reverse conflicts/game version, compiled comparison regex, bounded progress, stable observable row/count inputs, and a 300-mod median-budget benchmark.
8. **Fixed** — Dependency-closed near-half subsets strictly reduce suspects; no production fallback culprit; inseparable groups report inconclusive and restore; repeated starts and profile-changing/end paths preserve restoration.
9. **Already fixed, completed here** — Real IO supplied metadata/feed caches; store exposes lazy metadata/notes actions, independent load status, explicit unknown dates/sizes, and installed-artifact dates without the +38 trick.
10. **Already fixed, completed here** — Registry fallback game version existed; null artifact game versions now get no warning, and prerelease-only artifacts participate in compatibility filtering.
11. **Fixed** — Only the mod's own author/owner tags are hidden; case and known variants merge with the most frequent spelling retained; README corrected.
12. **Fixed** — Explicit GitHub owner/repository, fileName, release prefixes/assets, urls and imageHash survive conversion; fixture retains real wire values, including string-valued AI flags.
13. **Fixed** — Logger port records technical details; player failures use plain text; registry fetch/cache/save fail independently; cancellation propagates; cache-save failure keeps a ready catalog.
14. **Fixed** — Explicit failed download status, Retry, removal and clearing; offline pack import/update is blocked with “Needs a connection”.
15. **Fixed** — Applies coalesce to latest state; close drains with a bound and shutdown awaits it; queued awaits fail on closed queues; local/takeover writes serialize; takeover merges Library; canceled transfers do not commit installation; jobs clean up; injected scopes get owned child jobs.
16. **Fixed** — Failed removal restores Library state and removes stale success text; failed Undo does not claim restoration; removal/restore/apply errors keep recovery state and expose Retry.
17. **Fixed** — Transfers share a mod/version identity, while completion requests retain each profile/version; regression verifies one transfer enables both requesting profiles.
18. **Fixed / automatic purge not applicable** — Real IO already had retention/Undo protection; explicit Clear/usage/move actions are exposed and the required loader notice remains. IO.md deliberately requires explicit clearing rather than automatic deletion when Undo expires.
19. **Fixed** — Update checks use ordered versions and the active profile's prerelease preference; a newer installed prerelease is not offered a downgrade.
20. **Fixed** — Cycle-safe dependency closure includes extends and transitive references; install, problems and guided tests use it with selected/installed artifacts; reverse conflicts follow installed artifacts.
21. **Fixed** — `AppState.setting(key)` reads the profile in use; direct settings queries for other profiles remain explicit.
22. **Fixed** — Settings definitions have an independent resource; help derives game/loader/storage/app-version text from state and does not claim the app is newest or an existing loader was installed by NNOMM.
23. **Fixed** — Folder selection validates before updating both setting and GameInfo; discovery and takeover keep them aligned; demo uses the same sandbox path in both fields.
24. **Fixed** — Pack import/update preserve unrelated toasts and pending Undo callbacks.
25. **Fixed** — Download/action/pack kinds, test phases, import/end choices and Browse/Library sorts use enums; serialized wire values remain compatible.
26. **Fixed / existing real ports retained** — Finder, danger-zone/reset, registry scheduling, automatic update preference, usage/move/Clear, notes state and resolved GamePlan apply are wired; real pack read/write/follow and config swapping remain available. UI supplies file choosers, previews and danger confirmations.

## Nits

- **Fixed** — Core-only Spotless/ktfmt Kotlin style; no dense statement chains; model no longer imports rules.
- **Fixed** — Section ties have explicit ordering; Library names use a per-thread locale Collator.
- **Fixed** — New “all mods off” profiles use base/default versions.
- **Fixed** — Look for updates refreshes first and does not force a Show action.
- **Fixed** — Undo toast consumption is atomic; local-file text has a name fallback; duplicate local ids do not replace Library state; real local ids already include a content hash.
- **Fixed** — Demo state is cached; notes/takeover do not rebuild the model on each access.
- **Fixed** — Removed the self-constructed string assertion and production-culprit test dependency; added real-wire, multithreaded store, loader preparation/removal, deferred apply/retry, shared download, exact pack import/diff, list observation and store move regression tests. Existing IO tests cover persistence/config/recovery.

## Player-visible decisions

- A missing historical version is unavailable; it is never replaced with a different download under the requested label.
- A prerelease-only mod remains installable. Updates never downgrade a newer installed version.
- Pack import respects exact versions and equal-version rows; offline import/update explains that a connection is needed.
- An unsplittable diagnostic group restores the profile and explains that the test cannot split it; it does not accuse a guessed mod.
- Unknown metadata shows no invented value. Settings no longer claim an unknown loader's origin or that the app is newest.
- Store moves retain the old copy. Clear remains explicit. Loader removal only removes recorded manager-created paths and keeps recovery copies; existing unowned loader paths remain.
- Real mode obeys the chosen registry schedule while NNOMM is open; no machine service/startup helper is installed.

## UI and Windows handoff

Every public API change is in the final “API changes” section of README.md.
The current UI's concrete source break is AppModel.perform's String `"openMod"`
branch against the enum `UiAction.kind`; change it to `ActionKind.OPENMOD`.
No Compose compile was run and no Compose code was edited.

The parent must transfer these platform checks into design/WINDOWS-CHECKS.md;
that file was outside this assignment's edit limits:

- Steam/process launch observation and the separate starting timeout.
- Deferred apply on process exit, serialized loader preparation and launch.
- Folder discovery/selection and persisted folder consistency.
- Resolved game-plan application and config snapshot swaps, including ownership changes in one profile.
- Store moves/retention on Windows paths and locked files.
- Ownership-recorded loader removal and recovery copies; unowned loader files stay.

Installer, updater and custom frame code were not changed. Real Windows game
launch, Steam discovery and locked-file behaviour still require Windows. The
core benchmark is a warmed JVM regression budget, not a UI frame-rate measurement.
The guided test retains its single-cause assumption; combined-mod faults can be
inconclusive. No independent Opus/Sol review ran because this task says to work
alone; the parent must arrange those reviews before marking the part done.

## Validation

Final test counts, benchmark values and commit SHAs are recorded in the implementation
handoff. Commands: `tools/gradlew-locked :core:spotlessApply :core:test :core:spotlessCheck`
and `git diff --check`. No external network test was added; existing HTTP tests
bind only to loopback. The manifest fixture is a trimmed captured file, not a live fetch.

Final validation: **92 tests, 0 failures, 0 errors, 0 skipped**. Spotless checks
passed. Library with 300 installed mods: **median 1.550940 ms**, **p90 2.952549 ms**;
25 measured recomputes after 80 warm-ups, median failure threshold 5 ms. Counts,
update-date sorting and complete Library rows/problems are included. `git diff --check`
passed. The final validation included stored-version folder checks, semantic
prerelease ordering, stable row updates, shared transfers, failed removal/retry,
loader preparation/removal and store moves.

Implementation commits:

- `9cfac3e`: format core IO and add core-only Spotless.
- `0f47030`: core rules/store/IO fixes, typed API, fixtures and regression/benchmark tests.

The documentation handoff is the following commit on the same branch. No commits
were pushed. No independent review was requested from another agent.
