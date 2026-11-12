# Settings page handoff

The page uses the core setting definitions, categories, keyboard list and store
setting/override actions. It registers search focus for `/`, tracks focused fields,
and clears search with Escape. Category navigation clears search. Profile scope
never changes the active gameplay profile. Inherited values stay disabled until
`ovset`; `ovreset` and `ovclear` restore general values. Search includes options
and marks the matching text.

The live shell already derives theme, text percentage, density and motion from
general settings. Settings updates those same values immediately.

## Player-visible decisions

- Servers contains only “Show Servers in the sidebar”. Its lead is “The server
  list comes in a later version.” This is the requested first-version exception.
- Update help shows the installed version until the updater supplies a status.
  It does not claim that an unchecked version is the newest.
- Unused-storage help does not show the mock's invented 212 MB. Core does not
  expose unused bytes. Store usage does come from `storeUsedMb`.
- Registry addresses are saved in `sources`, a newline-separated primitive,
  because the current setting model accepts only primitives. Additional registry
  reads and source selection remain unsupported by core; changing them gives
  a TODO(core) notice. The built-in NOMNOM Read now calls `refreshRegistry`.

## TODO(core)

No fabricated completion is shown for these absent capabilities:

- Game-folder discovery (Find again).
- Moving the mod store and measuring/removing unused versions.
- Full problem report with the last game log.
- Reset settings and remove everything, after their confirmation dialogs.
- Reading additional registry addresses or changing the registry reader's source.
- Editing keyboard shortcuts.

Choose folder uses FileKit's native directory picker; chosen paths go through
`setSetting("gamePath", ...)`, which inspects them in the core's background work.
Repair uses `repairLoader`. Open uses the configured store path; Open log folder
opens the game's BepInEx folder. Missing paths produce an explicit notice.
Licences contains the complete repository MIT licence, the NOMM and NOMNOM
credits, and the bundled Archivo OFL. Confirm and licences factories are registered
in the live model and shot model. No changes to Windows code paths are made.

## Checks

See the implementation task's final report for the checks actually completed.
Independent Opus/Sol review remains the parent's responsibility: this task
explicitly required working alone.

Completed validation:

- `tools/gradlew-locked :composeApp:jvmJar :composeApp:renderShots` passes.
- `tools/compare-shots` creates the six Settings comparisons in
  `composeApp/build/shots/compare/`: 08-settings-game, 10-settings-profile,
  11-settings-search, 26-settings-danger, 27-settings-mods, 32-settings-registry.
- A temporary render entry point checked all ten categories plus profile/search,
  in dark/light at 1328 x 792 and 728 x 720. Pictures are under
  `composeApp/build/shots/settings-check/`. It was removed after use. The final
  category wrapping and path-control fixes were also compiled and rendered in
  the standard harness; those extra variant pictures precede these last fixes.
- Chromium captures checked every mock category. The core definitions omit the
  temporary-server-profile setting present in the mock; the UI follows core.
- On the shared desktop, demo settings navigation, typed search with highlights,
  and Escape clearing were checked. Final Tab/select-arrow coverage is incomplete:
  another agent used the desktop, then X window enumeration itself timed out.
  This task's demo windows were closed. No real game folder was selected.

Remaining: complete desktop interaction/keyboard coverage, an exact visual pass
against the final pictures (font rasterization and spacing still differ), and the
project's independent Opus/Sol reviews. No pixel-equivalence claim is made.
