# Two-lane migration background execution — CANONICAL spec + status + test case

## 0. LATEST STATUS (2026-07-28 evening) — FULL E2E SUCCESS ✅

A complete background migration ran end to end with the app closed and no
intervention: plan committed 20:42 → Lane A anchor-driven sync+prove → checkpoint
backfill → Lane B broadcast 20:52:06 → `migration complete!`. Log proof (single-transfer
plan, the fast completion case):
```
20:42:32 committedPlan(1 transfer, boundary 4213512) + sync-session restart (retention) ✓
20:43:32 LaneA wake at anchor height → sync
20:45:25 checkpointBackfill: orchard + ironwood @ 4213512 (empty gap) ✓
20:45:52 LaneB DEFER — post-sync privacy buffer ✓
20:51:08 LaneA SKIP_NEAR_DUE, unproven=[] ✓
20:52:06 transfer sent + migration complete! ✓
```
Earlier the same evening a 9-transfer plan sent 8/9 in the background before lifecycle
churn (self-inflicted reinstalls) stalled the last one — the fixes below are exactly that
churn hardened.

**SCHEDULING IS ANCHOR-DRIVEN, NOT CADENCE (test phase).** Lane A currently wakes ONLY at
anchor-boundary heights (+ settle margin); cadence is a states-unavailable fallback only.
The FINAL Android implementation per Kris returns to a fixed sync CADENCE (60 min mainnet)
and consumes the engine's `sync_wakeup_schedule` (#2801, present in the merged engine, not
yet wired). The current anchor-driven mode exists for deterministic testnet full-flow
testing — do not mistake it for the shipping design. `laneACadence()`/`laneAFirstRunLead()`
carry TODO notes to this effect.

### The 9 bugs fixed today (autonomous E2E loop) — grouped by system layer

ANCHOR/CHECKPOINT CHAIN (the real design gaps — nothing proved in the background until all
three landed):
1. **Session-scoped retention floor.** The engine reads the migration anchor-retention floor
   once, at `start_session`; a session already live at commit didn't protect the new plan's
   boundaries. Fix: `Synchronizer.restartSyncSession()` after commit
   (`FinalizeMigrationScheduleUseCase`).
2. **Retention gated on an in-progress migration.** But ZIP 318 draws boundaries in the recent
   PAST (age 1..16 buckets below tip), so retention that only turns on once a plan exists loses
   exactly the checkpoints the plan draws against. Fix: ALWAYS-ON retention (18-bucket window
   below the scanned tip) + commit-time `BoundaryCheckpointMissing` validation → re-propose.
3. **Sync checkpoints per scan sub-batch, not per block.** A grid height inside a multi-block
   chunk gets no checkpoint even with retention. Fix: empty-gap checkpoint BACKFILL (copy the
   nearest earlier checkpoint's position across a provably commitment-free gap — exact, not an
   approximation). Real fix (grid-aligned sub-batch cuts) belongs in slipstream-core → report.

LIFECYCLE ROBUSTNESS (all variants of "a self-rechaining lane must never silently vanish" —
triggered by reinstalls here, by Doze/kill/reboot in production):
4. **Worker returned success when the SDK wasn't up yet** (post-update WorkManager re-run before
   the synchronizer inits) → consumed the OneTimeWork without re-arming → lane dead. Fix:
   `Result.retry()`.
5. **App-open recovery burned its throttle window on an SDK-null attempt.** Fix: un-stamp the
   throttle when the SDK isn't ready.
6. **Both lanes woke in the same second** (WorkManager batches similar due times) → Lane B saw
   Lane A RUNNING and deferred a full buffer every cycle → deterministic lockstep (5 proved, due
   transfers stuck 17 min). Fix: Lane B waits out a same-instant Lane A wake (≤30s) instead of a
   blind buffer defer.
7. **Broadcast over a cold-bootstrapping Tor client hung with no timeout** until the WorkManager
   ceiling, nothing re-armed. Fix: 3-min timeout around the broadcast → retryable (a late detached
   completion is safely classified as a duplicate submit).
