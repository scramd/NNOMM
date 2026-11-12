# Checks that need Windows

These parts cannot be checked on the Linux development machine. Check them on Windows before a release. Each entry says what changed from NOMM 5.5.0 (`3dacae5`) and what to check.

| Part | Change from NOMM | What to check |
| --- | --- | --- |
| Window frame | Replaced Material chrome with a foundation-only 38 px NNOMM title bar inside Nucleus `DecoratedWindow` and `WindowScaffold`, Tao backend. The scaffold publishes the custom caption height to native code. Blank title regions use Nucleus `windowDragArea` for native hit testing, dragging and double-click maximize. Window buttons use `NucleusWindow`. Minimum 720 x 560. | Drag, double-click, resize edges, snapping, minimize, restore, maximize, close, DPI scaling, Alt F4 and taskbar restore. Check native Windows caption hit testing and snap layouts. |
| Window persistence | Placement and floating width/height now save to NNOMM `window.properties`, separate from NOMM configuration. No Steam shutdown paths remain. | Close and reopen floating and maximized; ensure NOMM settings are untouched and a second instance restores NNOMM. |
| Single instance | Explicit runtime ID `io.github.scramd.nnomm`; FileKit ID `NNOMM`. | Run NOMM and NNOMM beside each other; second NNOMM restores its own window only. |
| Installer identity | App/package/menu group NNOMM, version 0.1.0, new upgrade UUID `b0eccd25-0931-477e-8ff4-c6693ee69964`, own homepage and noreply maintainer. Existing package formats retained. | Install alongside NOMM, upgrade NNOMM, uninstall each independently; check shortcuts, names, icons and data retention. |
| Updater | Original Nucleus metadata, platform suffix and portable/ZIP manual-update rules retained, now targeting `scramd/NNOMM`; publishing remains Never. | Metadata and installer assets resolve to NNOMM; installed MSI update and portable/ZIP manual update work. No releases were created during development. |
| File association | `.nommpack` and MIME `application/nommpack` retained. Initial arguments and later file deep links are queued in `LaunchRequests.pendingFiles` for the future import UI. | Open a pack with the app closed and already open, including spaces/non-ASCII paths. Confirm the queued file reaches the later import preview once. Check association ownership with NOMM installed. |
| Removed Steam process | Steamworks dependency, worker sources and both Steam app-id resources removed. | Starting or leaving NNOMM open never marks Nuclear Option as running in Steam. |
| Installer resources | Corrected inherited nonexistent NSIS installer/uninstaller icon paths to the existing `icons/icon.ico`; corrected the licence path to the root MIT licence. | Build a Windows installer and check both icon resources and the displayed MIT licence. |
| Core game discovery | Ported HKCU SteamPath query and libraryfolders.vdf path scan to :core; non-Windows uses configured path only | Discover default and secondary Steam libraries; check registry access failures and non-ASCII paths |
| Core game launch | Keeps cmd.exe /d /c start "" steam://rungameid/2168680 for empty options, no Steam worker; nonempty options use steam://run/2168680// URL-encoded arguments | Steam running/closed, spaces/quotes in start options; confirm game receives options and running detection follows actual game |
| Vanilla launch | Backs up doorstop_config.ini, writes enabled=false, restores exact original text on exit or next launch attempt | Verify BepInEx is bypassed; normal exit, launch failure, crash recovery; external Steam launches after an interrupted vanilla session |
| Mod placement and loader | New managed state record, hard-link/copy fallback, BepInEx 5.4.23.4 install/repair without config overwrite | Same/different volume, permissions, antivirus locks, existing unmanaged plugins, profile config swap, repair preserves plugins/config |
| Private persistence | APPDATA/NNOMM or NNOMM_HOME, atomic JSON with backups | Installed beside NOMM, no NOMM settings access, locked files and interrupted writes |
| Shell integration | Unified title-bar close and native close into one asynchronous placement-save path; floating x/y saved and restored. Maximized frame has square corners. Theme defaults to Dark; system theme and `win.animation` apply only when selected. | Close by title bar, Alt F4 and taskbar, reopen at the same position; maximize at different DPI; test Windows theme and animation changes. |
| Shell file routing | FileKit chooser/saver; native Compose file-drop target; initial and single-instance file launches route `.dll`, `.zip`, `.7z`, `.nommpack` and `.nomm.json` through `AppModel.acceptFiles`. Packs retain the file for the import factory. | Drop each format, open files with spaces/non-ASCII names with app closed and running; check import preview and local install after real IO is connected. |
| Branding | New original olive/cream N icon from `composeApp/icons/icon.svg`, PNG/ICO/ICNS generated from it; vendor NNOMM, Linux package name nnomm. | Check installer publisher, taskbar and shortcuts; rebuild all platform packages. |
| Update setting | Startup checks now follow Update NNOMM: Never skips, Tell me reports availability, By itself downloads and uses Nucleus install/restart. Windows portable/ZIP builds keep manual downloads. About calls `AppModel.checkAppUpdates` and `openAppReleases`. | Check all three settings in an installed Windows build, failure/offline handling, portable/ZIP manual path and installer restart. Linux demo/kit checks intentionally make no updater requests. |

