# NNOMM status

NNOMM is a development build. Use an isolated settings home and a sandbox game folder with an empty `NuclearOption.exe`. It is not yet accepted for a player's real installation.

## What works

Browse, Library, mod details, Settings, profiles, command search, pack previews, sharing, first start and the broken-mod test are connected to the core. Both themes and narrow layouts are implemented. Servers says “Coming later”.

Real IO supports registry reads and cached lists, verified downloads, stored versions, managed game files, takeover, profiles, Undo, packs and loader preparation. File recovery and ownership rules protect player files. Removal keeps copies that cannot safely be downloaded again. Loader failures give a reason and a working Retry action.

The final loader, metadata and wording fixes are recorded in [Final items](notes/FINAL-ITEMS.md). Earlier message, menu and generator fixes are recorded in [Last findings](notes/LAST-FINDINGS.md). Earlier implementation and sandbox evidence is in [Closing fixes](notes/CLOSING-FIXES.md), [UI follow-ups](notes/UI-CORE-FOLLOWUPS.md) and [Last app fixes](notes/LAST-APP-FIXES.md). The file safety contract is in [Core IO](../core/IO.md).

## What is left

Independent Opus and Sol acceptance of the combined build is still required. This implementation task ran alone. Existing screenshots do not establish pixel equivalence with the mock.

Offline pack import can add selected bundled files before profile creation is refused; local-only offline import still needs work. Loose files adopted from the game are not later linked to registry entries automatically. The broken-mod test assumes one cause; interacting mods can give an inconclusive result.

No release or tag has been made.

## Windows checks

Linux cannot verify the native frame, installer identity, packaged updater and restart, Steam folder discovery, real launch with mods, locked DLLs, junctions, cross-drive recovery or delayed process detection. Pack replacement and cancellation also need the Windows file chooser. Use the full [Windows checklist](WINDOWS-CHECKS.md).

## Differences from the mock

Servers and its helper are excluded from the first version. The drop overlay uses real file drops without simulation controls. Linux uses Swing file choosers.

Ownership, recovery, removal and failed-storage messages explain the real safety rules. Pack previews can show several bundled-file warnings and optional settings. Replacing an exported pack keeps a previous copy. Missing-folder first start says what to do next. Stop following really stops future pack checks.

Font shaping, line wrapping and Browse strip spacing still differ in some pictures.

## Decisions made without the owner

Keep local, taken-over and unknown-origin copies when removing mods. Keep previous exported packs without automatic deletion. Included pack files start off. Unknown game versions use the newest registry game version for warnings; unavailable exact mod versions are not substituted.

Removal without a selected folder explains how to remove any files left in a game folder, without claiming a folder was previously selected. Loader Retry uses the folder currently selected. Visible metadata requests skip cards that have scrolled away; a request already running can finish. Recovery copies are reused only for the same source version.

No background helper, service or startup change was added. Detailed decisions and checks are linked above.