8. **Lane B had no reviver.** Lane A was revived by recovery + SYNCED hook; Lane B only re-armed
   at the end of its own run → an update mid-plan killed all future broadcasts (final transfer
   proved+due with no job to send it). Fix: revive BOTH lanes in the SYNCED hook AND app-open
   recovery.
9. **Revival keyed on the app-side plan cache**, which can be lost while the engine still holds a
   live run. Fix: gate revival on the engine's `MigrationState.InProgress` (single source of truth).

### Missed / can't-execute handling — HOW IT WORKS NOW (answers the "Danny shift" question)

We NEVER recompute safe points. The engine draws and stores each transfer's `anchor_boundary` +
`scheduled_height` + `expiry_height` ONCE at commit; they are fixed. Our hand-rolled reschedule
(a second source of truth) is DELETED.
- **Lane A didn't run / Lane B has no proof:** Lane B's run converts into a sync run (does the
  prove itself, against the STORED boundary), never broadcasting in the same execution, then
  re-arms the send from the engine's own live `scheduled_height` (`getMigrationTransferStates`),
  translated height→time via the measured block rate. We only TIME the wake; the engine already
  decided the safe point.
- **Missed but UNEXPIRED:** no action needed. ZIP 374 (signature excludes the anchor) + retained
  boundary checkpoint mean the transfer is still broadcastable against its original boundary, days
  late; the engine's `next_broadcastable(tip)` serves it whenever Lane B next wakes. "Missed" =
  just "late".
- **EXPIRED (chain passed `expiry_height`):** THIS is the one case that needs a recompute, and it
  is the engine's `rebuild_expired_transfer(_unsigned)` — Danny's "migration shift" (Slack
  1785231345.187899). We do NOT wire it yet: today we `mark Failed` (migration.rs ~1176), killing
  the plan. FOLLOW-UP TICKET. Mainnet expiry is ~30 days out, so rare in practice, but the correct
  handling is the engine rebuild (fresh boundary+expiry, re-sign), not plan death.

---

Date: 2026-07-28. This document supersedes and replaces:
`2026-07-27-migration-background-anchor-sync-understanding.md`,
`2026-07-27-migration-anchor-sync-diff-vs-core-guidance.md`,
`2026-07-27-migration-two-lane-android-design.md`,
`2026-07-24-migration-background-sync-advance-design.md`, and the executed plan
`plans/2026-07-28-migration-two-lane-execution.md` (all deleted; git history keeps them).

---

## 1. Design (implemented)

