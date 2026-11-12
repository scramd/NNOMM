# Real-mode integration and sandbox check

Real mode now owns a RealRuntime rather than returning demoStore. The window opens
with “Opening NNOMM…” while recovery, persistence loading and game inspection run
on IO. Closing through the title button or native close request uses the same
asynchronous shutdown: placement save, disk-queue drain, state/settings flush,
watcher/client close, then application exit. The window shows “Saving your changes…”.
A startup failure keeps a readable window; a flush failure keeps the process open.

## Implementation

- Private settings path comes from core settingsHome: NNOMM_HOME or the platform's
  NNOMM folder. Demo and kit modes keep fake IO.
- Core notices, including startup notices emitted before the shell exists, forward
  to timed store toasts. Metadata loads progressively after catalog changes, using
  the core's bounded metadata queue and persistent cache. Existing lazy Coil tiles
  and on-demand panel release-note loading remain in place.
- First start uses runtime recovery/discovery, then read-only folder inspection.
  Folder choice uses FileKit and requires NuclearOption.exe. Counts cover visible
  entries in plugins and disabledPlugins. It displays the inspected game and
  loader versions; the empty sandbox executable correctly has an unknown version.
- Both confirmation buttons await core adoption before navigation and completion.
  Failure keeps the layer open. Escape, global navigation/Play, and command search
  cannot bypass the layer. Pending command-line imports wait for confirmation.
- A small core change prevents automatic startup adoption before first-start
  confirmation. The existing recovery adoption for a completed, empty library is
  retained. A regression test covers no adoption before confirmation, unchanged
  game bytes, confirmation and restart.
- Registry refresh now retains known metadata for matching id/version/download
  URLs, including offline fallback; a regression test covers both refresh outcomes.
  The desktop restart check exposed the former reset to unknown sizes/dates.
- Empty configured registry sources now stay empty instead of silently enabling
  NOMNOM again. The regression test checks this without network access.
- Small registry-settings integration edits remove stale TODO notices and connect
  additional sources' Read now buttons to the existing refresh action. No broad
  page edits.
- NNOMM_SANDBOX_LAUNCHER optionally supplies one executable for launch checks.
  The app accepts it only with a home under ~/nnomm-sandbox. Normal launch paths
  are unchanged. Windows checks are listed in design/WINDOWS-CHECKS.md.

## Sandbox preparation

Only ~/nnomm-sandbox/game was used. Its NuclearOption.exe is empty.
The prior game tree was copied to ~/nnomm-sandbox/game-before-real-<timestamp>.
The reset tree contains two enabled fixture mods (SandboxHud, SandboxRadar), one
disabled fixture (SandboxOff), a fake BepInEx.dll carrying version 5.4.23.4, a
player config and Doorstop config. The exact initial tree is kept at
~/nnomm-sandbox/game-real-baseline. ~/nnomm-sandbox/home-real started empty.

The built executable was launched on Xvfb :58 at 1600 x 1000, with a window manager
on that display only. Display :1 was not used. Other running demo instances
initially caused the packaged launcher to exit through single-instance routing.
A private TMPDIR, java.io.tmpdir and XDG_RUNTIME_DIR under ~/nnomm-sandbox isolated
this check without touching those instances.

Build command: tools/gradlew-locked :composeApp:createDistributable.
Executable: composeApp/build/compose/binaries/main/app/NNOMM/bin/NNOMM.
Real screenshots and per-step file comparisons are kept in
~/nnomm-sandbox/notes/real-mode.

## Step-by-step result

1. Fresh launch showed “Nuclear Option was not found”, a folder-choice action and
   disabled confirmation buttons. Linux did not search real Steam libraries.
   The baseline game tree was unchanged.
2. Chose ~/nnomm-sandbox/game through the native chooser. First start showed
   “3 mods”, “2 on, 1 off”, unknown game version and BepInEx 5.4.23.4.
   No adoption files or stored payloads existed before confirmation; recursive
   game comparison was identical to the baseline.
3. Clicked Open my Library. Three local mods appeared with the original switches.
   Completion and game path persisted. Game comparison was still identical.
4. Browse read the live NOMNOM registry: 278 catalog records, with 275 visible
   under the default server-only filter. Real image tiles appeared. The HEAD
   cache filled progressively; the later Library row showed size and release date.
   Browsing alone left the game unchanged.
5. Installed Ace Combat Reloads 1.2.0 from its GitHub release, a 53,248-byte DLL.
   The real downloader verified the registry hash. The DLL appeared at
   BepInEx/plugins/AceCombatReloads/AceCombatReloads.dll. Only it and NNOMM's
   ownership record were added; every original game file stayed unchanged.
6. Turned it off in Library. Its DLL left the game tree and remained in the store.
   Turned it on; the identical DLL returned. Original fixture bytes were unchanged.
7. Created Sandbox with all mods off. The enabled fixture copies and downloaded
   mod left plugins, with all original bytes retained in NNOMM's store/recovery.
   Returned to Default; all enabled copies returned. Config and disabled fixture
   remained unchanged.
8. Uninstalled Ace Combat Reloads through its row menu. It disappeared from Library
   and plugins. Clicked Undo; the same version and bytes returned, enabled in
   Default. Original fixture files were unchanged.
9. Closed with the custom title button and restarted the packaged app. First start
   did not reappear. Library, Default/Sandbox profiles, switches, settings, registry
   cache and installed DLL survived. Restart itself did not change game files.
