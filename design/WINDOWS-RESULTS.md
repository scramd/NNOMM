# NNOMM on Windows: results of the first real run

Date: 2026-11-12. Machine: Windows 11, 100 % display scale, two monitors. Build: `master` after the fixes listed below.

This is the first time NNOMM ran on Windows and against real game files. The list of things to check is [Checks that need Windows](WINDOWS-CHECKS.md); this file has the results.

## Short answer

- NNOMM starts on Windows, finds the game through Steam, takes over existing mods, and installs, switches, updates and uninstalls real mods from the real registry.
- **The game loads the mods that NNOMM installs.** Copies of the real game were started several times. The `BepInEx/LogOutput.log` shows the loader that NNOMM installed (BepInEx 5.4.23.4) and the mods that NNOMM placed, for example `Loading [NOAutopilot 6.0.1]`.
- The first build did not start at all, and one popular mod could not be installed. Nine defects were found by running the app, and two more by the one review of the fixes. All eleven are fixed and checked again in the running app.
- Not checked: a start through Steam itself (see "What only the owner can do"), the automatic update of an installed build (there is no release yet), and the parts listed under "Not checked".

## How it was checked

- The real game folder was never used as the target. Two copies were made: one complete copy (with its BepInEx 5.4.23.5 and three plugins) and one copy without any loader, like a fresh Steam install. NNOMM worked only on these copies, with its own settings folder (`NNOMM_HOME`).
- The app was driven with real mouse and keyboard input, and every result was read from the screen and from the files on disk.
- The game copies were started directly from their folder. Steam was not needed for that: the game opens its main menu without it. "Play" in NNOMM was checked with the development switch `NNOMM_SANDBOX_LAUNCHER`, which starts the copy in place of the Steam address.
- After the checks, the real install had no NNOMM file in it and its loader files were unchanged, and NOMM's settings folder was byte-identical.

## Results

"Passed" means: done in the running app on Windows, result seen. "Fixed" means: it failed, was fixed, and passed afterwards. The pictures are in `windows-shots/`. Most were taken before the fixes, so some still show "version unknown" or a stuck download; [this one](windows-shots/13-library-after-the-fixes.png) is from the final build.

### Start, window, shell

| Check | Result |
| --- | --- |
| The built app starts | **Fixed.** The package took Java 21 from `JAVA_HOME`, the classes need Java 25: "UnsupportedClassVersionError", no window. |
| Window frame: drag by the title bar, double-click to maximize and restore, minimize, maximize, restore, close | Passed |
| Resize by left, right and bottom edge and by a corner; minimum size 720 x 560; narrow layout at that size | Passed ([picture](windows-shots/06-narrow-720.png)) |
| Snap with Win + Left, and back | Passed |
| Close by the title-bar button and by Alt F4: the process ends, placement is saved | Passed |
| Reopen at the same place and size | **Fixed.** A window that was never moved saved no position and reopened at the top-left corner. The window also grew by 1 px with every start. |
| A second start shows the first window | **Fixed.** A minimized window stayed minimized. A window behind other windows already came to the front. |
| Light and dark theme | Passed ([light](windows-shots/07-browse-light.png)) |
| Keyboard: Ctrl K command search with typing, arrow keys and Enter; Tab focus ring; Space on a focused sidebar entry acts once; arrow keys and Enter in a menu | Passed. Arrow keys of the number pad (Num Lock off) do not move the selection. |
| Maximized close and reopen; Windows theme and animation settings; other display scales | Not checked |

### Game folder and first start

| Check | Result |
| --- | --- |
| Find the game through the Steam registry key and `libraryfolders.vdf` | Passed for the default library. Secondary libraries and non-ASCII paths: not checked (this machine has one library). |
| First start shows the folder, the mods found and the loader; nothing changes before the player confirms | Passed ([picture](windows-shots/01-first-start.png)). The game folder was byte-identical after the confirmation. |
| Choose another folder with the Windows folder dialog | Passed |
| Take over existing plugins as they are | Passed: three loose DLLs came into the Library, switched on ([picture](windows-shots/02-library-after-takeover.png)) |
| Game version | **Fixed.** A real game always showed "Game version is unknown". Now "Nuclear Option 0.34.6". |
| Change the game folder in Settings | Passed. Nothing is written to the new folder until the next mod action. |

