# Migration background sync-advance — design

Date: 2026-07-24
Status: Approved (design), pending implementation plan

## Problem

A scheduled migration commits N transfers, each with a future `nextExecutableAfterHeight`
(a few blocks ahead of the split anchor). Each transfer only becomes broadcastable once the
wallet's **synced chain tip** reaches its height. In the foreground this works: the
synchronizer advances the tip, the transfer becomes due, and `MigrationWorker` broadcasts it.

In the **background** the migration stalls indefinitely. Observed on a real device: the app
went to background (`MainActivity.onStop`, gRPC connection closed), the synced tip froze at
4198071, and every `MigrationWorker` run took the `WAIT_AND_RETRY` path — a signed, proved
transfer (`TxId9 @ 4198073`) never broadcast because the tip never advanced the 2 blocks
needed. Foregrounding the app immediately advanced the tip to 4198085 and unstuck it.

### Why the tip is frozen (confirmed, not assumed)

Two independent reasons a sync may not run, and only one applies here:

- **(A) `isSyncBlocked()` gate** — `OrchardMigrationSdkImpl.isSyncBlockedNow()` returns
  `overdue || bufferActive`. `overdue` is **height-based**: Rust `hasOverdueTransfers` is
  `state.next_broadcastable(tip).is_some()` where `tip = target_height - 1`
  (`backend-lib/src/main/rust/migration.rs:1238`). So while the tip is *below* the next
  transfer's height the gate is **open** (`overdue=false`); it closes only once the tip
  reaches that height and the transfer is ready. `bufferActive` is the post-broadcast
  10-minute privacy window (`MIGRATION_SYNC_RESUME_AT`).
- **(B) App lifecycle** — the synchronizer only runs while something drives it. In the
  foreground the UI does; in the background only `SyncWorker` does, and it runs **once per
  day** (~3am, requires charging + unmetered).

During the stall the worker took `WAIT_AND_RETRY`, i.e. `hasOverdueTransfers() == false`, so
the gate (A) was **open** the whole time. The freeze was purely (B): nothing drove the
synchronizer. **There is no gate deadlock, and no "temporary unblock" is needed** — the gate
already opens exactly when the tip needs to advance and closes exactly when a transfer is
ready to broadcast.

## Goal

Make a background migration advance its own chain tip so pending transfers reach their
executable height, **without** coupling sync traffic to broadcast timing (the privacy
property the whole `isSyncBlocked` design exists to protect).

## Non-goals

- Changing the `isSyncBlocked` gate or any SDK/Rust code. The gate behaves correctly.
- Changing the daily `SyncWorker`.
- Driving sync in the overdue/`HANDOFF_TO_APP` branch (that already hands off to the app;
  unchanged).

## Design

Entirely app-side, inside `MigrationWorker` (`ui-lib/.../work/MigrationWorker.kt`), on the
`null` result's `WAIT_AND_RETRY` branch only. The transfer is pending, not overdue, and
wasn't ready to broadcast this run.

Sequence:

1. **Detect** `WAIT_AND_RETRY` (unchanged `decideNullResultAction`).
2. **Drive a bounded sync burst.** Reuse `SyncWorker`'s pattern: inject `SynchronizerProvider`
   and collect `synchronizer` status/progress until it terminates. The synced tip advances;
   once it reaches the next transfer's height the gate flips (`overdue=true` →
   `isSyncBlocked=true`) and `WalletCoordinator` tears the synchronizer down.
3. **Terminate the collect on any of:** `Status.SYNCED`, `Status.DISCONNECTED`, **or the
   `synchronizer` StateFlow emitting `null`** (gate closed = tip reached the transfer height;
   this is the key difference from `SyncWorker`, which treats `null` as `emptyFlow()` and
   would hang).
4. **Do NOT broadcast in this run.** Deliberately skip re-calling
   `executeNextPendingTransfer()` after the burst — an immediate broadcast would sit adjacent
   to the sync traffic and defeat decorrelation.
5. **Reschedule conditionally**, then return:
   - if `hasOverdueTransfers()` is now **true** (a transfer became broadcastable) →
     `MigrationScheduler.schedule(PRIVACY_SYNC_BUFFER)` (10 min). The next run broadcasts,
     10 minutes after the sync burst — sync and broadcast are decoupled on both sides.
   - else (tip still short, or proof still not witnessed) → the existing short retry
     (`ZcashSdk.BLOCK_INTERVAL_MILLIS`), unchanged.

After the eventual broadcast, the existing `wasOverdue` post-broadcast buffer keeps sync
quiet for another 10 minutes, then the next pending transfer repeats from step 1.

### Why this preserves the privacy property

An observer correlating lightwalletd sync traffic against a transaction broadcast sees them
separated by ≥10 minutes on both sides of every broadcast: the sync burst (step 2) is
followed by a ≥10-minute quiet gap (step 5) before the broadcast, and the post-broadcast
buffer enforces another ≥10-minute quiet gap after. The gate is never overridden.

## Components touched

- `MigrationWorker` — new dependency on `SynchronizerProvider` (already a `KoinComponent`);
  new sync-burst helper; conditional reschedule on `WAIT_AND_RETRY`.
- A small, unit-testable helper for the collect-until-terminal logic (mirrors how
  `executeWithRetries`/`decideNullResultAction` are already factored out top-level for
  testability without Koin/WorkManager).

No changes to `SyncWorker`, `MigrationScheduler`, `OrchardMigrationSdk`, the gate, or Rust.

## Edge cases

- **Far-behind wallet:** the burst could approach the `CoroutineWorker` ~10-minute ceiling.
  Acceptable — WorkManager reschedules; during an active migration the wallet is normally
  only a few blocks behind.
- **User foregrounds mid-burst:** the foreground UI drives the same `WalletCoordinator`
  synchronizer; no conflict.
- **Proof-not-ready (not tip-behind):** step 2 still helps (witnesses come from scanning);
  step 5's `hasOverdueTransfers()` re-check stays false, so it falls back to the short retry
  rather than an unwarranted 10-minute wait.

## Testing

- Unit-test the terminal-condition helper: terminates on `SYNCED`, on `DISCONNECTED`, and on
  `synchronizer == null`; does not terminate on `SYNCING`.
- Unit-test the reschedule decision: overdue-after-burst → 10 min; not-overdue → block
  interval.
- Manual emulator verification (per project practice): background the app mid-migration with
  a pending transfer whose height is above the synced tip; confirm the worker advances the
  tip, waits ~10 min, then broadcasts — and that no broadcast happens in the same run as the
  sync burst.
