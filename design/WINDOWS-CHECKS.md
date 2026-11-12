# Checks that need Windows

These parts cannot be checked on the Linux development machine. Check them on Windows before a release. Each entry says what changed from NOMM 5.5.0 (`3dacae5`) and what to check.

| Part | Change from NOMM | What to check |
| --- | --- | --- |
| Core game discovery | Ported HKCU SteamPath query and libraryfolders.vdf path scan to :core; non-Windows uses configured path only | Discover default and secondary Steam libraries; check registry access failures and non-ASCII paths |
| Core game launch | Keeps cmd.exe /d /c start "" steam://rungameid/2168680 for empty options, no Steam worker; nonempty options use steam://run/2168680// URL-encoded arguments | Steam running/closed, spaces/quotes in start options; confirm game receives options and running detection follows actual game |
| Vanilla launch | Backs up doorstop_config.ini, writes enabled=false, restores exact original text on exit or next launch attempt | Verify BepInEx is bypassed; normal exit, launch failure, crash recovery; external Steam launches after an interrupted vanilla session |
| Mod placement and loader | New managed state record, hard-link/copy fallback, BepInEx 5.4.23.4 install/repair without config overwrite | Same/different volume, permissions, antivirus locks, existing unmanaged plugins, profile config swap, repair preserves plugins/config |
| Private persistence | APPDATA/NNOMM or NNOMM_HOME, atomic JSON with backups | Installed beside NOMM, no NOMM settings access, locked files and interrupted writes |