### Mods

| Check | Result |
| --- | --- |
| Real registry | Passed: 280 mods listed, search works ([picture](windows-shots/03-browse-real-registry.png)) |
| Install from the registry (zip) | Passed |
| Install from the registry (7z) | **Fixed.** NOAutopilot failed with "Could not download". Its 7z file uses the BCJ2 filter, which the library could not read. 86 registry versions are 7z files. ([picture](windows-shots/09-7z-mod-installed.png)) |
| A needed mod is installed with it | Passed (Configuration Manager came with NOAutopilot) |
| Switch off and on | Passed. The files leave and return byte-identical, also for taken-over DLLs. |
| Older version, update offer, update, "What's new" | Passed ([picture](windows-shots/04-mod-panel-update.png)) |
| Uninstall, with Undo offered | Passed |
| Add from a file, name with a space and accented letters | Passed |
| A failed download | **Fixed.** It stayed in the download list as a running download at its last percentage. Now "Failed", with Try again and Remove ([picture](windows-shots/10-failed-download.png)). Try again worked. |
| Changes while the game runs | Passed. NNOMM shows "Game is running", keeps the change, and applies it when the game closes ([picture](windows-shots/05-game-is-running.png)). |
| A mod DLL held open by another program | Passed. The other mods changed, the locked one stayed and was reported; when the file was free NNOMM finished the change ([picture](windows-shots/08-locked-file.png)). |
| A junction in place of a mod folder | Passed. NNOMM refused that entry, the files behind the junction stayed, another mod still changed. |
| File drop on the window; "Open the mod folder"; "Open its settings file"; the broken-mod test | Not checked |

### Mod loader

| Check | Result |
| --- | --- |
| Install BepInEx 5.4.23.4 when the first mod is turned on, in a game folder without a loader | Passed. `winhttp.dll`, `doorstop_config.ini` (`enabled = true`, target `BepInEx\core\BepInEx.Preloader.dll`) and `BepInEx/core` were placed. |
| The game uses that loader | Passed. Log: `BepInEx 5.4.23.4 - NuclearOption`, `4 plugins to load`, `Chainloader startup complete`. |
| An existing loader stays as it is | Passed (BepInEx 5.4.23.5 of the copied install was used and not changed) |
| Repair on another version | Passed: refused, files unchanged. **Fixed:** the refusal was shown twice. |
| Remove all mods | Passed. Mods and the loader files NNOMM installed were removed; game files, mod settings and logs stayed. **Fixed:** the Library footer still named a loader version afterwards. |
| Repair of 5.4.23.4; interrupted installs; no network or full disk during the install; the Doorstop tables (file encodings, unusual values, read-only and locked INI) | Not checked |

### Play

| Check | Result |
| --- | --- |
| Play with mods (through the development switch) | Passed. The game started, NNOMM showed "Game is running" and returned to Play when the game closed. |
| Play without mods | Passed. While the game ran, `doorstop_config.ini` had `enabled = false` and BepInEx wrote no log. After the game closed, the file was byte-identical to before. |
| Steam shows "playing" only while the game runs | Passed as far as it can be seen here. With Steam running and signed in, Steam's own state (`RunningAppID`) went back to 0 when the game closed, while NNOMM stayed open. NNOMM loads no Steam library, starts no helper process and ships no Steam file. |
| Start through `steam://rungameid/2168680`, with start options, with Steam open and closed | **Cannot be checked here.** Steam starts only its own install, and that install must not be used for these checks. See "What only the owner can do". |
| Keep, minimise or close NNOMM when the game starts; the mod log window switch | Not checked |

