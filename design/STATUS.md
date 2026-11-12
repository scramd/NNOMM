# NNOMM build status

NNOMM has the agreed pages and real IO integration. It is a development build: use an isolated settings home and a sandbox with an empty `NuclearOption.exe`, never a real game installation. The last app and core passes are merged. The UI follow-ups below are implemented in the current checkout; independent Opus/Sol acceptance is still outstanding. Earlier review notes are historical evidence, not current acceptance.

## Works now

- Compose shell and component kit, dark/light themes, narrow layout, Browse, Library, mod details, Settings, profiles, command search, pack previews, sharing, first start and the broken-mod test. Servers says “Coming later”, as agreed.
- Real registry and cached data, verified downloads, per-version storage, managed placement, takeover, persistence, Undo, packs, loader preparation and Steam URI launch are wired. Earlier sandbox tests cover these paths; Windows behavior remains unverified.
- Fifth IO round: stored provenance and shipped hashes distinguish registry files from player files; saved extras have version sidecars; Play offers Retry/Turn off; loader repair shares the disk mutation queue. The later core pass learns verified takeover shipped hashes in the runtime background scope, keeps unproven edits with their original version, and recovers legacy extras conservatively when their version cannot be identified.
- Deferred mod changes persist, external game exit needs an authorized pending change, and Clear retains the last recoverable player copy. Recovery journals, home locking and guarded disk swaps are covered by core tests; see `core/IO.md` and the Windows checklist.
- Pack previews have separate unchecked Include switches for bundled payloads, readable file names and optional settings. Included payloads enter the Library off. Empty pack authors omit the byline and the warning says “Include it only if you trust the source of this pack.” Empty mod authors do not introduce stray punctuation in the broken-mod result.
- Linux saves resolve the extension before asking to replace a file. No or closing the question leaves the chooser open. Pack export first keeps a uniquely named `.previous-….nommpack` copy beside an existing destination; failed export keeps that copy too. A successful save names the recovery copy in the toast. Windows retains FileKit confirmation and uses the same export backup.
- Stop following clears the address/update marker, persists, keeps the profile's mods and cannot be undone by an in-flight check.
- Beta updater discovery uses the releases Atom feed and published metadata assets. Manual Look now bypasses Never for one check, offers a releases-page action in unsupported builds and never auto-installs. Startup checks stay quiet for unsupported/current/failure results.
- Browse tag chips stay whole; strip scrollbars appear on hover/scroll. Library size counts installed versions, while Storage counts all store/recovery use. No-download and no-update results, registry freshness and relative dates use player wording.
- Danger-zone row, button and confirmation say “Remove all mods”. Help explains that registry mods leave every profile, local/taken-over mods stay in the Library off, and only NNOMM-installed loader files are removed. The confirmation does not repeat that explanation. Problem-report help starts “A report with your mod list”. Missing-folder first start says “Choose your game folder”; the row still says “Game folder not found”.

## Remaining work and integration dependencies

- The merged core pass implements legacy-sidecar handling, verified takeover shipped hashes, one-pass takeover indexing, cheap routine size refresh, identical-payload version selection, distinct Play launcher/apply errors, Library removal after successful apply, background catalog dates, contributor/changelog filtering, damaged-home-journal isolation, acronym tags and per-entry folder collisions. These are implemented and have offline regressions, not independent final acceptance.
- Storage requests a Clear estimate when its category or matching search results become visible. Library no longer starts hash scans. Unknown estimates omit the number; initial real scans show “Calculating…”. Routine actions retain the last scan. The core has no public scan progress/completion signal: repeat scans display the previous estimate and an initial failed scan cannot be distinguished from a pending one (TODO(core)).
- Removal completion is one sentence: “Removed registry mods and turned off local and taken-over mods.” The confirmation states that it cannot be undone; the row explains ownership and retained files. `PlayerSettings.kt` still has a marked TODO(core) override for removal/report wording; shared definitions need the same names so command-search results agree.
- Independent Opus and Sol review must inspect the combined code, running pictures and mock pictures. This implementation agent works alone and does not supply those reviews.
- Offline imports can add selected bundled files before profile creation is rejected with Needs a connection. Local-only offline pack import remains a core follow-up.
- Loose takeover files are not later linked to registry identities automatically. The broken-mod test assumes one cause; interacting mods can be inconclusive.
- Final combined sandbox acceptance and all Windows checks remain. No release, tag or publication is authorized.

## Windows-only checks

`design/WINDOWS-CHECKS.md` is the detailed checklist: native frame, separate installer identity, packaged updater/restart, Steam library discovery, real launch with mods, locked DLLs, junctions/reparse points, cross-drive recovery, private config transitions, loader ownership/removal and delayed process detection. Check beta assets and manual updater feedback on packaged builds. Check repeated pack replacement, cancellation and recovery-copy permissions through FileKit too. Linux cannot certify these paths.

## Deviations from the mock

- Servers joining, the helper and server settings are excluded from the first version. The sidebar and empty page remain.
- Archivo shaping changes some wraps/baselines from Chromium. Browse reserves room for hover scrollbars, so strip spacing can be slightly taller. Existing picture differences are not claimed fixed by this pass.
- Real pack previews can have several bundled warnings and optional settings, making them taller than the single-file mock. Empty authors get a source-based warning rather than an empty trust phrase.
- Removal wording describes ownership and retained local files instead of promising a plain game. Stop following performs the action instead of the mock's no-op. The drop overlay uses native drops and omits simulation controls.
- Linux uses Swing choosers. Explicit replacement confirmation and retained pack copies add safety to the mock's Save action. Missing-folder first start gives the next action as its heading.

## Decisions without the user

- Keep a separate previous pack for every replacement beside the destination, without automatic deletion, including after export failure. Failure to copy prevents export. No new background process or startup helper is introduced.
- Preserve named-author warnings; use the source-of-pack sentence only when the author is blank. Empty author slots on cards/panels remain empty rather than inventing an identity.
- Keep unknown-provenance stored mods alongside local/taken-over mods during removal; a registry id does not prove that the bytes can be downloaded again.
- Unknown game versions compare with the newest registry version; unavailable exact mod versions are not silently substituted.
- Included bundled files stay off until the player enables them. Hiding profiles selects Default immediately; placement waits for a mod action or Play. Console preferences apply at Play.
- Servers settings retain sidebar visibility and a later-version explanation. BepInEx shows its installed version; installer vendor is NNOMM.

## Reproduce checks

Use JetBrains JDK 25 and `tools/gradlew-locked`: run `:core:test :composeApp:jvmTest`, then `:composeApp:renderShots` and `tools/compare-shots`. Renders and comparisons are in `composeApp/build/shots/` and its `compare/` directory. The earlier last-app pass uses Xvfb display `:74` at 1600 x 1000 in real mode, an isolated settings home and a temporary copy of the sandbox game. Its checked results and limits are recorded in `design/notes/LAST-APP-FIXES.md`.

The UI follow-up pass uses isolated Xvfb display `:75` in real mode with a temporary sandbox copy. See `design/notes/UI-CORE-FOLLOWUPS.md` for checks and limits. No Opus/Sol agents were launched because this implementation was explicitly assigned to work alone.