## Real IO safety fix round

| Changed behavior | Check on Windows |
| --- | --- |
| Journaled directory/file replacement and rollback | Inject interruption with antivirus/file handles present; restart and verify old or new complete tree, recovery index and directory rename behavior. |
| Portable path preflight | Reserved names, trailing dots/spaces, case aliases and 240-character path limit reject before game changes. |
| Copy-only placement, stable takeover names and parent/addons placement | Verify loader reads one copy; native mods and Blueprinter keep their expected paths. |
| Doorstop restoration tied to observed game exit and startup | Steam delayed start, app closes first, app restart while game runs, and Steam URI arguments need real Windows checks. |
| Process polling every three seconds, executable/argument detection | Verify external Steam starts and exits, CPU usage, and game-close apply timing. |
| Pinned loader batch repair preserves existing Doorstop INI | Check 5.4.23.4 repair, differing-version refusal and interrupted repair with locked DLLs. |

## Real-mode startup integration

- RealRuntime now opens from the live app on IO before the shell is usable.
  Verify Windows Steam discovery, missing Steam, first-start folder selection
  and validation, game-version inspection, and the no-change confirmation boundary.
- Native close, custom title close and Alt F4 share asynchronous runtime shutdown
  and placement saving. Verify all three during startup and pending mod writes.
- Core notices now show as toasts; verify restoration/recovery notices after restart.
- Settings directory selection uses core settingsHome (APPDATA/NNOMM on Windows),
  with NNOMM_HOME override. Verify NOMM's settings remain untouched.
- Installer packaging remains inherited; launcher discovery, launch monitoring and updater shutdown have changed as listed below.

## Remaining polish

- Shared kit controls now activate on Tao's typed-only Space on X11. Physical
  Space remains handled by Compose clickable; an accompanying typed event is
  consumed until key-up. Check focused buttons, switches, checkboxes, radios,
  chips, segmented choices and popup menu entries on Windows for one activation.
- Library and panel settings-file actions now search stored DLLs for serialized
  BepInPlugin GUIDs and match BepInEx/config/<GUID>.cfg, then exact normalized names.
  Check a real plugin after one game run, multiple plugin GUIDs in one mod,
  absent configs and the Windows default editor association. Linux tests use
  byte-array fixtures and temporary sandbox files only.

## Second real IO safety round

| Changed behaviour | Windows check |
| --- | --- |
| Per-entry rename swaps outside plugin scanning | Lock one mod DLL: that mod reports a problem, others change, all player bytes remain recoverable. Validate same-volume rename and actual destination path limits. |
| Interrupted promotion rolls forward | Interrupt after each rename, run the game, restart NNOMM: new player files survive. |
| Startup waits for game exit | Reopen during play with pending recovery/adoption: no game writes; work resumes on observed exit. |
| Loader Repair refuses unknown/different versions | Test undetected/BepInEx 6 and known 5.4.23.4; only the pinned version can repair. Play alone must not install it. |
| Inherited vanilla restoration | Reopen NNOMM during Play without mods; Doorstop text returns byte-for-byte on exit. |
| Add-on order and directory ownership | Parent first, child second; child edits return with profile; empty created folders disappear. |

## Acceptance settings behaviour

- An observed game start now applies the profile's Keep/Minimise/Close NNOMM choice; verify native window minimisation and graceful close on Windows.
- Play writes only `[Logging.Console] Enabled` in BepInEx.cfg through the game-folder port, preserving a private backup; verify Windows file locking and profile config transitions.
- The updater selects `latest` or `beta`; beta metadata resolves the newest published `-beta` prerelease rather than GitHub's stable-only latest endpoint. Verify with packaged Windows builds once a beta release exists.


## Second acceptance check

| Changed behaviour | Windows check |
| --- | --- |
| Steam URI is the only game-start path; old executable fallbacks remain removed | Steam open/closed, default/secondary libraries, launch options and URI association; failure must leave Play usable. |
| Game-start detection is bounded to 60 seconds | Delayed start and no start: Play returns with a plain notice; later external starts still update the running state. A failed vanilla start restores Doorstop. |
| Steam registry query has a five-second process limit | Hung/missing reg query, access denied and non-ASCII Steam path do not hang folder discovery. |
| Automatic “By itself” update waits for the same placement-save/runtime-drain path as window close | Start an update during a queued mod write: install/restart begins only after safe shutdown; save failure prevents installation. |
| Hiding profiles and reset save preferences without placing files | Hide profiles outside Default, verify Default is selected and files wait until the next mod action or Play; reset must not change game files. |
| Per-profile console value applies at Play | Different overrides on two profiles, reset/clear override, Windows config locking and private config transitions. |

