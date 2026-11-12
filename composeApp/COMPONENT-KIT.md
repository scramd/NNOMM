# NNOMM UI foundation

The kit is independent of `:core`. It uses Compose foundation, without Material.

## Theme

Wrap content with `NnommTheme(dark, textPercent, comfortable, reduceMotion)`.
`textPercent` is a percentage (90, 100, 110 or 125); `comfortable` chooses 40 instead of 34 dp rows.
Read `LocalNnTheme.current.colors` for the CSS tokens. Hyphenated CSS names become camelCase.
`shadow` is the CSS shadow colour; its geometry is `shadowBlur` and `shadowOffsetY`.
`text(size, weight, width)` creates an Archivo style with tabular figures. Width 112 is for headings,
100 for text and 88 for numeric values. The title wordmark uses 122, as in the mock.
The bundled variable font retains both axes. `archivo(width)` registers real width/weight variations;
it does not scale glyphs or add letter spacing.

`panelMillis`, `controlMillis`, and `progressMillis` become zero when motion is reduced.
Use the theme's row height rather than hardcoding rows in future screens.

## Components

Public composables live in `nnomm.ui.kit`. Names describe their role: Button, IconButton,
Switch, CheckBox, Radio, Chip, FilterOption, SegmentedControl, LinkButton, TextField,
Select, Menu, Dialog, Toast, Badge, Tag, Note, ProgressBar, DownloadPill, KeyCap,
Tooltip, ModTile, Skeleton and ScrollArea. `Label` is deliberately not selectable;
text fields support selection. Wrap readable document text in foundation SelectionContainer where needed.

Buttons accept `ButtonKind`, `ControlSize`, optional vector, enabled state and a deterministic
`PreviewState`. Every control uses a hand cursor and standard Compose keyboard activation.
`NnIcons.all` and `NnIcons.pictures` expose the mock's named vectors.
`tileHash` uses unsigned 32-bit overflow. `ModTile` accepts id, display name, optional picture key,
and TileSize (Small 22, Normal 28, Medium 40, Large 56).

Place a Menu inside the same Box as its anchor. Its popup position provider flips left/up at the
window edge and clamps within the window. MenuEntry supports Item (hint, icon, danger, enabled,
action), Heading and Separator. Escape and outside click dismiss; arrows move focus, Enter/Space activate.
Select is a menu-backed dropdown. Dialog is a focusable full-window popup with a scrim; place it last
inside the app's full-size Box. DialogSurface and MenuSurface support static galleries.

`WindowShell` supplies the title bar and body slot. Pass command, minimize, maximize, close
callbacks and a drag modifier from the native window layer. The live window uses Nucleus WindowScaffold to register the title height with native code; shots use the equivalent WindowShell layout. Command clicks and Ctrl K both call
`LaunchRequests.onCommand`; file launches queue in `LaunchRequests.pendingFiles` until the import
screen is connected. No domain work starts in this shell.

## Render states

Run `tools/gradlew-locked :composeApp:renderShots`. No display or GPU is needed.
Add a `ShotSpec(name, dark, width, height) { content() }` to `Shots.registry`.
Theme and reduced motion are applied by the renderer. The content should include WindowShell
if the shot is a whole window. Defaults are 1328 x 792; narrow states use 728 x 720.
These are app frames: wide mock screenshots have a 16 px horizontal and 14 px top margin.
The remaining page area contains test buttons, outside the app. `tools/compare-shots` crops those
margins before appending a matching mock and render. The initial kit/window names have no matching
mock files, so the comparison tool correctly skips them.

For desktop inspection run the app with `--kit` for the interactive gallery and `--light` to force
light mode; the normal shell is intentionally empty. `NNOMM_HOME` overrides its private settings root.

## Validation and remaining checks

The desktop gallery has been exercised with CPU Skia on X11: native dragging, minimize/restore,
maximize/restore, close, opening a dialog, Escape dismissal, and dropdown arrow/Enter selection.
The headless gallery demonstrates the applied Archivo width axis. Tile hash and initials fixtures
come from the mock's JavaScript, including unsigned overflow and an astral Unicode character.

This is an implementation handoff, without the independent Opus/Sol reviews. The initial gallery
and empty-window shot names have no reference PNGs, so there is no whole-screen pixel equivalence
claim. Compare future screen shots against their matching mock names. Font rasterization and shadow
blur can differ between Chromium and Skia even with the same inputs; these still need visual review.
Windows packaging, native caption behaviour and updater execution need the checks in
`design/WINDOWS-CHECKS.md`. The inherited app artwork is retained. Command search and pack imports
are callback/queue seams only; the palette, import screen and domain module are separate work.

## Tables and selection

`Table(keys, modifier, columns = LibraryColumns, selection, header, cell)` supplies a lazy,
scrollable table with a sticky 30 dp header. `header(columnId)` and `cell(key, columnId)` draw
only the visible columns. `LibraryColumns` is the mock's column template; source and size disappear
when the table's available width is at most 800 dp. Rows use the theme's 34/40 dp height.
Pass stable mod IDs as keys, including after sorting. Pass `isOff` for the off appearance, and use
`TableValue` and `NameCell` in cells to inherit the off text colour and weight. `ModTile(off = true)`
and the switch remain explicit so their appearance also works outside a table.
`NameCell` reserves space for badges; pass `Badge(modifier = Modifier.weight(1f))` within its
badge slot when multiple badges must share limited space. Badge and Tag accept modifiers and clip.

