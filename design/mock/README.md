# NNOMM mock

A click-through draft of the NNOMM user interface. All data is invented. It touches nothing: no game, no registry, no Steam.

- Open `nnomm-mock.html` in a browser. Add `#fill=1` to the address for the full window size.
- `shots/` has pictures of every screen and state.
- `src/` is the source. `build.ps1` joins it into `nnomm-mock.html`; `build.ps1 -Shots` also takes the pictures and `test.ps1` runs `src/selftest.js`, both with headless Chrome. The scripts were written for Windows PowerShell and a Chrome in `C:\Program Files`.
- `../DESIGN.md` describes the design and lists the address keys that select a screen.

The typeface is Archivo, under the SIL Open Font License (`Archivo-OFL.txt`).
