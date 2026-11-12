# Real IO review disposition

Reviews: io-review-opus.md (A) and io-review-sol.md (B), against f89d55a.
This round works alone as requested. No independent review was started.

## Review A blockers

| Finding | Disposition |
| --- | --- |
| B1 loose files block takeover | Fixed: verbatim raw copies, per-entry notices; unknown files and ZIPs remain inert. |
| B2 takeover moves names/settings apply | Fixed: private stable adoption placement names; settings-only reconciliation was already fixed. |
| B3 player files disappear | Fixed: new files saved and restored per mod/profile, with recovery and path notices. Distributed-file edits remain recoverable but Repair replaces them. |
| B4 hard links corrupt store | Fixed: all copies are independent. |
| B5 Repair skips game copy | Fixed: transactional generation stamps and forced AppStore apply for Repair. |
| B6 transfer deadline/no retry | Fixed: transfers override request timeout, retain socket timeout, retry three times. |
| B7 stale ownership removes mods | Fixed: fresh ownership alone gives no removal authority; empty runtime library takes over existing entries. |

## Review A should-fix: game and store

| Finding | Disposition |
| --- | --- |
| Recovery growth/storage/Clear | Fixed with a conservative limit: usage counts recovery/player files, identical snapshot replacement is skipped, shared config does not swap, Clear removes only old duplicates with another copy outside recovery. Full-tree snapshots still consume space. |
| Config swap crash window | Fixed: snapshot first, stage config and plugins in the same game-tree transaction, replay before inspection. |
| Apply staging in plugins/shutdown queue | Fixed: target-sibling staging outside plugins; shutdown drains without a ten-second cutoff. |
| One unmanaged collision blocks all | Fixed: per-mod placement notices, other mods proceed. |
| Duplicate takeover ids | Fixed: enabled association wins, disabled original retained with notice. |
| Changing game folder/associations/Clear | Already fixed in part: settings do not apply folder and adoption merges library; this round refuses replacement of differing adopted store bytes. |
| Takeover before registry | Fixed: NOMM metadata retains registry identity even with no loaded catalog. Unidentified files remain local. |
| Takeover extracts inert archives | Fixed: raw takeover never unpacks loose archives. |
| Symlink ancestors rejected | Fixed: canonical game root; safe path checks stop at managed roots. |
| Store promotion loses old version | Fixed: durable batch journal with startup rollback. |
| put deletes pack siblings | Fixed: store never consumes input parents; downloader cleanup is explicit and separate from pack preview lifetime. |
| Invalid ZIP becomes empty install | Fixed: sniff magic, ZipFile validates structure, require meaningful payload before promotion. |
| Clear/install race | Fixed: store mutex, disk queue ordering, fresh state at Clear execution, recent install and Undo protection. |

## Review A should-fix: launch and loader

| Finding | Disposition |
| --- | --- |
| Vanilla crash/early restore | Fixed: restore at runtime launcher construction if absent, after observed exit, never on cancellation or a timeout before detection. |
| Unpinned loader | Fixed: official 5.4.23.4 release asset SHA-256 pinned (see IO.md). |
| Loader Repair overwrites INI/mixes versions | Fixed: preserve existing INI, refuse a known different version, replace files and ownership as one journal batch. Unknown loader versions cannot be identified reliably. |
| Missing saved folder prevents start | Fixed: folderMissing state and plain Settings help. |
| Proton process detection/setup | Fixed detection and guidance: inspect process arguments as well as executable, poll less often, emit the Steam WINEDLLOVERRIDES setup notice. Actual Wine/Proton behavior remains unverified; no environment override is silently installed. |

## Review A should-fix: network

| Finding | Disposition |
| --- | --- |
| Bad artifact/source loses registry, source priority | Fixed: decode entries/artifacts separately, isolate source failures, official source wins duplicates. |
| Metadata blocks downloads/toasts/repeats | Fixed: separate AppStore semaphore, cached unknown metadata on failure, no metadata toasts. |
| Transfer progress prevents saving | Already partly fixed by AppStore throttling; downloader now emits changed percentages only. |
| RAR/TAR.GZ | Fixed: gzip TAR via existing Commons Compress, RAR unavailable before download with a reason in modAction and queue. |

## Review A should-fix: packs/data

| Finding | Disposition |
| --- | --- |
| Pack exports/imports private settings | Fixed: same mod-setting allow-list on write and read. |
| Missing requested version gets newest bytes | Already fixed: exact requested version required. |
| Pre-release-only crashes | Already fixed: installable version fallback. |
| Add-on placement differs | Fixed: parent/addons/id, checked against NOMM LocalMods.kt enable() at 3dacae5. |

