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
Select is a menu-backed dropdown. Dialog is an in-window layer with a scrim; place it last
inside the app's full-size Box. DialogSurface and MenuSurface support static galleries.

`WindowShell` supplies the title bar and body slot. Pass command, minimize, maximize, close
callbacks and a drag modifier from the native window layer. The live window uses Nucleus WindowScaffold to register the title height with native code; shots use the equivalent WindowShell layout. Command clicks and Ctrl K use `AppModel.openOverlay("palette")`; file launches feed `AppModel.acceptFiles`.
See `design/SHELL-INTEGRATION.md` for page keys, fields and overlay factories.

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
light mode; normal mode renders the integrated shell around page entry points. `NNOMM_HOME` overrides its private settings root.

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