Authority: Kris's Slack verdict 25.7. (#orchard-ironwood-migration ts 1784910426.257129 —
proving belongs to the sync path; "The broadcast step should not sync"; the
fetch→prove→broadcast weld "is a bug!"; missed windows shift the plan) + ZIP 318 ("A single
Worker execution MUST either synchronize (updating anchors and proofs) or broadcast, never
both") + swift-sdk #1853.

**Two WorkManager lanes**, self-rechaining OneTimeWork, active only during a migration:

- **Lane A — `MigrationSyncWorker`** (sync + prove, never broadcasts). Cadence: testnet 5 min /
  mainnet 60 min ± 10 min jitter. Run: window check (live states + estimator; steps aside from
  `due − buffer`, hot-loop-guarded re-arm) → gate check (`isSyncBlocked`) → `syncToTip` →
  `finalizeReadyTransfers()` (proves everything whose boundary settled — transfers against
  their drawn bucket boundary, preparations against the natural anchor) →
  `reconcileInvalidations()` (invalid → notify + cancel BOTH lanes) → stamp
  `LastNetworkActivityStore` → re-arm. Stops itself on terminal migration state.
- **Lane B — `MigrationWorker`** (exclusively broadcast; the submit is its only network
  touch). Preflight defers (local delay) while ANY sync source is live: Lane A RUNNING,
  synchronizer SYNCING (2s-timeout read, unknown → defer), or quiet gap < privacy buffer.
  Sets a self-expiring (120 s) broadcast-in-flight flag ORed into `isSyncBlockedNow` so sync
  cannot start mid-submit. Tri-state `executeNextPendingTransfer(options, useEstimatedTip)`:
  - `Executed(result)` → existing Success/NetworkError/Tor/InvalidNote/Expired handling;
    Success resets the shift counter and arms the post-broadcast privacy buffer.
  - `AwaitingProof(id)` → engine shift via `rescheduleUnprovenTransfer` (silent); the shift
    counter increments ONLY when a sync completed since the last shift; the 3rd counted shift
    escalates once (reconcile → notify invalid, else manual-confirmation notification).
  - `NothingDue` → re-arm for the next live window.
- **Foreground hook**: on synchronizer SYNCED while a plan exists → finalize + reconcile +
  stamp. Daily `SyncWorker` no-ops during a migration. `MigrationSendingVM` uses the same
  tri-state (weld removed).
- **App-open catch-up** (`CheckMigrationRecoveryUseCase`, 10 s throttle): re-arms Lane A if
  absent; at-most-one-overdue — keeps the earliest overdue, `rescheduleUnprovenTransfer`-shifts
  the rest; routes TorFailure → `sending/`, RequiresAttention → `invalid/`,
  ready-without-background → `transferreview/`, overdue → `progress/`.

**Hard invariants** (all grep-verified in the final review):
1. Estimated tip accelerates ONLY `scheduled_height` due-ness; expiry/rebuild/invalidity always
   use the scanned tip. The `isSyncBlockedNow` gate always passes −1 (scanned).
2. `expiry_height` is never mutated (ZIP 374 sighash covers it; shifts move
   `scheduled_height` + boundary only, PCZT bytes byte-identical).
3. Notifications are never load-bearing; app-open is the lossless fallback driver.
4. `nextDueTransfer` is kind-AGNOSTIC (matches engine `next_broadcastable`): multi-transaction
   preparation layers are broadcast by the same loop. (A Transfer-only filter deadlocked a live
   plan — proved+due preparation had no broadcaster while the kind-agnostic overdue gate held
   sync blocked. **iOS swift layer has the same Transfer-only filter — report to Kris.**)

**Height→time projections** use the SDK's **measured block rate**
(`estimatedSecondsPerBlock()`: average header-timestamp spacing over the last 100 scanned
blocks, clamped 5–150 s, 75 s fallback) — testnet's minimum-difficulty rule makes real spacing
bursty (observed: ~73 blocks in 5 min), so the 75 s constant rendered minute-scale schedules
as "~1 hour" and scheduled workers far past real due heights. Wired into: plan persistence,
Lane A window check, Lane B re-arm, ProgressVM delays + display merge, Review labels + plan
log. Engine truth stays height-based — projections are display/scheduling only.

**Rust/SDK primitives** (backend-lib, engine = librustzcash **latest main via local path-deps**
`[patch.crates-io]` → `../../librustzcash`, includes #2801 `sync_wakeup_schedule` — not yet
consumed; revert to published crates before release): tri-state `nextDueTransferNative`
(+terminal guard, scanned-tip expiry filter), `rescheduleUnprovenTransferNative` (bucket-grid
redraw, empty-candidate → keep boundary), invalidation side-table
`zashi_migration_invalidation` (reason → `RequiresAttention(InvalidTransfer)` reachable),
`reconcileInvalidatedTransfersNative` (mark_mined → own-txid probe (submit-crash self-heal) →
spent-check with no-false-positive rule), duplicate-submit classifier + mined-height probe
(non-gRPC rejection ≠ automatic plan death), network-scaled privacy buffer (testnet 3 min /
mainnet 10 min), `syncToTip`, `ChainTipEstimator` (+measured rate), both `open_at` connections
with 5 s busy_timeout.

## 2a. EVENING UPDATE (28.7.) — supersedes stale parts of §1/§2 below

- **Cleanup landed** (commits `694700067` app / `8f82d782` SDK): Lane A wakes ONLY at anchor
  boundary heights + 2-block settle margin (no cadence; cadence = states-unavailable fallback;
  TODO notes for the final cadence-driven impl per Kris + #2801). Lane A always syncs on wake
  except the privacy gate and a step-aside that now applies only when the imminent due tx is
  ALREADY proved. Lane B is send-only; on AwaitingProof it converts the run into a sync run
  (never sync+broadcast in one execution) and re-arms. The entire hand-rolled reschedule/shift
  stack is DELETED (Rust+Kotlin+tests; engine is the only source of truth). States surface ALL
  transactions with `(id, isTransfer, isSent, isProved, scheduledHeight, anchorBoundaryHeight)`.
  Progress "Reschedule" button = sync now + next window (no plan mutation).
- **Anchor provability root cause fixed**: scan batches are cut at anchor-grid multiples near
  the tip (CompactBlockProcessor `clampBatchEndToAnchorGrid`, 12 testnet / 144 mainnet), so a
  checkpoint exists exactly on every boundary; retention is the engine's `AnchorRetention`
  (144/12, verified in open_at + lib.rs). Earlier livelock (Lane A stepping aside forever while
  Lane B awaited proof) and its overdue-heuristic workaround are gone with the rewrite.
- **DB robustness**: busy_timeout 15 s + `mmap_size=0` on both migration connections,
  "database is locked" added to the SDK's transient retry classifier, StalePlan on commit →
  re-propose once and retry (engine's note-index snapshot semantics). Milan's SQLite rules
  verified: ONE libsqlite3-sys node, one zcash generation, no framework SQLite on the engine DB.
- **Kris's `feature/ironwood-slipstream` MERGED** (SDK merge `2d7f8451`, app adaptation
  `b0c824b81`, all pushed): engine generation now git-pinned at librustzcash `a00f4a7a`
  (pool_migration 0.1.0-rc.3 etc.), our `[patch.crates-io]` path-dep block REMOVED (it had been
  silently inactive), renamed migration API (MigrationTxId→MigrationTransferId,
  note_split→denominations), transfer ids are Long end-to-end, voting disabled upstream.
  `sync_wakeup_schedule` (#2801) IS present in this engine generation — wiring it into Lane A is
  the designated follow-up. FLAGS: (1) Keystone batch redaction contract changed upstream
  (dummy `spend_auth_sig` retained, alpha cleared) — needs an FW-compat check before Keystone
  batch signing is trusted; (2) persisted `MigrationPlan` transfers changed id String→Long —
  plans persisted by older builds fail deserialization (do a Migration restart after install);
  (3) expired-transfer handling should move to engine `rebuild_expired_transfer` (follow-up
  ticket; we currently mark the migration Failed).
- THE test case in §3 is unchanged and still pending a full clean run on this new stack; the
  §3 note about the first Lane A run applies with the rewrite: expect
  `LaneA: next wake from boundary — tx=… wakeHeight=…` logs instead of cadence re-arms.

## 2. Where we are (status, 28.7. afternoon)

- **All code implemented and committed LOCALLY (unpushed — push after user testing)** on
  `android-slipstream-ironwood-chp` in both worktrees:
  app `z/wt/migration/zodl-android`, SDK `z/wt/migration/zcash-android-wallet-sdk`
  (+ `z/wt/migration/librustzcash` checkout on origin/main, `z/wt/migration/slipstream`).
- Built via 13-task subagent plan + adversarial reviews + final whole-branch review (fable) +
  fix wave F1–F7. Tests: ui-lib green (except pre-existing NearSwapQuoteTest), sdk-lib green,
  cargo 32 green (pre-existing `unknown_any` fail is known/unrelated).
- **Live testing on emulator found and fixed** (each committed separately): 2 Koin startup
  crashes (DI cycle via AccountDataSource → Lazy injection; `factoryOf` vs defaulted lambda →
  explicit factory), preparation-broadcast deadlock (invariant 4), measured block rate
  (everywhere), "database is locked" crash (busy_timeout + resilient gate tick), back-button
  loop on Progress + stacked Home entries (HomeVM.init trigger removed + 10 s recovery
  throttle), SDK Twig init (Synchronizer.new; MigrationSdk.new NOT yet covered — SDK-built
  paths still log with the empty process column; cosmetic).
- **Full end-to-end background migration has NOT yet completed** — plans progressed (split +
  preps + first sends observed on-chain: two Sent today ~5.008/~4.9994 TAZ), logging is
  complete, we know what to watch (see §3).
- Environment: testnet **Internal debug** flavor (`installZcashtestnetInternalDebug`),
  emulator **emulator-5556** (AVD Z_-_Droid; physical device 58191FDCH002ET — never touch),
  funded testnet wallet on-device. `gradle.properties` local SDK path stays uncommitted.

**Open items:** (1) F1 ticket — foreign-spend detection inactive in production (parser wants
exactly 1 Orchard action, transfers have 2 = real + dummy padding; rework = match candidates
against the wallet's KNOWN nullifier set; warn log active). (2) Report to Kris: iOS
Transfer-only next_due filter + multi-tx prep layers; prep-schedule vs app's instant split
broadcast. (3) Consume #2801 `sync_wakeup_schedule` for Lane A when SDK-exposed. (4) Push +
PR after the §3 test passes. (5) Minor: MigrationSdk.new Twig init; Koin `checkModules` test
would have caught both DI crashes — worth adding.

## 3. THE test case (what we're trying to prove)

**Goal: take a funded wallet, start an AUTOMATIC migration, close the app, and watch the
whole plan execute in the background exactly as designed.**

Steps:
1. `ANDROID_SERIAL=emulator-5556 ./gradlew :app:installZcashtestnetInternalDebug -Pcoverage=false --max-workers=1 -PSDK_INCLUDED_BUILD_PATH=../zcash-android-wallet-sdk`
   (the `-P` flag is REQUIRED — project `gradle.properties` has the property empty and the
   user-level pin is commented out, so a plain build silently uses the Maven SDK without our
   changes), launch, wait for SYNCED.
2. Home → Migration banner → create AUTOMATIC plan → check the
   `MIGRATION_DIAG Plan:` dump (per-transfer anchor/send/expiry + dueIn/gapFromPrev at the
   measured rate) → confirm (sign) → `MIGRATION_DIAG committedPlan:` dump shows the REAL drawn
   boundaries (expect shared buckets across neighbours).
3. **Close the app** (swipe away or HOME). Watch:
   `adb -s emulator-5556 logcat | grep MIGRATION_DIAG | grep -vE "starting|succeeded"`

Expected sequence (pass criteria):
- `LaneA: run start … decision=RUN (… secondsPerBlock=N)` every ~5 min →
  `syncToTip result=SYNCED_TO_TIP` → `finalizeReadyTransfers: PROVED …` (preps with
  `boundary=None`, transfers with `boundary=Some(grid)`) **ahead of their windows** →
  re-arm log.
- `LaneB: run start … preflight=BROADCAST` at each window → `transfer sent — txId=…` →
  `next transfer scheduled in …`. **No sync activity within ±privacy buffer (3 min testnet)
  of any broadcast** — Lane A must show `SKIP_NEAR_DUE`/gate skips around windows.
- Occasional `AwaitingProof → shifted` is fine (bursty chain); counter stays low; NO
  escalation notification unless something is really wrong.
- Preparations broadcast through the same loop (invariant 4) — no `SKIP_GATE_BLOCKED` loops.
- End state: `migration complete!` log + Complete/celebration on next open + Lane A stops
  (terminal check) + home banner cleared. Balance moved to Ironwood.

Failure modes to capture (all have existing routing): NetworkError/Tor → notification +
`sending/`; InvalidNote/Expired → `invalid/`; repeated `SKIP_GATE_BLOCKED` without progress =
regression of invariant 4; `database is locked` in logs = busy_timeout regression;
back-from-Progress bouncing = recovery-throttle regression.

## 4. How to resume after a context compact

Read this file + memory `project_two_lane_migration_implemented`. Worktrees above; ledger
history is in git (`git log` both repos, everything after `a88a956c8`/`0e4b661f`). Emulator
`emulator-5556`; always relaunch the app after any reinstall and check
`logcat -d | grep -c "FATAL EXCEPTION"`.
