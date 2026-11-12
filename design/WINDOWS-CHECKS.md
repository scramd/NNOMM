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
- The launcher, installer and Nucleus updater implementations are unchanged.

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
