# Shell integration contract

`AppModel` is provided as `LocalApp.current`. Collect `model.store.state` for domain state. All UI state uses Compose snapshots. The shell owns navigation, the 400 dp panel host, overlay placement, downloads, toasts and native file routing.

## Pages and keyboard

Implement `BrowsePage()`, `LibraryPage()`, `SettingsPage()` and `ModPanel(ref)` in their existing packages. The main page remains composed while a panel opens, preserving list scroll state. The panel overlays it at window widths up to 940 dp.

Register keys and search focus with a `DisposableEffect`:

```kotlin
DisposableEffect(model) {
    val unregisterKeys = model.registerKeys(Page.Library) { event ->
        // Library arrows, Space, Enter, Delete and Ctrl A.
        false
    }
    val unregisterSearch = model.registerSearch(Page.Library) { searchFocus.requestFocus() }
    onDispose {
        unregisterKeys()
        unregisterSearch()
    }
}
```

Every editable field must set `ui.focusedField = FocusedField({ query }, { query = "" })` on focus and clear it on blur. This lets the central handler distinguish typing, clear the focused field with Escape, and avoid stealing Undo and `/`. Kit text-field integration belongs to the controls agent; the shell does not edit Controls.kt.

`model.shortcut(event)` is the only global shortcut handler. The native window and anchored popup content route keys to it. The shell handles Ctrl K, Ctrl Enter, Ctrl 1/2/3, Ctrl comma, Ctrl Z, `/`, and Escape in the mock order. Page handlers run only outside overlays, typing and menus. Register palette arrows/Enter (including Ctrl Enter's alternate action) with `registerOverlayKeys("palette", handler)`.

## Overlay factories

Fill `model.overlayFactories[kind] = { app -> YourOverlay(app) }`. Each result implements `Overlay.Content()`. Use `model.openOverlay(kind)` and `model.closeOverlay()`. Missing factories do nothing. Factory keys are `palette`, `import`, `packupdate`, `newprofile`, `rename`, `share`, `trouble`, `confirm` and `first` as needed. `AppLayers` spans the whole window including the title bar.

The factory reads `ui.overlayProfileId` for profile actions; import reads `ui.importFile` and `ui.importMode` (`new` or `add`). Native file drops, command-line paths and single-instance file links all use `model.acceptFiles(files)`. Pack paths stay in state even when no import factory exists. `.dll`, `.zip`, `.7z` call `store.addLocal`.

Use the in-window `Dialog`, with `initialFocus` pointing to the first field or button and `leftFooter` for footer content that stays left. Dialog content scrolls and starts 52 dp below the top of the window. Keep command search in this same layer. Menus and rich `AnchoredPopover` content remain anchored popups. Context menus accept `pointerOffset`. Any page-owned menu must register its dismiss callback in `ui.dismissMenu` while open and remove it on dismissal/disposal.

## View fields

- `BrowseView`: `query`, `view` (`grid`/`list`), `tags: Set<String>`, `sort`, `have`, `kind`, `game`, `tagMode` (`any`/`all`), `facetsOpen`.
- `LibraryView`: `query`, `filter` (`all`/`on`/`off`/`upd`/`local`/`prob`), `sort`, `descending`, `selection: Set<String>`, `focusedId`, `anchorId`.
- `SettingsView`: `category`, `query`, `scope` (`all`/`profile`), `profileId`.
- `PaletteView`: `query`, `index`.
- `UiState`: `page`, `panel` (`PanelRef` and `PanelTab`), `overlay`, `overlayKind`, `downloadsOpen`, `mock`, `importMode`, `importFile`, `overlayProfileId`, `dragging`, `focusedField`, `updateStatus`.

These are the shared page contract. Extend them as needed without changing address semantics.

## Shots

`MockShots.kt` lists every non-server picture from `design/mock/build.ps1` as `MockShot(name, address, width, height)`. Add another entry there to render a whole-window demo state. `applyMockAddress` applies all view keys. `mockStore` seeds loading/offline/first/empty, running and a frozen download before composition. Factory overlays remain absent until their factories are installed; the drop layer is shell-owned. Factory registration must also be added to the shot model when dialogs land.

Run `tools/gradlew-locked :composeApp:renderShots`, then `tools/compare-shots`. Outputs are `composeApp/build/shots/<name>.png` and `composeApp/build/shots/compare/<name>.png`. Comparison crops the mock to 1328 x 792 or 728 x 720 at offset 16,14. No image comparison thresholds are used while pages remain placeholders.

## Appearance and native integration

The live app reads `theme`, `motion`, `textSize` and `density` from general settings. Default theme is Dark. System theme and Windows `win.animation` are used only for Same as Windows; other platforms retain motion when no system preference is available. Both native close and title close save placement through one IO coroutine. Demo and kit modes do no update checks. Real mode currently still uses `demoStore()` behind the explicit `TODO(core)` in `createStore(args)`.

About and updates calls `model.checkAppUpdates()`, displays `ui.updateStatus`, and uses `model.openAppReleases()` for manual downloads. Update NNOMM controls startup checking and automatic installation. The inherited Nucleus provider and Windows portable/ZIP manual-update rules remain. Installed updates have not been tested on Linux development builds.

## Decisions and remaining integration

- Servers heading: `Servers`; empty heading: `Coming later`; body: `A list of dedicated servers comes in a later version of NNOMM.`
- Missing dialog factories stay inert until the dialog agent supplies them. The first-start and guided-test page layers also need that agent's factory wiring.
- `TODO(core)`: real mode still uses demo IO. No real game directory was used.
- Store timers expire core toasts. The host displays at most three with optional actions and bounded wrapping.
- Windows checks are in `WINDOWS-CHECKS.md`.
- Independent Opus/Sol review remains the parent's responsibility: this task explicitly required working alone.