## Review A nits

| Finding | Disposition |
| --- | --- |
| Windows reserved/trailing names | Fixed: portable components and case alias validation; conservative 240-character preflight. |
| "unknown found here" | Already fixed; missing folder now has its own message too. |
| Real defaults from demo | Already fixed in RealRuntime (explicit blank game path/private store/default registry). |
| store setting ignored | Already fixed runtime wiring; blank configured paths now use the private store. |
| Absent side means client | Fixed: absent/unknown means BOTH. |
| Takeover creates marker/plugins | Fixed: writes only private adoption metadata, no game marker or directory creation. |
| One-second process scans | Fixed: three-second polling; Windows CPU still requires measurement. |
| Steam arguments may prompt | Unverified Windows behavior, recorded in WINDOWS-CHECKS; existing Steam URI approach retained. |
| Unbounded text/insecure follow addresses | Fixed: 16 MiB text limit and HTTPS for configured external addresses (loopback fixtures excepted). |
| ZIP STORED data descriptors | Fixed: ZipFile replaces ZipInputStream. |
| atomic lacks directory fsync | Fixed: attempt directory force after rename; unsupported Windows directory channels documented. |
| Integration guard substring | Fixed: normalized sandbox path boundary instead of substring. |
| Stale docs/report path | IO.md rewritten; this report supersedes historical REAL-IO-REPORT.md, whose old checkout remains historical evidence. |

## Review B

| Finding | Disposition |
| --- | --- |
| 1 hard links | Fixed: independent copies. |
| 2 Repair | Fixed: generation stamp and forced apply. |
| 3 profile ids | Already fixed: UUID ids, covered after restart. |
| 4 config crash | Fixed: staged game-tree transaction, snapshot and rollback tests. |
| 5 takeover/state gap | Fixed non-destructively: private repeatable adoption, raw copies, empty-library startup takeover, stale ownership guard. Takeover does not mutate the game. |
| 6 store replacement crash | Fixed: journal includes payload and generation stamp. |
| 7 Clear race | Fixed: serialized store mutation, queued Clear, recent install/Undo protection. |
| 8 loader partial repair | Fixed: staged batch including ownership, rollback tests. |
| 9 pack sibling deletion | Fixed: explicit download ownership/cleanup; preview owns bundled locals. |
| 10 settings/placement | Already fixed settings isolation; stable takeover names added. |
| 11 second folder | Already fixed association merge; differing payloads kept with notice rather than overwritten. |
| 12 Windows aliases/length | Fixed portable preflight; native Windows locking/rename still unverified. |
| 13 own-config flag | Already fixed comparison of profile id and flag; staged transition retained. |
| 14 vanilla recovery | Fixed startup/observed-exit logic and pending record on close. |
| 15 claimed local provenance | Fixed: installed/catalog provenance governs import; updatePack also skips locals. |
| 16 invalid ZIP | Fixed sniffing, validation and nonempty payload; old version retained. |
| 17 filename/format | fileName was already modeled; now used by downloads; content sniffing, TAR.GZ and preflight RAR reason added. |
| 18 notes mapping | Prefix already modeled; percent-decoding and declared-prefix fallback added. Without an explicit tag/prefix, ambiguous monorepo notes are omitted. |
| 19 tests | Ported probe assertions; added per-boundary store/apply/config/loader fault tests, recovery Clear, local HTTP retries/deadline, player files, and real AppStore plus runtime restart. Physical power loss/Windows remain unverified. |

## Player-visible choices and API

RAR shows Unavailable. Missing folders show Game folder not found. Collisions,
unknown takeover entries and duplicates leave originals and emit notices. Additional
files return only with their originating profile. Clear intentionally retains last
copies, so it can free less space than the total recovery usage. Known different
BepInEx versions are not mixed. Large apply operations need full-tree staging space.

API additions and downloader cleanup responsibilities are in core/README.md.
No Compose files, releases, tags, pushes or remote publication are part of this round.

## Validation and checkout

- Branch: `t3/fix-real-io-safety`
- `tools/gradlew-locked :core:spotlessApply :core:test :core:spotlessCheck`: passed.
- JUnit results: 117 tests, zero failures, errors or skips. The two ported probe
  suites contain 24 tests; the real AppStore/runtime integration adds one.
- `git diff --check`: passed. No Compose files changed. No pushes or tags.
- Unverified: Windows locked-file/directory rename behavior, Steam launch and
  argument prompts, real game loader/Blueprinter behavior, Proton process detection,
  physical power loss and actual Windows process-scan CPU use. Recovery retains
  last copies and whole-tree apply needs temporary disk space.
