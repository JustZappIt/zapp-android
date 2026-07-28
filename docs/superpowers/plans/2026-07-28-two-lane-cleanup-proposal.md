# Two-lane migration — cleanup & alignment proposal (2026-07-28)

Self-contained implementation proposal. Worktrees:
- APP = `/Users/micutad/Projects/AndroidStudioProjects/z/wt/migration/zodl-android` (branch `android-slipstream-ironwood-chp`)
- SDK = `/Users/micutad/Projects/AndroidStudioProjects/z/wt/migration/zcash-android-wallet-sdk` (same branch)

Build/verify commands (run from APP root):
```
./gradlew :ui-lib:testZcashtestnetInternalDebugUnitTest --tests "co.electriccoin.zcash.work.*" -Pcoverage=false --max-workers=1 -PSDK_INCLUDED_BUILD_PATH=../zcash-android-wallet-sdk
ANDROID_SERIAL=emulator-5556 ./gradlew :app:installZcashtestnetInternalDebug -Pcoverage=false --max-workers=1 -PSDK_INCLUDED_BUILD_PATH=../zcash-android-wallet-sdk
(cd ../zcash-android-wallet-sdk/backend-lib && cargo check && cargo test migration)
```

## Target design (binding, decided by the user today)

1. **Test-phase scheduling is plan-fixed (iOS-style), NOT cadence-driven.**
   Lane A (`MigrationSyncWorker`) wakes exactly at anchor-boundary heights (+ small settle
   margin) of not-yet-proved, not-yet-sent transactions. Lane B (`MigrationWorker`) wakes at
   scheduled (transfer) heights. Cadence survives ONLY as a fallback when live states are
   unavailable, with a code note: the FINAL Android impl per Kris returns to a cadence-driven
   Lane A and will consume the engine's `sync_wakeup_schedule` (librustzcash #2801) after the
   rc.3+ engine merge (the `[patch.crates-io]` path-dep is currently inactive; we compile
   against published `zcash_pool_migration 0.1.0-rc.1`, which predates #2801).
2. **Single source of truth = the Rust engine.** The JNI layer only SURFACES engine state
   (`anchor_boundary`, proof state); it never re-implements or mutates plan logic.
3. **Shift/reschedule is dead.** Our hand-rolled boundary redraw is deleted end-to-end.
   Missed-but-unexpired transfers need NO shift (ZIP 374: the signature does not cover the
   anchor; a `Signed` tx proves against its committed boundary and broadcasts late). EXPIRED
   transfers are the engine's `rebuild_expired_transfer(_unsigned)` (present in rc.1) — wiring
   that is a separate follow-up, not this cleanup.
4. **Precise Lane A decisions from real proof state** (no more overdue heuristics).
5. Keep what is already correct (grid-cut checkpoints, StalePlan retry, busy_timeout 15 s,
   `mmap_size=0`, locked-DB retry classifier, Lane B preflight, notifier/debug-restart cleanup,
   estimated-tip-only-for-scheduled-dueness).

Verified engine facts used below (rc.1 `engine.rs`): `MigrationTxState` variants are
`AwaitingSignature | Signed | Proved | Broadcast{txid} | Mined{height}`; `MigrationTransaction`
getters include `anchor_boundary()` (`Option<BlockHeight>`; `None` for preparations) and
`scheduled_height()`.

---

## Part 1 — SDK Rust (`SDK/backend-lib/src/main/rust/migration.rs`)

### R1. `migrationTransferStatesNative`: surface boundary + proof state + preparations
Current emission: `(id, is_sent, scheduled_height)` for `kind == Transfer` only, JNI ctor
`(Ljava/lang/String;ZJ)V`.

Change to emit **ALL transactions** (transfers AND preparations) as
`(id, is_transfer, is_sent, is_proved, scheduled_height, anchor_boundary)`:
- `is_transfer = matches!(t.kind(), MigrationTxKind::Transfer { .. })`
- `is_sent = matches!(t.state(), Broadcast{..} | Mined{..})` (unchanged)
- `is_proved = matches!(t.state(), Proved | Broadcast{..} | Mined{..})`
- `anchor_boundary`: `i64::from(u32::from(b))` or `-1` when `None`
- JNI ctor signature becomes `(Ljava/lang/String;ZZZJJ)V` (order: id, isTransfer, isSent,
  isProved, scheduledHeight, anchorBoundaryHeight).

Why (design #1, #2, plus a latent-bug fix): Lane A needs boundaries + proof state to wake at
the right heights; and today `scheduleForNextLiveWindow` (Lane B re-arm) reads these states
while `nextDueTransferNative` serves preparations too (kind-agnostic, hard invariant 4) — so a
plan WITH preparation layers can have Lane B sleeping past a due prep because preps are
invisible in states. Including preps closes that inconsistency. UI correlation is unaffected
(it matches by stable id; prep ids simply match nothing).

### R2. DELETE the shift machinery
- `Java_..._rescheduleUnprovenTransferNative` (around line 2483+) — whole function.
- `select_shift_boundary` helper + its unit tests (`select_shift_boundary_*` in the test mod).
- `Java_..._debugRescheduleTransfersNative` (around line 2627+) — hand-mutates
  `scheduled_height`/boundary via `MigrationTransaction::from_parts` = the same second source
  of truth. No app UI references it (only stale doc comments in
  `MigrationPlan.kt`/`MigrationProgressVM.kt` mention it).
- Any now-unused imports (`MigrationTransaction::from_parts` usages that only served these).

### R3. Accepted residuals — keep, but ensure each carries a comment
These construct state via `MigrationState::from_parts` with ONLY the status swapped
(sub-state passed through verbatim); the engine has no cancel/fail primitive in rc.1:
- `clearMigrationNative` (cancel → `Failed`),
- `recordTransferResultNative` tags 2/3 (InvalidNote/Expired → `Failed` + invalidation reason),
- `reconcileInvalidatedTransfersNative` (foreign spend → `Failed`).
Not part of the shift problem; leave them, verify each has a "status-only swap" comment.

---

## Part 2 — SDK Kotlin

### K1. Model plumbing (follows R1)
- `SDK/backend-lib/.../internal/model/migration/JniMigrationModels.kt`:
  `JniMigrationTransferState(id: String, isTransfer: Boolean, isSent: Boolean,
  isProved: Boolean, scheduledHeight: Long, anchorBoundaryHeight: Long)`.
- `SDK/sdk-lib/.../MigrationSdk.kt` `MigrationTransferState` becomes:
  ```kotlin
  data class MigrationTransferState(
      val id: String,
      val isTransfer: Boolean,
      val isSent: Boolean,
      val isProved: Boolean,
      val scheduledHeight: Long,
      /** Committed anchor bucket boundary; null for preparations (natural anchor). */
      val anchorBoundaryHeight: Long?,
  )
  ```
- `OrchardMigrationSdkImpl.kt` `toPublic()` (line ~938): map `-1 → null` for the boundary.
- Update kdoc on `MigrationTransferStates`: states now include preparations; display-facing
  consumers filter `isTransfer` or correlate by id.

### K2. DELETE dead API surface
Remove `rescheduleUnprovenTransfer` and `debugRescheduleTransfers` from ALL of:
`MigrationSdk.kt` (interface + kdocs), `OrchardMigrationSdkImpl.kt`,
`TypesafeMigrationBackend.kt`, `TypesafeMigrationBackendImpl.kt`,
`backend-lib/.../jni/MigrationRustBackend.kt` (suspend wrappers + `external fun` decls).

### K3. Test impact (SDK)
`OrchardMigrationSdkImplTest`: update any `MigrationTransferState(...)` fixtures to the new
shape; drop reschedule-related stubs.

---

## Part 3 — App: Lane A rewrite (`APP/ui-lib/.../work/MigrationSyncWorker.kt`)

### A1. New pure scheduling/decision functions (replace the current pile)
```kotlin
internal const val SETTLE_MARGIN_BLOCKS = 2L   // boundary must be strictly below scanned tip;
// 2 blocks ≈ the engine WakeupParams margin scaled to the 12-block testnet grid (10@144).
// FINAL impl per Kris: cadence-driven Lane A + engine sync_wakeup_schedule (#2801) once on rc.3+.

/** Height at which [t] becomes provable: committed boundary for transfers, natural anchor
 *  (its own scheduled height) for preparations. */
internal fun provableAtHeight(t: MigrationTransferState): Long =
    (t.anchorBoundaryHeight ?: t.scheduledHeight) + SETTLE_MARGIN_BLOCKS

/** True when some pending, unproven tx's provable-at height has been reached (est tip). */
internal fun hasSettledProvableWork(states: MigrationTransferStates, est: Long): Boolean =
    est >= 0 && states.transfers.any { !it.isSent && !it.isProved && provableAtHeight(it) <= est }

/** Epoch seconds of the earliest PROVED, unsent transfer's window — the only case where the
 *  privacy step-aside is meaningful (the broadcast can actually happen). Null if none. */
internal fun nextProvedDueEpochSeconds(states, est, now, secondsPerBlock): Long?   // like
// nextEstimatedDueEpochSeconds but filtered to isProved && !isSent

/** Next Lane A wake: min over (!isSent && !isProved) of provableAtHeight − est, converted at
 *  the measured rate; floor 60 s (WorkManager slack); NO cadence cap. Null → caller falls back
 *  to laneACadence() (states/estimator unavailable — the only cadence left). */
internal fun nextBoundaryWakeDelay(states, est, nowEpochSeconds, secondsPerBlock): Duration?
```

### A2. Decision table (replaces `decideLaneARun` + RUN_OVERDUE_UNSENT)
```
1. isGateBlocked                                   → SKIP_GATE_BLOCKED  (re-arm: privacyBuffer)
2. nextProvedDue != null && now ≥ nextProvedDue−buffer → SKIP_NEAR_DUE  (re-arm: due+buffer−now)
3. hasSettledProvableWork                          → RUN                (re-arm: A1 wake calc, post-run states)
4. else                                            → NOTHING_TO_PROVE   (no sync! re-arm: A1 wake calc;
                                                      if no unproven work remains at all, re-arm to the LAST
                                                      unsent scheduled height + buffer — a completion sweep whose
                                                      run-start terminal check stops Lane A after the final mine)
```
Rationale: rule 2 before 3 keeps the ZIP 318 sync-vs-broadcast separation ONLY when a
broadcast is actually imminent (proved); an unproven due transfer falls through to RUN — this
is the precise replacement of the RUN_OVERDUE_UNSENT heuristic AND of the original livelock
fix, now grounded in real engine state instead of a 60 s guess.

### A3. DELETE from `MigrationSyncWorker.kt` (+ tests)
- `LaneARunDecision.RUN_OVERDUE_UNSENT`, `LANE_A_OVERDUE_OVERRIDE_SECONDS`,
  `overdueUnsentSeconds()`, `laneAPlanDrivenReArmDelay()`, `laneAFirstRunLead()`.
- `laneAReArmDelay()` shrinks to the fallback-cadence + skip-wait cases actually reachable.
- `MigrationSyncWorkerTest`: drop the overdue-override and plan-driven-re-arm tests; add
  tests for `provableAtHeight` (transfer vs prep), `hasSettledProvableWork`, the 4-row
  decision table, `nextBoundaryWakeDelay` (floor, no-cap, null fallback),
  `nextProvedDueEpochSeconds` (proved filter).
- `nextEstimatedDueEpochSeconds` STAYS (kind-agnostic min over `!isSent`) — it is Lane B's
  window basis and now naturally covers preparations after R1.

## Part 4 — App: Lane B (`APP/ui-lib/.../work/MigrationWorker.kt`)
- `AwaitingProof` branch stays a no-engine-mutation branch (60 s floor re-arm). It is now a
  RACE signal only (Lane A wake pending/late), not a plan state.
- Rewrite the shift-counter comments: the counter counts consecutive **awaiting-proof strikes
  with a completed sync in between** (storage/keys unchanged in
  `MigrationShiftCounterStorageProvider.kt` — rename kdocs, not prefs). Escalation at 3 and
  `shouldEscalateShift` stay as the "sync ran but proof still impossible" alarm.
- `scheduleForNextLiveWindow`: unchanged code; now also sees preparations via R1 (fixes the
  latent sleep-past-a-due-prep gap).

## Part 5 — App: recovery / finalize / UI
- `CheckMigrationRecoveryUseCase.kt`:
  - Lane A absent → `migrationSyncScheduler.schedule(accountKeyId, 60.seconds)` instead of
    `laneACadence()` (the worker recomputes the precise boundary wake on its first run);
    drop the `laneACadence` import.
  - DELETE `overdueTransfersToShift()` + its 4 tests in `CheckMigrationRecoveryUseCaseTest`
    (production caller already removed).
- `FinalizeMigrationScheduleUseCase.kt`: Lane A first arm → `60.seconds` flat (the schedule
  object carries no boundaries; the first worker run computes the real wake). Delete the
  `laneAFirstRunLead` usage/import and its comment block; keep Lane B `delayUntilFirstTransfer`.
- `MigrationProgressVM.kt` `onReschedule()`: DELETE the reschedule path (its engine call is
  gone). Recommended UX: remove the "Reschedule" action from the overdue state entirely —
  "Send now" remains the single truthful action (a missed, unexpired transfer needs no plan
  change by design; an EXPIRED one will get the engine `rebuild_expired_transfer` flow as a
  separate follow-up ticket). Touch: state field wiring for the reschedule button in
  `MigrationProgressState` + screen composable + `MigrationReviewVMTest`/Progress tests that
  reference it. Also delete the now-stale `debugRescheduleTransfers` doc mentions here and in
  `MigrationPlan.kt`, and `MigrationPlanRepository.rescheduleTransfer(...)` if it loses its
  last caller.
- `OnMigrationSyncCompletedUseCase.kt`, `GetHomeMessageUseCase.kt` polling, StalePlan retry in
  `MigrationReviewVM.kt`, `CompactBlockProcessor` grid-cut, `open_at` busy/mmap: UNCHANGED.

## Part 6 — Remaining two-sources-of-truth flags (explicitly accepted or follow-ups)
1. Status-only `from_parts(Failed)` swaps (R3) — accepted until the engine grows
   cancel/fail primitives.
2. App-side `MigrationPlanRepository` cache — display cache only; live merges via
   `withLiveState` correlate by id. Accepted.
3. Mainnet checkpoint retention (~100 blocks) < 144-block bucket — boundary anchors can be
   pruned before proving on mainnet. CORE question for Kris/Danny (retention pin at bucket
   boundaries); testnet (12-block grid) unaffected. No code change here.
4. Expired transfers: wire engine `rebuild_expired_transfer(_unsigned)` (rc.1) to replace the
   terminal Expired dead-end + Keystone re-sign session. Follow-up ticket, not this cleanup.

## Part 7 — Implementation order (for the implementing subagent)
1. R1+R2 (Rust) → `cargo check && cargo test migration` (expect select_shift tests deleted).
2. K1+K2+K3 (SDK Kotlin) → sdk-lib unit tests compile.
3. Part 3+4+5 (app) — worker rewrite, recovery/finalize, ProgressVM button, tests.
4. Full test run + install + launch + crash-check (commands at top).
5. One commit per repo: `refactor: plan-fixed Lane A wakes, engine-only plan state (no shift)`.

Estimated size: ~2–4 h focused work; ~15 files app, ~7 files SDK; net LOC negative.

## Open decisions for the user (blockers for the implementer)
- **D1**: Include preparations in `MigrationTransferStates` (recommended YES — closes the
  Lane B prep-window gap; UI unaffected).
- **D2**: Progress screen "Reschedule" button — remove entirely (recommended) vs. repurpose
  as "retry Lane B now" (schedules MigrationWorker immediately, no engine mutation).
- **D3**: Delete `debugRescheduleTransfers` entirely (recommended YES; debug menu no longer
  references it).

---

## USER AMENDMENTS (2026-07-28 late afternoon — BINDING, override anything above that conflicts)

A1. **Lane A always syncs when it wakes.** No "nothing-to-prove → skip sync" decision row. A
    woken Lane A runs sync + `finalizeReadyTransfers` + reconcile (proof falls out of the sync
    when the moment is right). The only reasons NOT to sync on a wake: (a) the post-broadcast
    privacy gate (`isSyncBlocked`), (b) the imminent-due step-aside — and that step-aside applies
    ONLY when the imminently due transaction is ALREADY proved (a broadcast that can actually
    happen). Wake times come exclusively from anchor boundary heights (+ settle margin) of
    unproved+unsent transactions; floor 60 s; NO cadence. Cadence remains only as the
    states-unavailable fallback. Add a `TODO` comment: may later be adjusted to periodic sync
    (final impl per Kris) — do not build it now.

A2. **Lane B is send-only of the single next Rust-served transaction — with a sync fallback.**
    Happy path: `executeNextPendingTransfer` returns Executed (the engine only serves proved
    txs). If it returns `AwaitingProof`, Lane B CONVERTS THIS RUN INTO A LANE A RUN: perform the
    same sync + finalize + reconcile (respecting the same privacy guards), do NOT broadcast in
    the same run (sync XOR broadcast per execution), and re-arm the broadcast for the next live
    window (existing scheduleForNextLiveWindow, floor stays). The awaiting-proof strikes counter
    stays: if even Lane B's own sync fails to make the tx provable repeatedly, escalate as today.

A3. **Our reschedule stack is deleted end-to-end** (Rust `rescheduleUnprovenTransferNative` +
    `select_shift_boundary` + `debugRescheduleTransfersNative`, all Kotlin plumbing, all their
    tests). "Rescheduling" is the ENGINE's semantics: unexpired txs prove late and broadcast late
    (`next_broadcastable`); expired txs → `rebuild_expired_transfer(_unsigned)` (follow-up
    ticket, not this pass).

A4. **Anchor persistence is Rust's job — verify, don't reimplement.** Confirm
    `anchor_retention_interval(network)` resolves to 144 on mainnet and 12 on testnet in BOTH
    `open_at` (migration.rs) and the scanning path (`lib.rs` `wallet_db`), so retained grid
    checkpoints match the buckets plans draw from. If testnet resolves to anything but 12, fix
    the mapping (config read, not new logic).

A5. **D1 resolved — include ALL transactions (preparations included) in the surfaced states**,
    with `isTransfer` flag, exactly as proposed. Lane B stays "send the single next due tx the
    engine serves" (kind-agnostic).

A6. **D2 resolved — KEEP the Progress screen's Reschedule button, with new semantics:**
    "Send now" = broadcast the already-proved due transfer (existing path). "Reschedule" =
    SYNC NOW (trigger the same sync+finalize Lane A does, foreground) + the transfer goes out in
    the next window (background or next app open). No plan mutation, no engine shift call.

A7. **D3 resolved — delete `debugRescheduleTransfers`** (hook + native + plumbing). The only
    debug action that must remain is the full "Migration restart".