`rememberRowSelection<Id>()` provides `picked`, `focused`, and `anchor`.
Ctrl-click toggles; Shift-click extends from the anchor; Ctrl+Shift adds a range.
An ordinary row click focuses and opens the item, as in the mock, without changing the selection.
The table handles arrows, Home/End, Shift ranges, Ctrl+A and Escape. `onToggleSelection`, `onOpen`, and
`onDelete` receive Space, Enter, and Delete. Space and Delete use the selection or fall back to
the focused row. The default bulk toggle calls `onToggle` for each key; pages should supply
`onToggleSelection` to turn a mixed selection uniformly on or off. A moved row is brought into view below the header.
Selection survives sorting and drops keys removed by filtering. Pages own the actual domain actions.
`LazyList` exposes the same themed scrollbar without table semantics; `tableKeyboard` and `TableRow`
are available for custom tables. Call `RowSelection.retain` when filtering custom lists.

## Text, cards and other screen pieces

`Label` accepts String or AnnotatedString, `maxLines`, `overflow`, and `textAlign`.
Use `Label(highlightedText(name, indices), maxLines = 1)` for mock search marks, and
`maxLines = 2` for clamped descriptions. Highlight indices are UTF-16 positions, as in JavaScript.
`highlightedText` also has a non-composable overload that takes mark and ink colours.

`CardGrid(items, key) { ModCard(...) }` lays out lazy columns of at least 228 dp, with 8 dp gaps.
`CardStrip(items, key) { ModCard(strip = true, ...) }` uses 244 dp cards and the right-edge fade.
`ModCard` supplies the tile, title, author, clamped description, tags, match reason and footer slot.
Its current state and hover follow `.card`. `Skeleton` works in either container, including a static
gradient under reduced motion. `PanelTabs`, `OptionCard`, `NavItem` (count, icon, profile dot/initial,
and tail slot), and `EmptyState` supply the other common screen elements. `Note` accepts any icon
and top-aligns its icon and action with multi-line text. `ModTile(imageUrl = ...)` overlays a Coil 3
image on the fallback tile: the initials remain visible during loading or on failure. Coil handles
fetching and decoding away from the UI thread. `off` desaturates the picture and dims the whole tile.

`FilterOption(radio = true)` uses a radio glyph. Both forms have one click target and Tab stop.
`SegmentedControl` supports `small` and a list of `Segment(title, icon, iconOnly)`; titles are tooltips.
`TextField` supports `bg`, `focusRequester`, `autofocus`, `trailing`, and the 44 dp `palette` variant.
Escape consumes the event only when it clears a nonempty field, leaving the next Escape for the shell.
`SplitButton` supplies ordinary and Play geometry; `Button` also exposes shape and iconOnly for
custom halves. `Icon(strokeWidth = ...)` changes stroke geometry without changing the source vector.

## Shared focus mode: shell integration required

Create one `remember { FocusMode() }` per window. Provide it with
`CompositionLocalProvider(LocalFocusMode provides mode)` around the whole window and install
`Modifier.windowInputMode(mode)` on its outermost container. Include the in-window overlay layer
in that provider. Controls then show the CSS focus ring only in keyboard mode; any pointer press
changes all controls to pointer mode. This prevents stale rings on controls previously reached by Tab.
The ring has a 1 dp gap and 2 dp stroke. Do not clip parent containers around focusable controls;
leave space between the control and scrolling viewport boundaries. The gallery installs this mode;
the application shell must install it separately. Popup windows need their own event forwarding.

## Kit verification and pictures

The gallery has Controls, 500-row table and Cards pages. The render harness adds
`kit-table-dark/light`, `kit-table-narrow-dark/light`, and `kit-cards-dark/light` alongside
`kit-dark/light`. Run `uv run --with playwright --with pillow tools/kit-reference.py` from the
repository root after rendering. It captures Browse and Library mock states at both widths and
in both themes, and creates same-data table/card CSS specimens with side-by-side crops in
`composeApp/build/shots/compare` (mock on the left). These are primitive comparisons, not completed
screens. The desktop table has been exercised through row 500, Shift range selection, Ctrl+A,
Home/End and wheel scrolling. This is a manual responsiveness check, not an FPS benchmark.
The app was closed after checking. RowSelection tests cover range anchoring, additive selection,
toggling, filtering and key identity through sorting.

Known limits: Chromium and Skia have different text rasterization. Highlighted Label draws grouped mark
backgrounds with the mock's 2 dp rounded corners. Profile initial/dot visibility is a page/shell
choice (initial for the narrow rail, dot for the wide sidebar). Card footers remain page-owned slots.
Remote image success/error rendering has not been exercised against the live registry in this pass.
Independent Opus/Sol review is still required by the project before this work counts as done.