10. Selected Play without mods with the sandbox fake launcher, an eight-second
    script. The UI showed Game is running; Doorstop was disabled while it ran.
    The original Doorstop text was saved in the durable restoration JSON and was
    restored byte-for-byte after exit. Profile switches and mod files stayed intact.
    The initial raw-file-only comparison could not recognize the text inside JSON;
    the checker was updated to include that stored original text. No bytes were lost.
11. In Registry settings, added http://127.0.0.1:9/manifest.json, disabled NOMNOM
    and read again. Browse showed “No connection to the registry” with the saved
    list, working Library and Play controls. Core failure notices became toasts.
    Restart retained the source setting and offline saved list. No game changes.
12. Opened Ace Combat Reloads → What's new: the real 1.2.0 release notes loaded
    on demand while the registry itself stayed offline. No game files changed.
13. Checked the repaired additional-source Read now button in the rebuilt app:
    it retried the unreachable source and returned to the offline list without the
    old placeholder notice. Alt F4 closed the window and flushed settings.
    A final native close request was also exercised.
14. Rebuilt with the metadata-retention fix and restarted offline. Library again
    showed Ace Combat Reloads as 52 KB with its release date after the failed
    startup refresh; the fixture config and payload comparison remained unchanged.

For install, off, on, both profile transitions, uninstall, Undo, launch completion,
restart and offline checks, file hashes were compared against the baseline.
When an explicit profile action removed a game copy, the identical original bytes
were checked in the private store/recovery. The only lasting game additions at the
end were the requested downloaded mod and NNOMM's ownership records. No original
fixture/config bytes were lost.

## Validation and review handoff

- Core tests, Compose JVM tests, distributable build and core spotlessCheck pass.
- tools/gradlew-locked :composeApp:renderShots and tools/compare-shots completed.
- Inspected composeApp/build/shots/compare/16-first-start.png (mock left, app right).
  Wording and layout are preserved; small vertical spacing/font raster differences
  remain from the existing layer. This task does not claim pixel equivalence.
- Inspected dialog-first-dark-wide, dialog-first-dark-narrow,
  dialog-first-light-wide and dialog-first-light-narrow in composeApp/build/shots.
  Both widths and themes fit; the narrow case uses the existing 728 px app frame.
- No new TODO(core) workaround. Direct panel release-note loading remains the page's
  existing implementation. Opened Ace Combat Reloads → What's new while the
  registry was offline: its real 1.2.0 notes loaded from GitHub on demand.
- No Opus/Sol review was started: the task explicitly requires working alone.
  Independent reviews and any page-wide visual fixes remain with the parent.
- Windows Steam discovery, native close handling, installation/updating and real
  game launching need Windows checks. No real game was launched. Startup loading
  and shutdown UI are wired, but no artificial slow-disk latency test was performed.
- The sandbox retains the unreachable registry setting for reproducing offline
  behavior. Turn NOMNOM on in Registry settings to restore network reads.
- The app and the private :58 display/window manager were closed after checks.
- Branch: t3/real-mode-startup. Worktree: the supplied NNOMM implementation worktree.
  Core commit: 766272e (first-start confirmation, notices, metadata retention
  and regression tests). UI/report changes are in the subsequent integration
  commit containing this document.
  Local commits only; no push, tags, releases, issues, comments or PRs.

## Second IO safety round (2026-11-12)

Repeated steps 1–11 using the built distributable on private Xvfb :61 with xfwm4.
No use of display :1. New home/game copies were under
`~/nnomm-sandbox/safety2`; the executable remained an empty fixture.
Screenshots and SHA-256 snapshots are in that directory (`shots/01` through `28`,
plus off/on/profile/uninstall/Undo/restart/offline JSON snapshots).

1. Fresh launch showed the missing-game screen. Game matched the baseline exactly.
2. Native folder choice selected safety2/game, showing 3 existing mods, 2 on and
   1 off, unknown game version and BepInEx 5.4.23.4. Game bytes were unchanged.
3. Open my Library adopted the three fixtures. No game files changed.
4. Browse loaded NOMNOM: 278 records, 275 shown by the server-only filter. No game
   files changed from viewing the registry or waiting for metadata.
5. Installed Ace Combat Reloads 1.2.0. Only its requested placement and manager
   metadata/staging directory were added. Baseline files were unchanged.
6. Turned that mod off/on. Its DLL left/returned; fixture and config bytes stayed.
7. Created Safety sandbox with all mods off, then returned to Default. Removed
   fixture bytes remained in the private store and returned unchanged.
8. Uninstalled Ace Combat Reloads and clicked Undo. The same DLL returned. Hash
   checks after both actions found every baseline file in the game or private home.
9. Closed and restarted. First start did not reappear. Profiles, versions and
   switches survived. Complete game-file hashes before/after restart were equal.
10. Used Play without mods with an eight-second sandbox launcher. UI showed Game
    is running; Doorstop was disabled during play, then restored byte-for-byte.
    The complete game-file tree after exit equalled the pre-launch tree.
11. Added unreachable http://127.0.0.1:9/manifest.json, disabled NOMNOM, used Read
    now, then restarted offline. Saved catalog and Library remained usable with
    a plain offline banner. No game-file changes from settings, refresh or restart.

After further journal/pack/PE hardening, rebuilt and repeated the offline restart
and mod off/on checks against the newer distributable. All baseline hashes still
survived. The last metadata retry, loader-notice and canonical-adoption refinements were
checked by the locked core/JAR build; the full GUI sequence was not repeated after
those changes. The sandbox keeps its offline source setting for reproduction.
The app was closed after checks; the private display/window manager were stopped.
