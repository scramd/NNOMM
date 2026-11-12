# Final core findings

Implementation is confined to core and the Windows checklist. No UI changes are
required. No real game folder was used. No delegates, push, tags, or GitHub
activity were started.

- F1: Clear uses durable per-version provenance and actual payload hashes.
  Personalized recognized takeover, local, pack, unknown and unverified payloads
  stay after uninstall. Verified downloads and byte-identical takeover payloads
  with recorded verified registry payload hashes are eligible.
- F2: An unknown startup plan does not authorize game writes on external exit.
- F3: Explicit takeover preserves completed first start.
- F4: A refused root link becomes an entry problem; independent entries continue.
- N14: Startup under the home lock removes abandoned player/edit/config
  preparation copies. Loose takeover files remain local after registry loading;
  automatic later hash recognition remains a documented limit.
- Acceptance 1: Persisted deferred-mod-change authorization gates exit apply.
  Exit recovers authorized journals but never installs the loader. Play with mods
  on can install it even if exit already reconciled the mod plan.
- Acceptance 2: Saved official fallback reports offline with its saved timestamp
  and does not refresh the cache timestamp or run automatic updates.

## Regression coverage

All seven supplied Round3ReviewTest probes are ported, with portable temporary
roots and cleanup. FinalCoreFindingsTest adds fresh startup/external play/exit in
visible and hidden profile modes, deferred changes during play, refused-write
authorization across a real persistence restart, exit without loader install,
Play after deferred exit, blocked-network official fallback, durable provenance
and actual-hash retention, and preparation cleanup.

Existing expectations were aligned with the requested rules: takeover preserves
completion; URL identity without verification cannot authorize Clear; loader
preparation is tested with a turn-on action.

## API and integration

Documented in README.md: AppState.deferredModChange; Registry.readSnapshot and
RegistrySnapshot.savedAtMillis; DownloadedFile.origin/verifiedHash and
PayloadOrigin. Old Registry.read implementations continue to work through the
default snapshot adapter. Trailing DownloadedFile cleanup lambdas still work;
positional cleanup arguments must use the named cleanup argument.

Runtime home cleanup happens before journal recovery while its exclusive lock is
held. Provenance is conservative after interruption: an incomplete put leaves
unknown provenance and Clear retains it. Legacy versions without provenance stay.

Native Windows lock/junction behavior and real game launch remain platform checks;
the checklist records the affected flows. Independent reviews were not started
because this task explicitly requires the implementation agent to work alone.

## Validation and commits

Final validation: `tools/gradlew-locked :core:spotlessApply :core:test` passed:
222 tests, zero failures/errors/skips. Round3ReviewTest: 7; FinalCoreFindingsTest:
7; remaining existing tests: 208. Earlier runs exposed three obsolete test
expectations described above; the final complete run passes. No Compose build or
Windows run is claimed.

Local commits, authored as clankagent:
- eb742a0: payload provenance, registry freshness snapshot and isolated link refusal.
- cfdc5f3: deferred mod-action authorization, loader rules, completion and preparation cleanup.
- This documentation/regression commit: both suites, API/IO notes and Windows checklist.

Implementation remains on the supplied `t3/fix-core-final-findings` branch.
The worktree location is reported to the caller, not embedded in repository files.
