# Review B fixes

Branch: `t3/fix-browse-settings-dialogs` (kept the assigned implementation branch).
First-start implementation and text files are untouched. No push, tags, release, or external activity.

## Finding ledger

Each line uses the numbering in the two supplied reviews. “Fixed” describes the implementation; verification limits are below.

- Opus 1 / Sol 1: fixed enum-based broken-mod phases, including INCONCLUSIVE; fixtures now cover intro, base, test, found, no-mod, and inseparable dependencies in both themes and widths.
- Opus 2 / Sol 2: fixed all unversioned demo registry pack entries; new import is 5 owned, 3 alternate versions, 8 downloads; add mode has 1 conflict. Missing versions classify as SAME only when installed, otherwise UNAVAILABLE, and cannot queue a null-version download. Added core regressions.
- Opus 3 / Sol 8 / lead Settings actions: fixed Find again, Move, Clear, usage refresh, Reset settings, Remove everything wiring; demo no-op actions use the exact mock toast. Unsupported registry changes and keyboard edits are disabled in real mode. Report export gives a plain unavailable sentence. No visible TODO(core) text remains.
- Opus 4: fixed game-folder local draft, Enter/blur commit, Escape restore; core writes the requested setting immediately and accepts only the newest inspection result. Added an out-of-order inspection regression.
- Opus 5 / Sol 5: fixed palette query and index reset whenever opened; mock-address seeding sets its query after opening.
- Opus 6: fixed kit fields to retain TextFieldValue selection and place the caret at the end of initial or externally replaced text.
- Opus 7: fixed forward and reverse focus wrapping within dialogs; removed the extra container focus stop. Confirmation dialogs explicitly request Cancel.
- Opus 8 / Sol 7: fixed clipboard toast and save toast with Show the file, using store toasts; demo Save gives the mock no-op toast. Save success no longer contaminates Update NNOMM status.
- Opus 9 / Sol 6: fixed real pack-update preview to fetch through the real PackSource and use core packUpdateRows. Stop following now gives the mock toast in demo and is disabled in real mode because no stop-following core action exists.
- Opus 10: fixed native/drop import entry to reset mode to new profile.
- Opus 11: not changed, expressly assigned to the first-start agent.
- Opus 12: fixed action-specific palette icons, page-specific icons, and no profile icon.
- Opus 13 / Sol 4, 10: fixed wrapping palette arrows, scroll only when the target is outside the visible list, and footer keycaps grouped with their labels.
- Opus 14: fixed palette update lookup feedback to supply Show targeting Library Updates; it refreshes without the core's separate announcement toast.
- Opus 15: fixed short strip width and fade only when more cards remain to the right.
- Opus 16: fixed mouse access with a horizontal scrollbar on card strips.
- Opus 17 / lead Settings dimensions: retained the existing 740 dp body cap; added lead width cap and strong current-profile selector surface, border, and weight.
- Opus 18: fixed profile-own-value hint as an underlined 12 px link.
- Opus 19: fixed full mock loader help and checked-update text in demo; real mode keeps verified loader/version information. Demo paths remain sandbox paths deliberately, never a real game folder. Open and Look now now give demo feedback.
- Opus 20: fixed filter chip close glyph to the kit's 16 px x vector.
- Opus 21: fixed pack byline in the header, bottom-only diff separators, removed-name strike-through, vector warning/update icons, segmented conflict choice.
- Opus 22: fixed dialog field labels to normal weight and secondary ink.
- Opus 23: fixed Servers title/empty-state sizes and header padding, removed server from command placeholder, and included Servers in palette page results when enabled.
- Opus nit facet radios: fixed radio glyph to 14 dp and filter option's 4 dp left alignment; reduced facet heading minimum height.
- Opus nit On: fixed Browse On label to 13 px.
- Opus nit Tags Clear: fixed missing tooltip.
- Opus nit Made for: fixed selected label to primary ink.
- Opus nit select chevron: fixed weighted select label so the chevron stays at the right edge.
- Opus nit new-profile option spacing: retained the existing 56 dp minimum option targets and mock padding; no further spacing change made without a measured discrepancy.
- Opus nit first-start row borders/text: not changed, expressly excluded.
- Opus nit trouble intro: fixed last paragraph to faint ink.
- Opus nit offline note vertical centring: fixed with an explicit centred Note layout for the Browse offline notice.
- Opus nit narrow Settings: fixed forced stacking; ordinary controls remain beside labels, while path/source rows retain the wide layout.
- Opus nit whole-state observation: Settings and palette project out toast/download changes before collecting and comparing state.
- Opus nit Settings search remount: fixed query removal from the category body key.
- Sol 3: fixed new profile navigation to reset Library filter to All.
- Sol 9: removed hard strip minimum height and matched section spacing including the grid gap; All mods remains present at narrow width. Small accumulated vertical/font-metric differences remain in the Browse comparison; exact pixel parity is not claimed.

## Checks and evidence

- Build and tests use only `tools/gradlew-locked`, JetBrains JDK 25.
- Three new `PageBRegressionTest` cases cover import counts, no null-version download, and reversed completion of game-folder inspection.
- Full core and Compose JVM tests and the complete render suite passed; final combined build/test/render log is `/tmp/b-complete.log`.
- Render output: `composeApp/build/shots/`; comparisons: `composeApp/build/shots/compare/`. Main affected names: 01, 02, 03, 08, 10–15, 19, 20, 23–28, 32, 36. New phases use `dialog-trouble-{intro,base,test,found,nomod,inconclusive}-{dark,light}-{wide,narrow}.png`.
- Desktop checked on isolated Xvfb :59, 1600x1000. Normal launch hit another instance's lock; setting java.io.tmpdir to a temporary folder isolated the test without disturbing it. Window and settings stayed under `<sandbox>`.
- Ran Start -> base -> successive tests -> Found -> Keep it on; the enabled count returned from 16 to 17. Typed in new-profile and all Share fields; prefilled values appended at the end. Exercised palette typing/reopen and forward/reverse Tab in the trouble dialog. Checked game-folder typing/Escape and both-width Servers display. Opened Reset confirmation and used its keyboard controls; demo confirmation produced the mock no-op toast.
- Desktop evidence: `/tmp/b-{desktop,pal2,trouble-key,wrap,test,test2,test3,test4,found,new,share-typed,reverse-ok,forward-ok,settings-runtime,confirm-runtime,confirm-reverse,servers-narrow}.png`.
- INCONCLUSIVE was checked through actual dependency-cycle render fixtures; it was not driven through the running window. Not every dialog/action and Settings field received a separate desktop keyboard check. Rename/import/pack-update/light-theme interaction checks remain for integration review.
- A render attempt failed in Compose SnapshotStateObserver with a multithread access exception; a retry rendered the complete suite successfully. An intermediate focus-direction implementation crashed under reverse Tab; it was replaced and reverse Tab was rechecked successfully.

## Core boundaries and remaining integration

The only extra core API change is making the existing toast method public. This minimal visibility change was needed to satisfy the request that UI feedback use store toasts; it adds no new toast implementation. Remaining TODO(core) comments concern bundled pack settings/config application, not player-visible UI. Real stop-following, report export, custom registries and editable shortcuts still need core support. Windows-specific paths were not changed or verified. This implementation was performed alone as requested; no new Opus/Sol review was launched.
