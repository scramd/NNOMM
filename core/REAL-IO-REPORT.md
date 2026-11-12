# Real IO implementation handoff

Worktree: `/home/agent/.t3/worktrees/NNOMM/t3-04b80b35-109a7494ca638ce1`
Branch: `t3/implement-core-real-io`. Work stays on the assigned branch; no push,
tag, release or GitHub activity. No composeApp changes. No independent reviewers
were started because this assignment says to work alone.

## Entry points

- `RealRuntime.open(home, version, fakeLauncher, notice)` loads durable state,
  caches and real ports. Wire `notice` to a visible notification. Keep the runtime
  alive and call suspending `shutdown()` to flush before exit.
- `runtime.store.refreshRegistry()` refreshes configured `sources` (newline,
  comma or semicolon separated) and progressively fills newest-release metadata.
  Historical metadata uses `io.meta.metadata(mod, version)` explicitly.
- `io.releaseNotes.releases(mod)` fetches Atom only on request. A repository feed
  is shared safely by several mods; mapping uses complete release tags.
- `runtime.mods.unusedBytes(state)` / `clearUnused(state)` implement storage Clear.
  Referenced versions, requested old versions, Undo grace and recovery copies
  are protected. Uninstall is logical; payloads remain until explicit clearing.
- `runtime.packs.read(path)` returns a closable preview; write exports NNOMM/1
  ZIPs with optional local files/config/manager settings. Legacy mod lists and
  NOMM packs are read. `selectedLocals` plus `store.addPreparedLocalMods` makes
  explicitly selected local files available, OFF. `checkPackUpdates` updates
  followed profiles' `nextVersion`; a follow address serves NNOMM/1 JSON.

## Validation

`tools/gradlew-locked :core:test`: **74 tests, zero failures or errors**, including
11 real-IO tests. All unit HTTP requests use a loopback in-process HTTP server.
Coverage includes JSON backup recovery, private paths, redirects, HEAD cache,
transfer/hash failure, cancellation cleanup, ZIP traversal, 7z extraction,
registry/local hash recognition, retention with a pinned profile, takeover,
managed-file removal/reapplication, unmanaged-file preservation, running-game
deferral, fake launch/vanilla ini restoration, Atom tag mapping and pack formats.
The remaining tests exercise existing domain and demo behavior.

Manual real-registry check:
`NNOMM_HOME=$HOME/nnomm-sandbox/home tools/gradlew-locked :core:test :core:realRegistryCheck -Ptakeover`

Observed 2026-11-12: **278 mods, 1327 versions, 1143 non-null hashes, 277 mods
with GitHub repository links (explicit or inferred), 40 images**. The saved
registry round-tripped exactly. The GET was read-only against the remote service.
Sandbox takeover: **3 mods, 2 on, 1 off**, original plugin/disabledPlugin copies
retained. Sandbox game has an empty NuclearOption.exe. No real game was used.
`realRegistryCheck` refuses a home path outside nnomm-sandbox; omit `-Ptakeover`
on subsequent checks because an already managed folder is not taken over twice.

## Contract changes and visible decisions

Existing IO ports remain source compatible; AppIo adds default-null
`StatePersistence` and `PackSource`. AppStore adds `flush`, `loadMetadata`,
`checkPackUpdates`, `addPreparedLocalMods`; persistence observes all state actions
with a 350 ms debounce. Runtime.shutdown flushes explicitly. Demo still compiles
and its existing tests pass. Missing registry artifacts/url labels have defaults.

- Exact required loader toast: “Installed the mod loader BepInEx 5.4.23.4. Mods
  need it to run.”
- Unknown game version is displayed as unknown; Made for/fit comparisons use the
  newest gameVersion named by the registry.
- Local ids include a short content hash to prevent collisions between files
  with the same name. Local versions use a content hash rather than inventing a
  release number.
- Duplicate takeover ids abort with an explanation; both copies stay on disk.
- A missing profile config snapshot inherits current settings; swaps preserve
  recovery copies of every replaced config tree.
- Bad JSON uses a backup with a notice. If both copies are bad, startup fails
  with their paths kept intact rather than silently resetting player data.

## Limits and open checks

- Windows Steam discovery, URI launch/start options, hard links across volumes,
  file locks, process detection and Doorstop behavior need Windows checks listed
  in design/WINDOWS-CHECKS.md. No direct-exe fallback or Steam authentication is
  introduced. Linux real launch uses xdg-open; tests inject a fake command.
- The Unity probe accepts only a named bundleVersion string. A real binary
  PlayerSettings layout is not verified; unsupported files return unknown.
- BepInEx version reads a version string from the DLL when available; it is not
  a full PE/CLR metadata reader. The loader itself was not downloaded/installed
  in the sandbox test; its contents are validated before promotion.
- The existing AppStore.importPack still needs its UI caller to supply selected
  pack data. Pack config/settings payloads are available in the preview but are
  not automatically applied: the caller must offer the optional selection.
  Follow addresses currently serve JSON, not a downloadable ZIP feed.
- No real Steam/game/Proton session, power-loss crash injection or UI integration
  was tested. Recovery copies deliberately have no automatic expiry.
- A hard-linked plugin can change the stored bytes if it edits itself. Removal
  captures an independent recovery copy. Source download hashes are checked
  before extraction; a later repair fetches the artifact again.
