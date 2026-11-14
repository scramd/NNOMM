# Checks that need Windows

The first run on Windows was on 2026-11-12. Its results, check by check, are in [Windows results](WINDOWS-RESULTS.md). This file stays the list of what to check before a release.
These parts cannot be checked on the Linux development machine. Check them on Windows before a release. Each entry says what changed from NOMM 5.5.0 (`3dacae5`) and what to check. Upstream 5.6.0 (`8786356`, merged) changed none of these Windows-only paths. Linux packaging checks are tracked in `STATUS.md`.

| Part | Change from NOMM | What to check |
| --- | --- | --- |
| Window frame | Replaced Material chrome with a foundation-only 38 px NNOMM title bar inside Nucleus `DecoratedWindow` and `WindowScaffold`, Tao backend. The scaffold publishes the custom caption height to native code. Blank title regions use Nucleus `windowDragArea` for native hit testing, dragging and double-click maximize. Window buttons use `NucleusWindow`. Minimum 720 x 560. | Drag, double-click, resize edges, snapping, minimize, restore, maximize, close, DPI scaling, Alt F4 and taskbar restore. Check native Windows caption hit testing and snap layouts. |
| Window persistence | Placement and floating width/height now save to NNOMM `window.properties`, separate from NOMM configuration. No Steam shutdown paths remain. | Close and reopen floating and maximized; ensure NOMM settings are untouched and a second instance restores NNOMM. |
| Single instance | Explicit runtime ID `io.github.scramd.nnomm`; FileKit ID `NNOMM`. | Run NOMM and NNOMM beside each other; second NNOMM restores its own window only. |
| Installer identity | App/package/menu group NNOMM, version 0.1.0, new upgrade UUID `b0eccd25-0931-477e-8ff4-c6693ee69964`, own homepage and noreply maintainer. Existing package formats retained. | Install alongside NOMM, upgrade NNOMM, uninstall each independently; check shortcuts, names, icons and data retention. |
| Updater | Original Nucleus metadata, platform suffix and portable/ZIP manual-update rules retained, now targeting `scramd/NNOMM`; publishing remains Never. | Metadata and installer assets resolve to NNOMM; installed MSI update and portable/ZIP manual update work. No releases were created during development. |
| Open folder | Folders open with `explorer.exe` (NOMM's way); files open with `Desktop.open`. A failure shows "Could not open <name>" and is logged. Before, `Desktop.open` ran fire-and-forget and a failure showed nothing. | Open the game folder, the mod folder, the mod log, Settings > Storage > Open and Open log folder; each opens a window in front, or shows a message. |
| Stop the game | Right-click "Game is running" > Stop the game: `taskkill /PID` (asks the game's windows to close), after 10 s `destroyForcibly`. Only processes named `NuclearOption.exe`. | Start the game from NNOMM and stop it; the game closes and NNOMM shows Play again. Also with the game started from Steam. |
| File association | `.nommpack` and MIME `application/nommpack` retained. Initial arguments and later file deep links are queued in `LaunchRequests.pendingFiles` for the future import UI. | Open a pack with the app closed and already open, including spaces/non-ASCII paths. Confirm the queued file reaches the later import preview once. Check association ownership with NOMM installed. |
| Removed Steam process | Steamworks dependency, worker sources and both Steam app-id resources removed. | Starting or leaving NNOMM open never marks Nuclear Option as running in Steam. |
| Installer resources | Corrected inherited nonexistent NSIS installer/uninstaller icon paths to the existing `icons/icon.ico`; corrected the licence path to the root MIT licence. | Build a Windows installer and check both icon resources and the displayed MIT licence. |
| Core game discovery | Ported HKCU SteamPath query and libraryfolders.vdf path scan to :core; non-Windows uses configured path only | Discover default and secondary Steam libraries; check registry access failures and non-ASCII paths |
| Core game launch | Keeps cmd.exe /d /c start "" steam://rungameid/2168680 for empty options, no Steam worker; nonempty options use steam://run/2168680// URL-encoded arguments | Steam running/closed, spaces/quotes in start options; confirm game receives options and running detection follows actual game |
| Vanilla launch | Backs up doorstop_config.ini, writes enabled=false, restores exact original bytes on exit or next launch attempt | Verify BepInEx is bypassed; normal exit, launch failure, crash recovery; external Steam launches after an interrupted vanilla session |
| Mod placement and loader | New managed state record, hard-link/copy fallback, BepInEx 5.4.23.4 install/repair with Doorstop handling as described below | Same/different volume, permissions, antivirus locks, existing unmanaged plugins, profile config swap, repair preserves plugins/config |
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
| Pinned loader Repair preserves healthy enabled INIs and skips pending restores | Check 5.4.23.4 repair, differing-version refusal and interrupted repair with locked DLLs. |

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
  with a valid General target stays unchanged; without a pending restore for that
  folder, ineffective configs receive shipped defaults and the original stays in
  recovery. With a pending restore, Repair skips the INI and asks for Repair again.
  Unowned binaries stay unchanged. Without a pending restore, an unreadable INI
  or a refused INI replacement move must fail without replacing any loader files.
- Play without mods after Repair; verify enabled restoration after observed exit.
- Full disk during loader download or placement gives free-space guidance; TLS
  and dropped transfers give connection guidance. First-mod Retry repeats installation.
- Remove all mods with owned and adopted loaders: confirm completion matches
  actual removed/retained files. Other-version Repair gives instructions without Retry.

## Doorstop encoding and profile API

| Check on real Windows | Expected result |
| --- | --- |
| Play without mods and Repair with UTF-8 BOM, UTF-16 LE/BE BOM, `[General] ; comment` and `[ General ]` headers; include lone CR, byte 0x85 comments, duplicate General sections and missing enabled. Hold the INI open with a sharing lock during Repair. | Doorstop uses the first General section. Play disables effective keys and restores exact original bytes after exit/restart/failure, with BOM first and original line endings. Healthy Repair preserves encoding and unrelated bytes; missing enabled alone stays unchanged, explicit false becomes true unless restore is pending. Without a pending restore, unhealthy Repair uses shipped defaults and keeps original bytes in recovery. Without a pending restore, a locked read fails with close-programs guidance and leaves every loader file unchanged. |

## Doorstop2 restore and value handling

| Check on real Windows | Expected result |
| --- | --- |
| Repair pinned 5.4.23.4 with enabled=0, 1, no, yes, off, empty, quoted false and inline comments; also missing enabled and enabled without equals. Try empty and quoted-empty target_assembly. | Without a pending restore, every present value other than true is repaired to true. With a pending restore, Repair skips the INI entirely. Missing/no-equals enabled stays unchanged. Without a pending restore, empty targets receive shipped defaults and exact originals remain in recovery with a notice. |
| Lock the INI or deny writes during Play without mods and again during restore after game exit. Keep the lock through NNOMM restart, then release it. | Startup still opens, running clears on exit, saved bytes remain pending, and one close-programs notice appears when changed INI bytes are known. A refused edit with an unreadable rollback comparison retains the record without claiming the mods were turned off. Automatic retry restores exact bytes after release without another game transition. Play retries first and remains usable after recovery. An edit failure gives access/lock guidance, preserving the initial cause in logs. |
| Disconnect a sandbox library drive while its restore record is pending, then reconnect it. | Restart retains the record without recreating the folder; automatic retry restores exact original bytes when the drive returns. |
| Use unmatched bracket lines, NBSP/control whitespace, and odd-length or unpaired-surrogate UTF-16 in the sandbox INI. | Windows profile API behaviour agrees with parser rules; malformed UTF-16 is refused with valid-copy guidance and no loader files change. |

## Doorstop3 read-only and pending Repair

| Check on real Windows | Expected result |
| --- | --- |
| Mark the sandbox doorstop_config.ini read-only, then use Play without mods followed by normal Play. | The edit gives access guidance. Unchanged original bytes clear the restore record without another INI write, no false restore notice appears, and normal Play works. |
| Put a bare enabled line before enabled=true; also use VT, FF and Ctrl-Z whitespace around headers, keys and values. | Play without mods changes the first effective key. Whitespace agrees with the Windows profile API; whitespace-only targets receive shipped defaults on Repair. |
| Repair an unhealthy INI with a pending restore for the same sandbox folder, then restart or retry restoration. | Repair leaves the INI and saved restore bytes unchanged, repairs other eligible loader files, and asks the player to use Repair again after restoration. The original remains in the private restore record. Once restored, a later Repair installs shipped defaults and keeps the current original INI under .nnomm/recovery in the game folder. |

## Doorstop4 pending restores and shared lock

| Check on real Windows | Expected result |
| --- | --- |
| Pause automatic restore after it reads saved bytes; request Repair, then allow restore to finish. | Repair waits on the same per-folder lock, then reads the current record. It never races a stale restore write or record clear. |
| With a pending restore, hold the INI without FILE_SHARE_DELETE and run Repair. Also try a missing or unreadable INI. | No INI replacement or restore-record rewrite is attempted. Other eligible loader files can repair. The result asks for Repair again after restoration and makes no false recovery-copy claim. |
| Disconnect sandbox library A with a pending restore, use Play without mods in sandbox B, then reconnect A. | Separate records retain both exact originals. B restores after exit; A is never recreated and restores when it returns. Repeat across NNOMM restart. |

## Doorstop5 Repair result and folder aliases

| Check on real Windows | Expected result |
| --- | --- |
| With an NNOMM-owned 5.4.23.4 loader, deny INI rename without denying reads. Repair both an unhealthy INI and a healthy disabled INI. | The INI replacement is first. A failed move leaves all loader file bytes unchanged, gives close-programs guidance and makes no success or recovery-copy claim. After a successful batch the INI must match the intended bytes before success is reported. |
| Save a legacy restore through a junction or a differently cased path, disconnect that sandbox library before startup, then reconnect it. | The absent folder is not recreated. The legacy record keeps its recorded path until canonicalisation is possible. Repair finds the pending record through the canonical path; restoration returns the exact saved bytes and clears it. Linux regression covers a symlink; Windows junction and case handling remain unchecked. |
| Keep restore locked in sandbox A, then use both normal Play and Play without mods in sandbox B. | B launches and its own original restores after exit. A's record and INI remain unchanged, its notice names A, and Play in A still refuses until restoration succeeds. |

## Loader ownership v2

- In a sandbox, replace NNOMM-owned loader files by hand, including the INI, then
  run Repair, Retry and Remove all mods. The replacements stay at their paths and
  lose NNOMM ownership. Hold a file without read/delete sharing: refusal must keep
  ownership evidence until access returns.
- Interrupt loader installation/removal, hand-install files, then restart twice.
  Operation-specific reports must not claim replacements or abandon unchanged
  NNOMM files. A removed parent directory must stay absent after Uninstall.
- Replace an incomplete owned loader with another BepInEx version. Play must keep
  its presence guard and must not add pinned-version files or announce installation.

## Closing loader regressions

- After interrupted rollback restores a loader file, delete that file before restarting.
  Recovery must finish and Install Retry must work.
- Replace `winhttp.dll` and delete the BepInEx detection DLL and another core DLL.
  Install must preserve the changed loader and add no pinned files, before and after restart.
