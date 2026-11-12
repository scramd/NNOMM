# Second real IO safety round

Branch: `t3/safe-per-entry-game-apply`. Local commits only, authored as clankagent.
No push, tags, releases, GitHub activity, delegates or real game folder.

The main apply now operates on changed mod entries. It no longer stages BepInEx.
Both supplied probe suites are ported as safe assertions (15 Opus scenarios and
8 Sol scenarios), plus PE-validation and canonical-folder collision cases. Core tests use temporary roots
and loopback HTTP fixtures. The build and sandbox results are recorded below.

## Opus findings

1. Fixed: interrupted promotions roll forward; recovery displaces to recovery,
   never deletes a current game target. Entry journals carry the resulting
   ownership JSON so restart finishes publication after promotion.
2. Fixed for normal apply: unchanged logs/cache/config never enter staging or
   recovery. Only extras/edits are captured against recorded payload hashes.
   Recovery is counted, trimmed oldest first above 100 directories/256 MiB with
   notice, and duplicate recovery is eligible for Clear. Caveat: crash recovery
   conservatively retains the previous entry whole; unique recovery survives Clear.
3. Fixed: parents precede add-ons. Child snapshots happen before parent changes;
   missing child snapshots stop parent removal. Created empty ancestors are tracked.
4. Fixed: unrelated applies preserve edited shipped files in place; changed entries
   save those edits with a notice. Explicit Repair restores shipped bytes.
5. Fixed: saved extras replace the previous saved set. Deleted extras stay deleted.
6. Fixed: game exit applies only a changed plan. Play alone does not install the
   loader. Missing ownership after an earlier apply stops placement with a notice.
7. Fixed: missing wanted store payload leaves the placed entry and reports a mod
   problem. Other entries can proceed.
8. Fixed: unrelated symlinks/long paths are not copied. Changed entry failures are
   isolated. Length checks use final game paths; failed entries expose badges.
9. Fixed: inspect is read-only; home-only recovery is separate from gated game
   recovery/adoption. Startup during play succeeds and exposes gameIoPending.
10. Fixed for supplied races: Undo protection registers before reference removal,
    pending ACTIVE download versions are retained, Clear re-reads current state
    under the store mutex, and Undo verifies every restored version exists.
11. Fixed: takeover unions versions per id and keeps existing profile associations.
12a. Fixed: pack preview/materialization uses content-derived local identities,
     never the author's installed id/version; existing associations are retained.
12b. Fixed for newly cached official data: the official source has its own cache,
     preferred over third-party definitions after official-source failure. A legacy
     merged cache cannot reliably identify official provenance and is not guessed.
13. Fixed: manifest filenames are trimmed; failed validation cleans download staging.
14. Fixed: only the PackDialogs construction helper changed. It takes the runtime
    pack source from store state, sharing the configured store and HTTP client.
15. Fixed: no whole-tree apply, repeated wanted-payload hashing or unchanged-entry
    copying. Store hashes are recorded once per put; player files are hashed only
    when their entry changes.

Additional findings:
- Fixed: Loader Repair refuses unknown/different versions with a plain notice.
- Fixed: a loose DLL needs a DOS header plus PE signature at its declared offset.
- Partial: pack settings export is limited to cfg names in selected payloads;
  runtime-generated config ownership is not inferred and such settings may be omitted.
- Fixed: failed HEAD results expire after five minutes; AppStore requests can retry
  after that backoff. HTTP 4xx transfers are not retried.
- Fixed: unavailable transaction storage no longer discards its journal.
- Fixed: successful swaps remove empty staging parents. Recovery removes empty
  staging parents. No broad deletion of unknown/orphan staging content was added.
- Fixed: storage usage runs in the disk queue; temp roots in IO suites are cleaned.

## Sol findings

1. Fixed: post-promotion player writes survive; ownership publication is journaled.
2. Fixed: inspect performs no recovery; startup gates game journals by detection.
3. Fixed for supplied races: synchronous Undo protection, ACTIVE versions retained,
   reference recheck under store mutex, and payload verification before Undo state.
4. Fixed: unrelated saved state does not authorize stale ownership. Adoption is
   scoped to the canonical game path; a saved game record alone is insufficient.
5. Fixed: add-on player files are captured before parents and return with profiles.
6. Fixed: inherited pending Doorstop text restores on observed game exit.
7. Fixed: takeover preserves existing versions and per-profile associations.
8. Fixed: every redirect destination uses the HTTPS/loopback address policy.
9. Fixed: empty-library startup during play defers takeover instead of failing.
10. Fixed: filename trim and staging cleanup cover pre-transfer validation failures.

## Validation and limits

`tools/gradlew-locked :core:test`, `:composeApp:jvmJar` and
`:composeApp:createDistributable` passed during this round. The last validation
command/results are added after completion. Core spotless formatting was applied.

The built app repeated real-mode-check steps 1–11 on Xvfb :61. SHA-256 checks found
every baseline file's bytes in the game/private store after each mod action.
Startup, adoption, browsing, settings, restart and observed game exit made no
unrequested lasting game-file changes. Doorstop restoration matched byte-for-byte.
Screenshots/hash snapshots: `~/nnomm-sandbox/safety2`. See the appended section in
`design/notes/real-mode-check.md` for which build each check covered.

Remaining limits are explicit in IO.md: unique recovery is kept by Clear, crash
recovery can retain a full previous entry, generated config ownership is incomplete,
and legacy multi-target batches are not global all-or-nothing transactions.
Undo/recent-put protection has a nine-second window; ACTIVE downloads also protect
pending versions. Physical power loss, native Windows locks and real Steam/Proton
behaviour were not tested. Windows check rows were added.

Player-visible choices: per-mod placement badges/notices, deferred-startup notice
and readable pending state, notices giving kept-file locations, conservative Repair
refusal for unknown loaders, content-derived local pack identities, and bounded
historical recovery with notices before pruning. API additions are in README.md.

Final validation: `tools/gradlew-locked :core:spotlessApply :core:test
:composeApp:jvmJar` succeeded. XML totals: 144 tests, 0 failures, 0 errors,
0 skipped. The distributable succeeded in the preceding locked build. The test
count includes all 23 supplied probe scenarios and PE/canonical-folder validation cases.

Ownership hardening also checks the full canonical path inside private adoption
records. The filename hash alone grants no authority. An old adoption record
without that path needs takeover again before mod changes; files stay unchanged.
This conservative compatibility choice and its collision case are covered by core
tests, not a repeated full GUI sequence.

Commits before documentation: 083cb85 (implementation), 58b621a (regressions).
The canonical ownership follow-up and documentation commit are listed in the final
handoff.
