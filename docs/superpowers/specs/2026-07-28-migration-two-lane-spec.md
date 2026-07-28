# Two-lane migration background execution — CANONICAL spec + status + test case

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
1. `ANDROID_SERIAL=emulator-5556 ./gradlew :app:installZcashtestnetInternalDebug -Pcoverage=false --max-workers=1`,
   launch, wait for SYNCED.
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