Third IO safety round: check Windows reparse/junction refusal before entry moves,
non-following deletion, home OS file locking (including installed/portable copies),
same-drive game recovery with home on C: and game on D:, and repeated recovery
under file locks. Core uses NOFOLLOW basic attributes (`isOther`), symbolic-link detection and a
canonical-parent/entry comparison for junctions reported as directories; native Windows junction coverage is not verified on Linux.

## Final core authorization and retention checks

- Reopen NNOMM, launch the game externally, then exit: no mod placement or loader
  installation occurs without an authorized pending mod action. Repeat with
  profiles hidden.
- Change mod switches during play, close NNOMM, reopen, then exit the game:
  deferred mod changes apply; BepInEx is not installed by that exit. Play with
  mods on can install the missing loader.
- Uninstall and Clear retain personalized recognized takeover files and local or
  pack payloads. Only payloads with verified registry provenance (or a matching
  takeover payload) are eligible for Clear.
- A refused link or junction at one mod root leaves it alone while independent
  safe entries apply.

## Final app acceptance fixes

- Beta discovery uses `releases.atom`, including `-beta.1` tags, then the same metadata asset download paths as stable (`beta.yml` on Windows). Verify published beta metadata and installation from a packaged build; no release API token is needed.
- Manual “Look now” bypasses the automatic-check “Never” preference for one check, returns unsupported-build feedback with a releases-page button, and never starts automatic installation. Startup unsupported/failure/current checks stay quiet.

Final acceptance retention: Remove everything now leaves local, taken-over and
unknown-origin stored mods in the Library, off in every profile, with their stored
copies retained. Verify this on Windows with locked files and after restart. The
Library footline now counts installed stored versions only; Settings continues to
count all store/recovery use. Linux choosers use Swing; Windows still uses FileKit.

## Last app findings

- Pack exports keep a uniquely named previous pack beside any replaced file, on
  every platform. Check FileKit replacement confirmation, extension handling,
  cancellation, repeated replacements and failed writes in a protected directory.
  Each recovery copy must remain readable after restart. Linux's Swing confirmation
  is platform-specific; Windows continues to use FileKit.
- The removal row/button/confirmation now says “Remove all mods”; confirm the
  combined core completion toast and actual retained files agree with the help,
  including when a locked DLL prevents removal.

## Closing pass

- With a locked loader file, Remove all mods must keep the completed Library/mod removal, show partial success, and let Retry remove only the loader files owned by NNOMM. No folder must update only the Library; a running game must refuse removal with the close-game instruction.
- Play without a folder must point to Settings. Failed loader preparation must have its own Retry and plain feedback, without paths or technical exception messages.
- Replacement pack recovery files now use a readable date/time and a collision suffix beside the destination. Check FileKit replacement, repeated saves in the same minute, locked destinations and recovery-copy permissions.
- Check serial background takeover verification alongside foreground downloads, and repeated taken-over mod version changes with player edits on NTFS. Stored payloads must not be duplicated into recovery parks.

## Last message and menu pass

- Check loader install, Repair and removal with denied/locked files, a full drive,
  an invalid archive and no network. Each message must give the matching next step;
  Retry must work. Loader removal Retry must use the currently selected game folder.
- Check removal with no Library entries and with only local mods. Confirm that
  completion describes loader ownership and retained copies accurately.
- Check an off mod left at a colliding placement name: its fix menu must name the
  enabled mods sharing that name, and each Turn off action must target that mod.

## Final loader fixes

- Repair Doorstop for owned and adopted BepInEx 5.4.23.4: a missing enabled key
  with a valid General target stays unchanged; ineffective configs receive shipped
  defaults and the original stays in recovery. Unowned binaries stay unchanged.
  A locked/unreadable INI must fail without replacing any files.
- Play without mods after Repair; verify enabled restoration after observed exit.
- Full disk during loader download or placement gives free-space guidance; TLS
  and dropped transfers give connection guidance. First-mod Retry repeats installation.
- Remove all mods with owned and adopted loaders: confirm completion matches
  actual removed/retained files. Other-version Repair gives instructions without Retry.

## Doorstop encoding and profile API

| Check on real Windows | Expected result |
| --- | --- |
| Play without mods and Repair with UTF-8 BOM, UTF-16 LE/BE BOM, `[General] ; comment` and `[ General ]` headers; include lone CR, byte 0x85 comments, duplicate General sections and missing enabled. Hold the INI open with a sharing lock during Repair. | Doorstop uses the first General section. Play disables effective keys and restores exact original bytes after exit/restart/failure, with BOM first and original line endings. Healthy Repair preserves encoding and unrelated bytes; missing enabled alone stays unchanged, explicit false becomes true unless restore is pending. Unhealthy Repair uses shipped defaults and keeps original bytes in recovery. A locked read fails with close-programs guidance and leaves every loader file unchanged. |
