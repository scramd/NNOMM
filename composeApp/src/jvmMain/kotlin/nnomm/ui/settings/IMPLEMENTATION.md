# Settings implementation

Settings use core definitions and store actions. Game-folder typing is a local draft committed on Enter/blur, with Escape restoring the saved value. Slow actions stay in the store or coroutine-backed platform pickers. Demo actions without a real effect use store toasts.

The review ledger and verification limits are in `REVIEW-B-FIXES.md`.
