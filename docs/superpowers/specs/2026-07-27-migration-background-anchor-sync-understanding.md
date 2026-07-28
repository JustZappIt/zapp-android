# Migration background execution — our working understanding (baseline for core-team diff)

Date: 2026-07-27
Status: Baseline. This captures OUR mental model of the scheduled-migration execution flow, the
background-anchor stumbling block, the fix we shipped, and the question we have repeatedly asked
the core team without an answer yet. A companion spec
(`2026-07-27-migration-anchor-sync-diff-vs-core-guidance.md`) diffs this model against Kris's
notes (Slack since 2026-07-23) and the Swift SDK PR
[zcash-swift-wallet-sdk#1853](https://github.com/zcash/zcash-swift-wallet-sdk/pull/1853).

## 1. The original plan (as we understood the design)

1. The app asks the SDK (which calls into librustzcash / `zcash_pool_migration`) to build a
   migration plan; the SDK returns it.
2. We show the plan to the user; the user confirms.
3. On confirmation we create the note split plus N transfer transactions scheduled into the
   future.
4. Everything is **pre-signed up front** — open point in our model: we were not sure whether
   the pre-sign happens *without* an anchor or with a *current/fake (placeholder)* one.
5. A background worker is scheduled for execution.
6. When a transfer's time arrives, the worker runs:
   - takes the transaction whose execution time has come,
   - **swaps in the real anchor and produces the proof**,
   - broadcasts it,
   - schedules the next one in order.

## 2. The stumbling block

Swapping the placeholder anchor for the real one was **impossible in the background**: the
app's background sync runs only **once per day, ~3am** (charging + unmetered constraints). So
unless the user happened to open the app and a sync happened at the right moment, the worker's
"swap anchor → prove → send" step could **never succeed** — the wallet's synced state never
contained the anchor/witness the proof needed.

## 3. The fix we shipped

If the worker finds the transfer isn't executable (no usable anchor / tip not reached), then
**instead of the transaction send it drives a sync** (bounded sync burst), and schedules another
worker run **10 minutes later** which performs the original logic — by then it can, because the
synced state now covers what proving/broadcast needs.

(Code, verified 2026-07-27: `MigrationWorker.kt` `WAIT_AND_RETRY` branch → `Synchronizer.syncBurst`
→ reschedule via `rescheduleDelayAfterSyncBurst(privacyBuffer = sdk.privacySyncBufferDuration())`;
design doc `2026-07-24-migration-background-sync-advance-design.md`.)

## 4. The open question to the core team (unanswered)

We have asked more than once and still have no answer:

> **Is our "sync burst, then transfer 10 minutes later" logic even correct?**

The concern is **sync/transfer correlation**: an observer who can correlate a wallet's
lightwalletd sync traffic with a subsequent broadcast can derive things about the migration
(which wallet is migrating, linkage across transfers, timing structure). The whole
`isSyncBlocked()` / broadcast-window design exists to prevent exactly that — and our fix
deliberately injects app-driven sync activity into the schedule, separated from the broadcast
only by the 10-minute privacy buffer. Whether a 10-minute gap (on both sides of a broadcast) is
sufficient decorrelation — or whether the core team intends a different mechanism entirely
(e.g. proving opportunistically during ordinary sync, long before the broadcast window, as
zcash-swift-wallet-sdk#1853 moves toward) — is the question we are waiting on.

## 5. Known uncertainties in this model (ours, marked explicitly)

- §1 step 4: pre-sign without anchor vs. with a placeholder anchor. (Code says: ZIP 374
  sign-now/prove-later — PCZTs are built with *bare notes, no anchor/witness*, and the real
  anchor+witness are installed at prove time via the PCZT Updater role; see
  `backend-lib/src/main/rust/migration.rs` `try_prove`.)
- Whether the 10-minute buffer value itself came from any core-team guidance or is our own
  choice.
- Whether "the transaction whose time has come" needing proving *at that moment* is the
  intended design at all — upstream (`zcash_pool_migration_backend::state`) separates
  `next_provable` (anchor boundary settled → prove now, ahead of schedule) from
  `next_broadcastable` (scheduled height reached → broadcast a stored proof), which suggests
  proving was always meant to happen earlier than the broadcast moment.
