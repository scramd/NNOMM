# Rules for page work

Shared rules for the agents that build one page or one group of dialogs of NNOMM. Each agent also gets a brief with its own scope.

## Read first

`AGENTS.md`, `design/DESIGN.md`, `design/ARCHITECTURE.md`, `design/SHELL-INTEGRATION.md` (how pages plug into the shell: `LocalApp`, view classes, key registration, overlay factories, focused fields, shots), `composeApp/COMPONENT-KIT.md` (theme, kit, table, cards, highlighted text, focus rings), `core/README.md` (store actions, rules, demo data).

## The mock is the specification

The mock in `design/mock/` is the agreed design. Reproduce its look, behaviour, wording and feel; do not redesign. Every visible text comes from the mock (`views.js`, `app.js`) or from the core, which already uses the mock's wording. `styles.css` has every size, colour, spacing and state. `design/mock/build.ps1` lists which address state each picture in `design/mock/shots/` shows. Render any mock state in Playwright Chromium (`~/.cache/ms-playwright`) with `design/mock/nnomm-mock.html#fill=1&<keys>` (keys in `design/DESIGN.md`) to measure, to see hover and focus, or to try an interaction. `design/mock/src/selftest.js` states the expected result of actions.

## Feel is part of done

- A click answers at once; store actions return at once; nothing slow on the UI thread.
- Lists are lazy (kit table, card grid); scrolling several hundred rows stays smooth. Do not collect the whole `AppState` in a way that recomposes a long list on every toast or download tick: derive what the page needs (`remember`/`derivedStateOf` on the parts it reads).
- Keyboard works as in the mock (`app.js` keydown) through the shell's key registration; focus rings show only for keyboard use.
- Motion is short, answers an action, and is off with reduce motion.

## Checking

1. Renders: `tools/gradlew-locked :composeApp:renderShots`, then `tools/compare-shots`; look at every `composeApp/build/shots/compare/<name>.png` of your page (mock left, app right) in both themes and the narrow size, and fix differences in size, spacing, colour, weight and text until they match. Crop and zoom with ImageMagick where needed.
2. The running app on the desktop (X display `:1`; `SKIKO_RENDER_API=SOFTWARE` if needed): `tools/gradlew-locked :composeApp:run --args=--demo` with `JAVA_HOME` set to the JetBrains JDK 25 in `~/.gradle/jdks/`. Other agents use the same desktop: check with `wmctrl -l` that no other NNOMM window is open first (if one is, do other work and try again later), keep the session short, and close your app at the end. Try every interaction of your page in the mock and in the app: hover, focus, keyboard, menus, Undo, scrolling, narrow window (`xdotool`/`wmctrl` to resize). Take screenshots with `import -window root`.

## Limits

- Builds: memory is tight. Gradle only through `tools/gradlew-locked` (two machine-wide build slots, no daemon). Never `./gradlew` directly.
- Edit your own package and files. Small additions elsewhere are allowed where the brief says so (for example one line in `AppModel.perform`, a field in your view class, a factory registration). Do not edit `core/`: if the core lacks something, add a UI-side workaround marked `TODO(core)` and list it in the report. Another agent is changing the core's public API now (strings to enums, derived state); use the current API.
- Never use a real game folder; the app runs in demo mode.
- Commit in small commits with clear messages (author `clankagent` / `327495124+clankagent@users.noreply.github.com` if not set). Do not push, no tags, no GitHub activity. Nothing private in the repository.
- Readable code (official Kotlin style, one statement per line).

## Report

Commits (SHAs), worktree path and branch, what works and how you checked it (renders and desktop), paths of the compare pictures, what still differs from the mock and why, `TODO(core)` items, decisions that change what the player sees, open points.
