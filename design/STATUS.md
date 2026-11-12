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

- Integrate and review the separate final core fixes for game-exit apply authorization, takeover preservation, Clear retention and registry freshness. Do not treat this UI/updater pass as proof that those fixes are complete.
- Independent Opus and Sol review of the final combined code and pictures. This implementation pass runs alone, so it does not supply those reviews.
- Run the Windows checks below on packaged builds, then perform the final sandbox acceptance pass on the integrated branch.
- Offline pack imports can add selected bundled files to the Library before the core rejects profile creation with “Needs a connection”. The current core requires a connection for pack import, including local-only packs; this remains a follow-up.
- Loose takeover payloads are not automatically linked to registry identities by a later hash match. They remain local files unless identified during takeover.
- The broken-mod test assumes one cause; interacting mods can produce an inconclusive result rather than a guessed culprit.

## Windows-only checks

`design/WINDOWS-CHECKS.md` is the detailed checklist. Linux cannot verify the custom native frame, installers and separate application identity, packaged self-update/restart, Steam library discovery or real game launch with mods. Also check Windows locked DLLs, junctions/reparse points, recovery across drives, private config transitions, loader ownership and removal, and delayed process detection. The Atom beta path and manual unsupported-update feedback need packaged Windows checks too. No release or tag is authorized by this status.

## Deviations from the mock

- Servers remains “Coming later”; server joining and the Steam helper are excluded from the first version.
- Archivo shaping can change line wraps and baselines between Compose and Chromium. Browse strips reserve a footer line; the final two-card strip is about four pixels taller than the mock.
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
