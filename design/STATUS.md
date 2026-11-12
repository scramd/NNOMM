# NNOMM build status

NNOMM has the agreed pages and real IO integration. It is still a development build: use a sandbox with an empty `NuclearOption.exe`, never a real game installation. The latest acceptance fixes still need independent Opus and Sol review together with the parallel core safety changes.

## Works now

- Compose Desktop shell and component kit, dark/light themes, narrow layout, Browse, Library, mod details, Settings, profiles, command search, pack previews, sharing, first start and the broken-mod test. Servers says “Coming later”, as agreed.
- Real runtime: registry and cached data, verified downloads, version storage, managed game placement, takeover, persistence, Undo, packs, BepInEx preparation and Steam URI launch. The sandbox checks and earlier safety rounds are recorded in `design/notes/` and `core/IO.md`; these are not Windows certification.
- Pack imports have separate, initially unchecked Include switches for bundled payloads. Warnings name the files, the summary counts bundled entries, and selected files enter the Library off. Settings import is also optional.
- Stop following a pack clears that profile's follow address and pending update marker, persists across reopening, and keeps its mods, versions and pack membership. A check already in flight cannot restore the marker after stopping.
- Beta updater discovery reads published tags from GitHub's releases Atom feed, including numbered beta tags, then uses release metadata assets. It makes no unauthenticated GitHub API call. Stable updates keep the latest-release asset path.
- Manual “Look now” checks answer even when automatic checks are disabled. Unsupported development/package formats offer the releases page. Startup checks do not announce unsupported formats, failures or an already current version.
- The danger-zone label and help explain ownership before confirmation: managed mods and loader files NNOMM installed are removed; adopted loader files, player files and recovery copies stay. The completion text uses the same ownership limit.

## Remaining work

- Review the integrated final core fixes for game-exit apply authorization, takeover preservation, Clear retention and registry freshness. Those changes are present; this acceptance pass does not replace their independent safety review.
- Independent Opus and Sol review of the final combined code and pictures. This implementation pass runs alone, so it does not supply those reviews.
- Run the Windows checks below on packaged builds, then perform the final sandbox acceptance pass on the integrated branch.
- Offline pack imports can add selected bundled files to the Library before the core rejects profile creation with “Needs a connection”. The current core requires a connection for pack import, including local-only packs; this remains a follow-up.
- Loose takeover payloads are not automatically linked to registry identities by a later hash match. They remain local files unless identified during takeover.
- The broken-mod test assumes one cause; interacting mods can produce an inconclusive result rather than a guessed culprit.

## Windows-only checks

`design/WINDOWS-CHECKS.md` is the detailed checklist. Linux cannot verify the custom native frame, installers and separate application identity, packaged self-update/restart, Steam library discovery or real game launch with mods. Also check Windows locked DLLs, junctions/reparse points, recovery across drives, private config transitions, loader ownership and removal, and delayed process detection. The Atom beta path and manual unsupported-update feedback need packaged Windows checks too. No release or tag is authorized by this status.

## Deviations from the mock

- Servers remains “Coming later”; server joining and the Steam helper are excluded from the first version.
- Archivo shaping can change line wraps and baselines between Compose and Chromium. Browse strips reserve a footer line and space below cards for the hover scrollbar; their section spacing can be slightly taller than the static mock.
- Real packs can add optional settings and multiple bundled-file warnings, so their preview is taller than the single-file mock. Bundled files show readable payload names rather than internal content ids; the demo warning no longer repeats `.dll`.
- Removal wording names the ownership boundary instead of promising a plain game. Stop following now performs the action instead of the mock's no-op.
- The drop overlay uses native drops and omits mock-only simulation controls; its centered group sits slightly lower.

## Decisions without the user

- Servers settings retain only sidebar visibility, with “The server list comes in a later version.” The page says “A list of dedicated servers comes in a later version of NNOMM.” The alternative is hiding the category/tab.
- Unknown game versions compare with the newest version named by the registry. Missing exact mod versions report unavailability instead of substituting downloads.
- BepInEx installation reports the installed version. Installer vendor is “NNOMM”.
- Hiding profiles selects Default immediately; placement waits for a mod action or Play. Console preferences save immediately and apply at Play. A start with no observed game process times out with a retry notice.
- Bundled pack payloads stay off when imported, even after Include; Include authorizes adding them to the Library. Players turn them on separately, matching the design's initial safety state.
- A manual updater check bypasses “Never” for that request and never auto-installs. Unsupported builds offer the existing releases-page action. Automatic checks retain the saved preference.

## Reproduce checks

Use JetBrains JDK 25 and `tools/gradlew-locked` for builds. Run `:core:test :composeApp:jvmTest`, then `:composeApp:renderShots` and `tools/compare-shots`. Compare images are under `composeApp/build/shots/compare/`. Run real-mode checks with an isolated settings home and a temporary sandbox game copy; use a separate virtual display when other agents use the desktop. Reports must distinguish exercised behavior from remaining Windows and independent-review work.

## Final acceptance implementation (O1–O5, O7–O12)

- Browse New and First release use the oldest registry version's date, fetched through the single-slot metadata queue and retained in the permanent successful HEAD cache. Unknown first dates stay out of New and show Unknown in the panel.
- Remove everything keeps local, taken-over and unknown-origin stored mods in the Library, off in every profile. Their stored copies stay. Confirmation and completion say this explicitly; only registry-download-only entries leave the Library.
- Linux file, folder and save choosers use Swing's event thread from an IO coroutine, avoiding the GTK save implementation. Windows retains FileKit.
- Card tag rows place only whole chips. Strip scrollbars appear during hover or scrolling with room below the cards.
- Empty pack authors omit the by phrase in previews and update notices. Release notes omit feed headings and Full Changelog links; release dates use relative wording.
- Library size counts installed stored versions, excluding hidden/recovery copies. Storage settings still report total disk use. Disk actions refresh both totals; a usage-read failure does not fail the action.
- Registry tags use sentence case, preserving HUD, UI, QoL, VR, AI, PvP, PvE, FPS, ATC and AWACS. Filter order follows current counts.
- Zero-update checks say All mods are up to date. Zero-download imports omit the download count. Singular years, registry freshness, report takeover labels, report help, non-breaking storage units and demo-mode notices use player wording. Runtime startup discards transient downloads; an opened empty queue says No downloads.
- The sandbox launcher is explicitly marked development-only and remains inert without its environment variable and isolated-home guard. Acceptance notes contain no temporary absolute path.

### Decisions for this pass

- First-start heading with a selected folder: “Check your game”. With no folder, or a missing folder: “Game folder not found”, including the game row. This avoids claiming automatic discovery after a hand-picked folder.
- Unknown stored provenance is retained in the Library by Remove everything, because a registry identity does not prove that its stored bytes can be downloaded again.
- Feed section headings are omitted rather than restyled. Notes retain the actual release text and relative dates.

These are deliberate wording and behavior deviations from the older mock where it promises removal of every mod, shows mock-only notices or empty author phrases, or keeps a fixed tag-filter order. Existing font shaping/wrapping differences remain. Independent Opus/Sol review and Windows checks remain the lead's work; this implementation agent works alone.