### Profiles and packs

| Check | Result |
| --- | --- |
| New profile with all mods off; switch between profiles | Passed. The plugin folder follows the profile. |
| Share a profile as a modpack (Windows save dialog, name with accents) | Passed |
| Open a `.nommpack` by its file type with NNOMM closed, and again with NNOMM open and minimized | Passed. The import preview opened once each time ([picture](windows-shots/11-pack-opened-by-file-type.png)). |
| A DLL given to an NNOMM that is already open | Passed ("already in your Library") |
| Import a pack; follow a pack address; pack settings | Not checked |

### Installer and updates

| Check | Result |
| --- | --- |
| Build the MSI | Passed, with two conditions: Node.js with npm must be found (`-Pcompose.electronBuilder.nodePath=<path of node.exe>`), and a version change needs a clean build, or the old package is kept. |
| Identity | Passed. Name and publisher "NNOMM", upgrade ID `b0eccd25-…`, folder `NNOMM\NNOMM`, shortcut group "NNOMM", file type `NNOMM.nommpack`, own icon. |
| Install for the current user | Passed (`ALLUSERS=2 MSIINSTALLPERUSER=1`): program in the user's program folder, Start-menu and desktop shortcut, file type. |
| Install for all users | Needs an administrator. A silent install without one stops with error 1603. With the normal dialog Windows asks for permission; that click was not possible here. |
| Upgrade 0.1.0 to 0.1.1 | Passed. One entry, version 0.1.1, shortcuts kept. |
| Beside NOMM 5.5.0 | Passed. Separate entries, folders and shortcuts. NOMM's settings folder was not changed, and NNOMM created none in `%APPDATA%` during the checks (its home was the sandbox). |
| Who owns `.nommpack` | The program installed last. After NOMM was uninstalled, the file type pointed to no program until NNOMM was repaired (Windows "Repair"). |
| Uninstall NNOMM | Passed. Program, shortcuts and file type are gone; the settings folder stays. |
| The app's own version | **Fixed.** Every build showed "0.1.0". |
| "Look now" for updates | Passed for what exists: a folder build says updates come from the releases page; an installed build says "Could not check for NNOMM updates", because no release exists. |
| Automatic update of an installed build, the beta channel, the installer's licence page and icons in the dialog | Not checked (no release; no dialog install) |

## What was fixed

Each was seen in the running app, fixed, and seen working in the running app.

1. The package uses the toolchain Java 25, so the app starts.
2. 7z files with the BCJ2 filter are unpacked (SevenZipJBinding in `core`, same limits as the other formats).
3. The game version is read from a real game.
4. A failed download shows as failed, with Try again and Remove.
5. A never-moved window keeps its position; the window no longer grows by 1 px per start.
6. A second start restores a minimized window.
7. The app shows its real version.
8. The Repair refusal is shown once.
9. The Library footer says "Mod loader not installed" when that is the case.

### The one review

One independent reviewer (GPT Sol) read the whole batch once. It reported four findings. Two were fixed and checked in the running app:

10. The 7z library is loaded once, under a lock, and tries again after a failed first load. Before, two unpacks that started together (an install beside a background check) could fail, and a full temporary folder at the first use broke 7z installs until a restart.
11. "Try again" and "Remove" in the download list act on their own row. Before, they found the download by the mod only, so with the same mod in two rows they could act on the other row.

Two are limits and are listed in [STATUS](STATUS.md): a hostile 7z file can use much memory before the size limit applies, and a game version with a letter at its end would show as unknown. The reviewer found the other parts sound. After that the area was closed.

## The tests on Windows

The full normal suite was run once at the end (`gradlew.bat :core:test :composeApp:jvmJar :composeApp:jvmTest --rerun-tasks`): 4.4 minutes. The 24 UI tests pass. Of 405 core tests, 385 pass and 20 fail. The separate crash tests (`:core:crashTests`) were not run.

