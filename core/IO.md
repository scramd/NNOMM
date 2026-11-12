# Real IO and player-file safety

Open real mode with `RealRuntime.open()`, collect `runtime.store.state`, and call
`runtime.shutdown()` before exit. All IO methods dispatch to `Dispatchers.IO`.
The UI still owns file selection. No Compose dependency, Steam authentication,
Steamworks worker or GitHub API is used.

## Private files

`NNOMM_HOME` overrides the entire home. Otherwise use `%APPDATA%/NNOMM` on
Windows, `$XDG_CONFIG_HOME/NNOMM` or `~/.config/NNOMM` elsewhere. No NOMM settings
are read or written. NOMM `meta.json` beside existing game mods is import metadata.

- `state.json`: profiles, installed records, profile versions/on states, settings,
  first-start status, game path and local catalog. State writes debounce 350 ms.
- `settings.json`: settings compatibility port. State is authoritative on restart.
- `registry.json`: decoded list and read timestamp, used offline.
- `head.json`: successful final-response HEAD size/date by id, version and URL.
- `release-notes.json`: GitHub Atom notes by repository; refreshed on first request
  each session with saved offline fallback.
- `mods/<id>/<version>/`: expanded mod payload. `staging/`: transfers and extraction.
- `recovery/`: copies retained before changing game files, repairing payloads,
  replacing configs or loader files. These are not removed by cache clearing.
- `profiles/<id>/config/` and `profiles/shared/config/`: config snapshots.
- `doorstop-restore.json`: original ini text for an interrupted vanilla launch.

JSON writes use a sibling temporary file, force it to disk and rename it. Atomic
rename falls back to replace if the filesystem does not support atomic moves.
The previous valid JSON is `.bak`. A damaged primary stays untouched on read;
recovery emits a notice callback. Before replacing a damaged primary, keep an
additional `.broken-<number>` copy. If neither primary nor backup parses, loading
fails with a useful message instead of replacing the player's state. Connect the
runtime's `notice` callback to visible UI notifications.

## Game folder

Only a configured folder containing NuclearOption.exe is accepted. Development
uses `~/nnomm-sandbox/game`, never an actual installation. NNOMM writes enabled
payloads under `BepInEx/plugins/<id>` and records ownership in
`BepInEx/plugins/.nnomm-state.json`. Takeover copies immediate entries of plugins
and disabledPlugins without moving/deleting them. Duplicate ids abort takeover
with an explanation and retain both game copies. Unknown files block conflicting
placement. All managed removals first make independent recovery copies.
Symlinks and archive traversal are refused. Archives have a 2 GiB/100,000 entry
limit. Extraction never trusts the extension as evidence of valid content.

A hard link is tried for profile placement, then normal copy. Editing a linked
plugin also edits its store copy; recovery snapshots are independent copies.
Use config files for settings. Config switching first backs up the whole current
config tree. Profiles without a prior snapshot inherit the current config.

While the real game process is detected, apply/loader/takeover will not run.
Store mutations remain private and apply on the next launch. Loader repair backs
up replacements and refuses plugins/config payloads. It never writes BepInEx.cfg.
Uninstall is logical; files remain for Undo. Explicit unused-file clearing keeps
referenced profile/default versions plus the requested number of old versions.
It does not clear recovery copies. Clear refuses a running game and protects
uninstalled payloads for nine seconds so the eight-second Undo remains usable.

Play without mods saves the exact ini text and sets enabled=false. It restores the
text after the watched game exits, on start failure, or before the next launch.
A crash can leave vanilla mode active until the next NNOMM launch attempt.

## Integration and current boundaries

Run `NNOMM_HOME=$HOME/nnomm-sandbox/home tools/gradlew-locked :core:realRegistryCheck`.
Add `-Ptakeover` only on a fresh sandbox to check import. This task GETs the real
registry, writes a sandbox cache and prints counts. Unit tests use loopback only.
`AppStore.loadMetadata()` fills metadata progressively with three worker slots;
registry refresh requests it for the newest release of each mod. Historical
metadata can be requested through `io.meta`. Notes load only through `io.releaseNotes`.

Decisions: loader toast is “Installed the mod loader BepInEx 5.4.23.4. Mods need
it to run.” An unknown game version compares Made for with the newest registry
version. Local ids include a short content hash to avoid name collisions.
The follow address currently serves JSON `{"format":"NNOMM/1","pack":...}`.
Pack reading returns a preview and staging files, never executes local payloads.
Pack writing includes selected local files, optional manager/profile settings and
a BepInEx config snapshot. `selectedLocals` materializes only explicitly selected
payloads and returns OFF records. Applying pack settings and connecting previews,
the file chooser/preview UI still need integration. Use `addPreparedLocalMods`
for the explicitly selected OFF records; `checkPackUpdates` records nextVersion
on each followed profile without importing it.
Legacy packs support modlist.nomm.json plus mods/.

Unverified: actual Unity serialized PlayerSettings bundleVersion layout (the
conservative named-field probe returns unknown on unsupported layouts), Windows
Steam launch and start-option URI handling, Proton loader behavior, crash/power-loss timing. ZIP and 7z extraction are tested with invented fixtures. This implementation has no independent
Opus/Sol review because this assignment explicitly requires work alone.
