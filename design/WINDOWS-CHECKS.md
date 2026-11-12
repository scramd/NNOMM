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