All 20 failures are in the test fixtures, not in the app:

- 12 start `sh` or `true` as a stand-in for the game (`DoorstopRestoreTest`, `DoorstopRestoreNoticeTest`, `DoorstopSpecTest`, `LoaderOwnershipV3Test`, `RealIoTest`, `ReviewProbeTest`).
- 2 start a shell script named `NuclearOption.exe` (`Review3ProbeTest`).
- 2 use `/dev/shm` (`Review4ProbeTest`), 1 uses POSIX file permissions (`LoaderPartialPlacementTest`).
- 2 write a Windows path with backslashes into hand-made JSON, which makes that JSON invalid (`FollowupSafetyProbeTest`, `LoaderPendingRecoveryTest`).
- 1 expects a path without a drive letter (`RealIoTest.pathsArePrivate`).

What these tests are about (Play without mods and its restore, loader recovery) was checked in the running app where the tables above say so. The tests were left as they are; making them run on Windows is a separate decision.

The new tests of this run (`GameVersionTest`, `SevenZipUnpackTest`, `DownloadListTest`) pass, and each was seen to fail without its fix.

## What only the owner can do

One thing is left that needs the real Steam install: **a start through Steam**. NNOMM starts the game with the address `steam://rungameid/2168680`, the same way NOMM does, and Steam then starts its own install. Steps:

1. Sign in to Steam.
2. Install NNOMM (or run the folder build) and let it use the real game folder. The first start changes nothing; it lists the mods it will take over.
3. Press Play. Expected: Steam starts the game, NNOMM shows "Game is running", Steam shows "playing".
4. Close the game. Expected: NNOMM shows Play again within about three seconds, and Steam stops showing "playing" although NNOMM is still open.
5. Put a start option in Settings, Game, "Start options" (for example `-screen-fullscreen 0`) and press Play again. Expected: the game starts windowed.

Until this is done, the start through Steam is the one unproven step between "Play" and a running modded game. Everything after it is proven with the game copies.

## Not checked, and why

- **Rare file cases from the check list** (Doorstop file encodings and unusual values, interrupted loader installs followed by hand changes, two crashes in a row): not run on purpose. They are limits, not work; see "Known limits" in [STATUS](STATUS.md).
- **One ordinary fault each, not run:** no network or a full disk during a loader install, an antivirus lock. The matching code paths have tests on Linux only.
- **Needs something this machine does not have:** a second Steam library, a game on another drive than the settings, a display scale other than 100 %, a published release for the updater.
- **Not possible with synthetic input:** dropping files on the window.
- **Skipped to stay off the shared desktop:** actions that open Explorer or an editor ("Open the mod folder", "Open its settings file", "Open the game folder", "Open the mod log").
- **NOMM and NNOMM running at the same time:** NOMM was installed beside NNOMM but not started, because it would start its Steam helper under the owner's Steam account.

## Things seen that were left as they are

- A real game folder can hold BepInEx 5.4.23.5 (this one does). NNOMM uses it, but Repair works only for 5.4.23.4 and says so.
- NNOMM places a registry archive as it is, in a folder named after the mod (`plugins/<mod id>/…`). BepInEx found the DLLs in every case tried.
- In a game folder that already has NNOMM-managed mods, taken-over loose DLLs keep their place. In another game folder they are placed in a folder each.
- When a toggle fails because of a lock or a junction, the toast names the mod, but the "Problems" filter still shows 0.
- A tooltip of the sidebar can stay on top of a dialog that opened from a right-click menu.
- Once, two folder dialogs were open after two clicks on "Choose another folder". Not reproduced.
- `gradlew.bat :composeApp:run` (the development start) made Windows Firewall ask about "OpenJDK Platform binary". The packaged `NNOMM.exe` did not cause that question and has no listening socket.
- One time, an NNOMM window that was left open was gone about ten minutes later, with no error output, no crash file and no Windows error record. Another agent worked on the same desktop at that time. The cause is not known, and it did not happen again.
