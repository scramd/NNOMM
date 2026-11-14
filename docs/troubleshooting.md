# Problems and questions

Find your problem below. If it is not here, make a problem report: **Settings > About and updates > Problem report > Copy report**. The report has your mod list, the versions, NNOMM's own errors (from `nnomm.log` in its settings folder, `%APPDATA%\NNOMM` on Windows) and the last game log. Check it before you send it to someone: it includes your game folder and messages from mods.

**Contents**

- [NNOMM does not find the game](#nnomm-does-not-find-the-game)
- [The mods do not load in the game](#the-mods-do-not-load-in-the-game)
- [The game crashes or something is wrong](#the-game-crashes-or-something-is-wrong)
- [A download fails](#a-download-fails)
- [I cannot install a mod](#i-cannot-install-a-mod)
- [A mod change failed](#a-mod-change-failed)
- [My changes are not in the game](#my-changes-are-not-in-the-game)
- [Questions](#questions)

## NNOMM does not find the game

NNOMM finds the game through Steam on Windows. If it does not:

1. Go to **Settings > Game > Game folder** and click **Choose folder**.
2. Select the folder that contains `NuclearOption.exe`. In Steam you find it with: right-click Nuclear Option > **Manage** > **Browse local files**.

On Linux NNOMM does not search: always choose the folder yourself. It is usually `~/.local/share/Steam/steamapps/common/Nuclear Option`.

"Game version is unknown" means NNOMM could not read the game version. NNOMM then compares mods with the newest game version that the registry names.

## The mods do not load in the game

1. **Is the mod on?** Look at its switch in the Library, in the profile you use. The Play box shows the profile in use.
2. **Is the mod loader installed?** The bottom of the Library shows "Mod loader BepInEx" and its version. If it is not there, turn on a mod: NNOMM installs the loader then.
3. **Is the loader damaged?** **Settings > Game > Mod loader > Repair**. Repair works only for BepInEx 5.4.23.4, the version NNOMM installs. If you have another version, NNOMM uses it as it is and does not change it.
4. **Linux with Proton:** in Steam, right-click Nuclear Option > **Properties** > **Launch options**, and type:

   ```
   WINEDLLOVERRIDES="winhttp.dll=n,b" %command%
   ```

   Without this, Proton does not load BepInEx.
5. **Look at the mod log.** Play menu (the arrow beside Play) > **Open the mod log**. Or turn on **Settings > Game > Show the mod log window while the game runs**. Errors from mods are in it.

## The game crashes or something is wrong

1. Start once with **Play without mods** (Play menu). If the problem is still there, a mod is not the cause.
2. If the problem is gone without mods: Library > three dots > **Find the mod that breaks the game**. NNOMM turns mods off in halves and asks you each time if the problem is still there. At the end it names the mod, you choose to keep it on, leave it off or remove it, and your other mods are back as they were. See the [user guide](user-guide.md#find-the-mod-that-breaks-the-game).
3. Look for badges in the Library, or use the **Problems** quick filter. "Conflicts with" means that two mods do not work together: turn one of them off.
4. A **Made for** badge means that the mod was made for another game version. It can still work, but after a game update it is a good first suspect.

## A download fails

- The download shows **Failed** in the sidebar. Click **Try again** (the circle arrow beside it).
- With no connection, NNOMM shows the saved list of mods with a notice, and the Install buttons do not work (point at one: "Needs a connection"). Your installed mods still work, and you can still play.
- If a download fails again and again, the file at the mod author's address may be gone. Look at the mod's page on GitHub (link in the mod panel, **About**).

## I cannot install a mod

- **Install does not react, "Needs a connection":** NNOMM is offline. See above.
- **"Unavailable":** point at the button to see why. Usually the mod's file uses an archive format that NNOMM cannot install yet (`.rar`).
- **An old version is not available:** the registry no longer has it. NNOMM never puts another version or another download in its place.
- **Mods only for servers** are hidden in Browse. To show them, turn off **Settings > Registry > Hide mods that are only for servers**.

## A mod change failed

A message names the mod, and the mod gets a badge with the reason. Usually another program uses a file of the mod (for example an antivirus scan or a file viewer).

1. Close the program that uses the file.
2. Click **Retry**.

NNOMM changes one mod at a time. When one mod fails, the other mods still change. Your files are kept.

## My changes are not in the game

- **While the game runs**, NNOMM keeps your changes and applies them when the game closes. Close the game and start it again.
- Check that you play with the right **profile**. Each profile has its own mods on.

## Questions

**Does NNOMM need my Steam account?**
No. NNOMM never signs in to Steam and runs no Steam helper program. It starts the game through Steam in the same way as a desktop shortcut. Steam shows you as playing only while the game runs.

**Does NNOMM change my game?**
Only when you install, uninstall or switch a mod, or click Play. It puts mods into `BepInEx\plugins` and installs BepInEx if it is missing. It never touches files that it did not place or take over.

**Where are my mod settings?**
Mod settings are the values you change in the game, for example with F1 (BepInEx Configuration Manager). NNOMM keeps them for each mod. A profile can keep its own mod settings: **Settings > Profiles**. To open the settings file of one mod: its row's three dots > **Open its settings file**.

**What does "From game folder" mean?**
The mod was already in the game folder at the first start, as a loose file. NNOMM took it over as it is. NNOMM does not match such files to registry mods later. To get updates for it, uninstall it and install the mod from Browse.

**Can I use NNOMM and NOMM together?**
You can install both. NNOMM has its own settings folder. Use one of them at a time for a game folder. Both register the `.nommpack` file type: the one installed last opens these files.

**How much disk space do the mods use?**
**Settings > Storage** shows it. **Clear** (with the game closed) removes stored versions that no profile uses. It keeps the number of old versions you chose, and the last copy of every file that cannot be downloaded again.

**I removed a mod by mistake.**
Click **Undo** in the message, or press Ctrl Z. After the message is gone, install the mod again from Browse. For a mod that you added from a file, add the file again.

**Is the Servers page broken?**
No. It says "Coming later": a list of dedicated servers comes in a later version.

**Can I import a modpack offline?**
No. NNOMM needs a connection to download the pack's mods. When NNOMM has no connection to the registry, the import window says so and **Import** stays off. Click **Try again** in that window when you are online.
