# Migration two-lane background execution — Android design

Date: 2026-07-27
Status: Approved design (brainstormed with Dominik), revised after independent adversarial
review (same date — findings B1/B2/M1–M7/m1–m6 incorporated; see §14), pending implementation
plan.

References:
- Kris's verdict, Slack #orchard-ironwood-migration thread ts 1784910426.257129 (2026-07-25):
  proving belongs to the sync path; "The broadcast step should not sync"; sessions separated;
  the fetch→prove→broadcast weld "is a bug!"; missed windows shift the plan.
- ZIP 318 (https://zips.z.cash/zip-0318): "A single Worker execution MUST either synchronize
  (updating anchors and proofs) or broadcast, never both."
- swift-sdk [#1853](https://github.com/zcash/zcash-swift-wallet-sdk/pull/1853) (prove
  opportunistically; tri-state due result) — Android FFI already has the prove/broadcast split
  (`finalizeReadyTransfersNative` / `nextDueTransferNative`, adopted 2026-07-23).
- Companion analysis: `2026-07-27-migration-anchor-sync-diff-vs-core-guidance.md`,
  `2026-07-27-migration-background-anchor-sync-understanding.md`.
- Testnet fast bucketing: android SDK PR #2042 — `AnchorBucketInterval::custom(12)` for
  TestNetwork (12 blocks = 15 min) + scaled send delays. Already merged on this branch.

Scope decision: **app + SDK, full** — including SDK due-ness semantics and new engine-adjacent
Rust work in `backend-lib` (§7). Estimated-height due-ness is computed by us now (Kotlin
layer), designed to be swapped for a core-provided value when librustzcash exposes one.

## 1. Goal and principles

Make a scheduled (AUTOMATIC) migration progress in the background per Kris's model, best-effort:

1. **Sync+prove and broadcast never share a window** (ZIP 318 MUST). Lane B performs no network
   activity other than the submit itself — and it defers while *any* sync is live (Lane A,
   foreground synchronizer, or the daily `SyncWorker`), not just its sibling lane.
2. **Proving is opportunistic** — it happens whenever a sync runs (background Lane A or
   foreground), never at broadcast time.
3. **Missed windows shift the plan** — a transfer that cannot proceed is silently rescheduled
   into the future (Kris: the whole plan shifts). No sync bursts from the broadcast lane; the
   burst-in-`WAIT_AND_RETRY` mechanism is removed. **Shifts never touch `expiry_height`** —
   the ZIP 374 sighash covers expiry (changing it kills the signature) and repeated shifts
   only spend down the fixed 30–60-day ZIP 203 window.
4. **Estimates accelerate due-ness, never expiry or invalidity.** The estimated tip (§4) is
   used solely for `scheduled_height` comparisons; every check that can expire, rebuild, or
   invalidate a transfer runs against the scanned tip only.
5. **Notifications are never load-bearing** — every notification is a shortcut to an app-open
   flow that works identically without it.
6. **App-open is the universal fallback driver** — with no background and no notifications the
   migration still progresses (slowly) on app opens; nothing is ever lost (durable anchor
   retention, 30–60-day expiry, plan shifting).

## 2. Architecture — two lanes

Both lanes are WorkManager `OneTimeWorkRequest`s that re-arm themselves ("each one sets up the
next"). No `PeriodicWorkRequest` — its 15-minute minimum equals the testnet bucket interval,
and self-chaining gives per-run jitter control. Both lanes are active only while a migration is
in progress (scheduled at commit, cancelled at terminal state — including invalidation, which
must cancel **both** lanes — reconciled at app open). WorkManager chains survive reboot; the
due-alarm does not (§10).

### Lane A — `MigrationSyncWorker` (new): sync + prove, never broadcast

One instance per wallet (not per account — sync traffic is wallet-global); its window check
considers **all** accounts' next due times, mirroring `isSyncBlockedNow()`'s all-accounts loop.
Cadence: **interim** fixed and plan-independent — mainnet 60 min ± jitter, testnet 5 min.
**Target state**: consume the engine-computed minimal wake-up schedule once
[librustzcash#2801](https://github.com/zcash/librustzcash/pull/2801)
(`MigrationState::sync_wakeup_schedule` — minimal piercing set over proving windows, bucketed
heights + jitter as thundering-herd defense, mandatory immediate wake for overdue; part of
epic #2630) lands and the SDK exposes it — the fixed cadence then remains as fallback when the
schedule is empty/unavailable. Run body:

1. **Window check**: compute `nextEstimatedDue` from **live** transfer states
   (`migrationTransferStates()`, i.e. current engine `scheduled_height`s — NOT the
   `MigrationPlanRepository` cache, which is stale by design after any shift) +
   `ChainTipEstimator`. If `now ≥ nextEstimatedDue − privacyBuffer` → skip this run and re-arm
   for `max(nextEstimatedDue + privacyBuffer, now + minBackoff)` (the `max` prevents a
   hot-loop when the due time is already in the past because Lane B hasn't run yet).
2. **Gate check**: if `isSyncBlocked()` (scanned-tip overdue, or post-broadcast buffer) →
   skip, re-arm.
3. **Sync to tip**: `syncToTip(timeout)` — thin wrapper over the existing `syncBurst`
   machinery terminating on `SYNCED`. The timeout is a per-run bound, not a correctness
   requirement — a far-behind wallet catches up across successive runs; proving just waits.
4. **Prove sweep**: `finalizeReadyTransfers()` (idempotent, skip-not-fatal — unchanged).
5. **Invalidation check**: `reconcileInvalidations()` (new, §6). On invalidation →
   `notifyMigrationPlanInvalid` (if permitted), cancel **both** lanes; the app-open router
   takes over.
6. Stamp the shared **last-network-activity timestamp** (§3) with the run end, re-arm.

### Lane B — `MigrationWorker` (slimmed): exclusively broadcast

Per-transfer scheduling via `MigrationScheduler` as today, keyed by `accountKeyId`. Run body —
the current `finalizeReadyTransfers()` call and the whole sync-burst `WAIT_AND_RETRY` branch
are removed:

1. **Overlap check** (all sync sources, M3): defer with a local delay (§5) if any of:
   Lane A RUNNING (`WorkManager.getWorkInfosForUniqueWork`); the synchronizer status is
   SYNCING (foreground session or daily `SyncWorker` live); or
   `now − lastNetworkActivity < privacyBuffer`. Before the submit itself Lane B sets a
   short-lived **broadcast-in-flight flag** that `isSyncBlockedNow()` ORs in, so a sync
   session cannot *start* mid-submit either (bidirectional yield through existing gate
   plumbing); the flag clears on record or run end.
2. `nextDueTransfer(estimatedTip)` → tri-state:
   - **READY** → extract → submit (Tor per flow-scoped flag) → record → post-broadcast privacy
     buffer (existing) → schedule next per plan. An overdue-but-proved transfer is simply READY
     and self-heals here.
   - **AWAITING_PROOF** → **engine shift** via the NEW `rescheduleUnprovenTransfer` (§7.4).
     Consecutive-shift counter (§2.B.4). Schedule next for the shifted time.
   - **NOTHING_DUE** → schedule for the next plan time.
3. Result handling: Success/NetworkError/Tor flows unchanged, including plan-repo
   write-through and notifications. Two corrections to today's behavior land here:
   - **Submit-crash reconciliation (M6)**: a process death (or timeout after server-side
     acceptance) between submit and `recordTransferResult` leaves a `Proved` transfer that is
     already on-chain. Reconciliation (§6.D) additionally probes `get_tx_height(txid(pczt))`
     for `Proved` transfers (the txid is deterministically extractable from the stored PCZT)
     and marks Broadcast+Mined on a hit; a "duplicate/already in mempool" submit rejection is
     treated as success. No additional persisted "sending" sub-state is introduced — it could
     only say "a submit may have happened", which the txid probe already answers
     definitively (discussed and rejected as redundant).
   - **Invalid/Expired recording becomes real (M1, §6.C)** — today it is a no-op.
4. **Consecutive-shift counter** (per transfer id, per account, persisted). Rationale: the OS
   throttles the app as a whole, so "Lane B runs while Lane A is starved" is not the real
   scenario. AWAITING_PROOF at a Lane B run has three causes: (a) same-wake ordering (B
   drained before A after a Doze window — benign, self-heals next window), (b) Lane A runs
   but sync keeps failing (connectivity — shifting is the correct response, escalation would
   be noise), (c) **sync succeeds and the transfer still won't prove** (a spent note that
   detection A somehow missed, or a witness/retention hole) — the only case worth escalating.
   The counter therefore counts only case (c): **increment only when a successful sync
   completed since the previous shift** (`lastNetworkActivity` newer than the last shift
   timestamp); otherwise shift silently without counting. Reset on READY, a different
   transfer id, or replan. On the **3rd** counted shift: run `reconcileInvalidations()`; if
   clean → `notifyManualConfirmationRequired` once. Shifting itself continues on subsequent
   runs (the plan must stay alive — the notification is not a stop), and the notification
   does not repeat for the same transfer.

### Foreground equivalent of Lane A

During an active migration, when the foreground synchronizer reaches `SYNCED`, a
WalletCoordinator-level observer calls `finalizeReadyTransfers()` + `reconcileInvalidations()`
and stamps the last-network-activity timestamp. The daily `SyncWorker` **no-ops while a
migration is active** (Lane A supersedes it — more frequent, coordinated; checked at
execution time so the system-shifted run time is irrelevant); outside migration it stamps the
timestamp too.
`MigrationSendingVM`'s `finalize + execute` weld is removed; the VM uses the same tri-state
call as Lane B.

## 3. Window hygiene without a lock

No shared mutex. Complementary execution-time checks:

- **Lane A schedule awareness** (§2.A.1): one-sided skip — from `due − buffer` onward Lane A
  keeps stepping aside until the transfer is actually broadcast or shifted (this is what makes
  a Doze-delayed Lane B safe), with the `max(..., now + minBackoff)` re-arm preventing
  hot-loops.
- **Lane B runtime checks** (§2.B.1): Lane A running-state query + synchronizer-status check +
  quiet gap from the shared **last-network-activity timestamp** (a single wallet-global store
  written by Lane A, the foreground hook, and `SyncWorker` at session end).

Residual risk: WorkManager offers no timing guarantees, which is exactly why the checks run at
execution time, not scheduling time. Combined with the existing post-broadcast buffer this
yields ≥ privacyBuffer of network silence on **both** sides of every broadcast, against every
sync source.

## 4. Estimated chain tip

Broadcast due-ness must not depend on a fresh scan (that is what welded sync to broadcast).
The wallet derives the current height from its own data:

```
estimatedTip = maxScannedBlockHeight + floor((now − headerTime(maxScannedBlock)) / 75 s)
```

- Computed in the SDK Kotlin layer (`ChainTipEstimator` interface) from the max *scanned*
  block and its header timestamp (readable via the `blocks` table) — no extra persistence.
  Conservative: floor; clamp negative elapsed (miner timestamps may lead wall clock) to 0.
  Note the base is the *scanned* tip, which is also what Rust compares against — not
  `wallet.chain_height()`, which can run ahead of scan.
- Passed to JNI as `estimated_tip: jlong` (−1 = disabled). Rust computes
  `effective_tip = max(scanned_tip, estimated_tip)` and uses it **only** for
  `scheduled_height` due-ness comparisons. **`is_expired`, rebuild eligibility, and anything
  that can mark a transfer invalid always evaluate against the scanned tip** (M2 — a
  forward-skewed clock must never falsely expire a valid transfer; the tri-state port also
  adds an expiry filter to `nextDueTransferNative`, which today has none, and that filter uses
  the scanned tip).
- **The sync-blocking gate stays on the scanned tip**: `isSyncBlockedNow()` call sites pass −1.
- `ChainTipEstimator` is the seam for the future core-provided value.

Worst-case failure of the estimate is a mistimed submit the node rejects (→ existing recovery)
or a late broadcast. It can never expire, invalidate, or rebuild anything (§1.4).

## 5. Shift semantics — two distinct kinds

| Kind | Trigger | Who moves what |
|---|---|---|
| **Engine shift** | AWAITING_PROOF (missed Lane A) | NEW `rescheduleUnprovenTransfer` (§7.4) rewrites `scheduled_height` (+ boundary where possible) in the persisted engine state. Plan repo write-through updates banner/Progress times. Silent (notification only at the 3rd consecutive shift). |
| **Local delay** | READY but a sync source is live / quiet gap unmet | WorkManager delay by minutes; engine untouched. Always silent. |

**App-open catch-up (B2 — new work, was wrongly specced as an existing engine policy):** the
engine has no at-most-one-overdue enforcement (`scheduling.rs` lists it explicitly as
"enforced elsewhere, not here", and nothing else implements it). The app-open reconciliation
(`CheckMigrationRecoveryUseCase`) implements it using the same reschedule primitive: when
multiple transfers are overdue, keep the earliest, `rescheduleUnprovenTransfer`-shift the rest
onto fresh future heights, then route as today (`transferreview/`/`progress/`). Without this, a
long-closed wallet would drip its whole backlog out back-to-back at 10-minute spacing — the
correlated-burst pattern the Poisson schedule exists to prevent.

## 6. Invalidation detection (defense in depth)

When can "a funding note is already spent elsewhere" be known? Four points, ordered:

- **A. During sync (new, primary).** The scan sees the note's nullifier in a foreign
  transaction — certain knowledge, potentially days before the send window. New Rust
  `reconcileInvalidatedTransfersNative`: for each pending (Signed/Proved) transfer, derive its
  funding-note nullifier (notes are embedded in the stored anchor-less PCZTs; nullifiers
  computable with the FVK) and check the wallet DB's spent set, excluding spends by the plan's
  own txids; reorg-robust. Spent outside the plan → **persist invalidation with a reason**
  (M1): the engine's `Failed` status carries no reason and `MigrationTxState` has no Invalid
  variant, so the reason (InvalidTransfer vs TransferExpired) is persisted alongside the
  migration store and read by `derive_migration_state` → `RequiresAttention(InvalidTransfer)`
  becomes reachable (today it never is — Failed maps unconditionally to TransferExpired).
  **Invalidation is migration-terminal**: remaining pre-signed transfers die with the plan and
  a replan is a full re-sign — for Keystone, a new signing ceremony. Called from Lane A
  (§2.A.5) and the foreground hook.
- **B. At prove time (unreliable, unchanged).** The prover cannot distinguish "spent" from
  "not yet scanned" (`UnknownSpentNote` stays in the transient set). Not used for detection;
  the 3×-shift bound (§2.B.4) is the backstop.
- **C. At submit (corrected — today this is a no-op).** `recordTransferResult` for
  InvalidNote/Expired currently persists nothing (`migration.rs` tags 1|2|3 → `Ok(())`), so
  the "as today" routing to `invalid/` never actually fires from Rust state. New behavior: a
  non-network rejection records the invalidation (same §6.A persistence, reason included) →
  RequiresAttention → `invalid/`. With A in place this should be rare.
- **D. After broadcast + later sync (extended).** `read_reconciled`/`mark_mined` confirm
  mined-ness for `Broadcast`-state transactions today; extended per M6 to probe `Proved`
  transfers' extractable txids so a submit-crash self-heals instead of looping on
  re-submission. A broadcast that never mines (lost race, reorg) surfaces via A or via ZIP 203
  expiry.

## 7. SDK changes

1. `nextDueTransferNative(estimated_tip)` returning tri-state `JniDueTransferResult`:
   `NOTHING_DUE` / `READY(JniPreparedTransfer)` / `AWAITING_PROOF(transferId)` (parity with
   swift #1853). Adds a scanned-tip expiry filter (§4). Adds an `is_terminal` guard (m2 —
   today a stale Lane B run could extract and broadcast a transfer of a Failed migration).
2. `hasOverdueTransfersNative(estimated_tip)` — estimate for due-ness only; gate call sites
   pass −1; expiry sub-checks on scanned tip.
3. Kotlin `ChainTipEstimator` (§4) wired through `OrchardMigrationSdk`.
4. **NEW `rescheduleUnprovenTransferNative`** (B1 — no production reschedule exists; the
   core team's epic #2630 is expected to deliver an engine primitive eventually — when it
   lands this FFI becomes a wrapper over it; #2801, the epic's first PR, covers Lane A
   wake-ups, not transfer rescheduling. The
   Kotlin `rescheduleOverdueTransfer()` is a non-persisting stub with a units bug — epoch
   seconds compared against block heights — and is deleted/replaced as part of this work).
   Modeled on `debugRescheduleTransfersNative`'s `from_parts` rebuild: rewrites
   `scheduled_height` and boundary in persisted state, PCZT bytes and `expiry_height`
   untouched (no re-signing — verified: the ZIP 374 sighash excludes the anchor). Boundary
   rule: draw via the bucket grid against the current scanned tip; **if the candidate set is
   empty** (early transfers funded < 2 bucket intervals ago — routine on testnet) **keep the
   old boundary and move only `scheduled_height`** (M4), respecting
   `earliest_broadcast_height`. Whether a past bucket boundary's checkpoint is reliably
   retained must be covered by tests, not assumed — a live `AnchorNotFound` was observed for a
   past-bucket draw in the debug path (§13).
5. `reconcileInvalidatedTransfersNative` + Kotlin `reconcileInvalidations(): Boolean` (§6.A) —
   includes the **invalidation-reason persistence** (M1) read by `derive_migration_state`.
6. `Synchronizer.syncToTip(timeout)` — `syncBurst` variant terminating on SYNCED.
7. Reconciliation extension for submit-crash (M6, §6.D).
8. `recordTransferResult` InvalidNote/Expired paths persist the invalidation (§6.C).
9. Privacy buffer becomes **network-scaled** (not build-type-scaled): mainnet 10 min, testnet
   3 min (m5 — today a single fixed constant shared by the post-broadcast gate and
   `privacySyncBufferDuration()`; both scale together).
10. Broadcast-in-flight flag ORed into `isSyncBlockedNow()` (§2.B.1); daily `SyncWorker`
    no-op during active migration (§2, foreground section).
11. Unchanged: `finalizeReadyTransfers`, `isSyncBlocked` gate semantics otherwise, Keystone
    signing paths (pre-signed PCZTs flow through the same lanes; shifts don't re-sign).

## 8. App changes

New: `MigrationSyncWorker` + `MigrationSyncScheduler` (Lane A: one chain per wallet,
per-network cadence constants); shared `LastNetworkActivityStore` (wallet-global, stamped by
Lane A, foreground hook, `SyncWorker`); per-transfer consecutive-shift counter store (keyed
account + transfer id, semantics §2.B.4); app-open at-most-one-overdue catch-up in
`CheckMigrationRecoveryUseCase` (§5). Optional/nice-to-have: `BOOT_COMPLETED` receiver
re-arming the due-alarm (m1 — WorkManager survives reboot, `AlarmManager` does not; the
worker lanes themselves need no receiver).

Modified: `MigrationWorker` decision core → tri-state (§2.B; `decideNullResultAction`, the
burst branch, and the run-start finalize are removed); `MigrationSendingVM` (weld removed);
WalletCoordinator foreground hook; commit/terminal/app-open lifecycle wiring — terminal AND
invalidation cancel both lanes (m2).

Removed: Lane B sync-burst path (`isBroadcastableAfterBurst`, `rescheduleDelayAfterSyncBurst`,
the `WAIT_AND_RETRY` burst branch); `SYNC_BURST_TIMEOUT` moves to Lane A; the broken Kotlin
`rescheduleOverdueTransfer()` stub (§7.4).

## 9. UI and state mapping (no new screens)

Router: `CheckMigrationRecoveryUseCase` (priorities unchanged; new catch-up step per §5).
Screens referenced below all exist under `ui/screen/migration/`.

| State / error | Origin | Surface |
|---|---|---|
| Silent shift (≤3×) | Lane B AWAITING_PROOF | None. `progress/` + home banner show updated times (withLiveState / plan write-through). |
| Spent note | Lane A / foreground detection (§6.A) | `notifyMigrationPlanInvalid` → router → `invalid/` (with M1's reason persistence the InvalidTransfer/TransferExpired split becomes real; replan flow exists — Keystone replan = new signing ceremony). |
| Expired transfer | scanned-tip expiry (§4) → invalidation record | `invalid/` (existing copy). |
| 3rd shift, detection clean | Lane B counter | `notifyManualConfirmationRequired` (once) → app open → foreground sync+prove usually self-heals. Note (m6): if background is *available* the router has no due-but-unproven branch — the foreground hook is the healer; a router branch is not added (YAGNI) but the limitation is named. |
| Ready, no background | router `isTransferReadyToSendWithoutBackground` | `transferreview/` — Send now (overdue gate pauses sync automatically) / Reschedule. Existing. |
| Submit rejected | Lane B + §6.C recording (new) | `invalid/` — reachable for real once M1 lands. |
| Tor failure | Lane B / `sending/` | `pendingTorFailure` → `sending/` → `torfailure/` — unchanged. |
| Overdue with proof (app open) | router `hasOverdueTransfers` + §5 catch-up | one offered via `progress/`/`transferreview/`, rest shifted. |
| Complete | engine Complete + dust check | `complete/` + `success/` — unchanged. |

`AttentionReason.SyncRequiredBeforeNext` stays unsurfaced — silent shifts make it unnecessary.

Copy-level changes only: scheduled/progress times presented as estimates ("approximately");
`scheduled/` shows a hint + settings link when `IsBackgroundExecutionAvailableProvider` is
false or notifications are denied at commit time (the `battery/` and `notification/` setup
screens already exist for the grants).

## 10. Permission matrix and platform roles

Android has two independent axes: notifications (`POST_NOTIFICATIONS`, API 33+) and background
execution (a spectrum: Doze/App-Standby throttling defers WorkManager but the **inexact,
permission-free** `setAndAllowWhileIdle` due-alarm still fires; a user-forced "Restricted"
battery state stops WorkManager **and** alarms). Exact alarms are deliberately not used. The
due-alarm is re-armed after reboot by the new `BOOT_COMPLETED` receiver (§8). Android has no
iOS-style system-delivered future local notifications; alarm → receiver → post is the closest
idiom. Server push (FCM) is rejected outright: a server knowing the wallet's transfer times is
exactly the correlation metadata this design eliminates.

| | Notifications ✓ | Notifications ✗ |
|---|---|---|
| **Background ✓** | Happy path: lanes do everything; notifications inform. | Lanes do everything; user learns state from the home banner at open. Nothing requires a notification to progress (§1.5). |
| **Background ✗** | Doze case: due-alarm fires → "ready to send" notification → app open → `transferreview/`. Hard-restricted case behaves like the right cell. | **App-open is the only driver**: reconciliation → foreground sync + prove → §5 catch-up offers one transfer (`transferreview/`/`progress/`), rest shift. Slow but lossless. |

Platform comparison (iOS column is the *expected* sibling design, not implemented here).
**iOS background reality (verified 2026-07-28, web research vs Apple DTS + measured reports):**
there is NO frequency guarantee anywhere in BackgroundTasks; `earliestBeginDate` means only
"not before". For a daily-active user expect a few ~30 s `BGAppRefreshTask` slots (useless for
proving) plus **at most ~1 usable minutes-long `BGProcessingTask` window per day** (overnight,
charging, killed when the user picks up the device). For a rarely-opening user the predictive
scheduler converges to **zero**, and force-quit is an absolute zero until the next manual
launch. iOS design must therefore be **notification-driven** (~2 pre-scheduled local
notifications per transfer: a prove-visit CTA after its boundary settles + a send CTA at S,
adaptively re-armed/cancelled on every app open — one prove visit covers all settled
boundaries, so merges reduce the count), with background windows as an opportunistic bonus
only, and proving checkpointable (interruptible mid-run).

| | Android (this design) | iOS (expected) |
|---|---|---|
| Primary driver | WorkManager lanes A (sync+prove) + B (broadcast) | Scheduled local notifications + user opens; background = best-effort bonus (0–1 usable window/day engaged, 0 disengaged) |
| Future-scheduled notification | Not available; inexact alarm → receiver → post | Native (`UNUserNotificationCenter`), system-delivered at time S without app execution |
| Notification role | Status visual + fallback CTA | Load-bearing: ~2 per transfer (prove visit + send visit), adaptively re-armed on every open |
| Sync ↔ send separation | Window type: a Worker run is sync+prove XOR broadcast | Visit type: prove visits sync; send visits don't (overdue gate blocks sync at open) |
| Invariant | Progress never depends on a notification | Progress depends on notification *or* app open — estimated-height rescheduling matters even more there |
| Last-resort fallback | App-open reconciliation | App-open reconciliation |

## 11. Testnet fast mode

Already-merged pieces: 12-block bucket (15 min) + scaled send delays (#2042) → a full plan
spans ~1–2 h. This design adds: Lane A cadence 5 min on testnet; privacy buffer network-scaled
to 3 min (§7.9) — **including the post-broadcast side** (a 10-min post-buffer would eat the
next window's prove slot on a ~15-min-spaced plan); estimated-tip due-ness so transfers fire
in their own windows. Arithmetic (review-verified): anchor age ≥ 1 bucket means a boundary
settles ≥ ~15 min before its window; a 5-min cadence lands ≥ 1 Lane A run inside
`[due − 12.5 min, due − 3 min]`. The binding constraint is the empty-candidate case at
reschedule time (§7.4's fallback covers it).

## 12. Testing

- **Unit (app)**: Lane B decision table (tri-state × sync-source overlap × quiet-gap ×
  shift-counter incl. reset/once-only-notification semantics); Lane A window skip incl. the
  past-due hot-loop guard; re-arm delay + jitter; app-open catch-up (keep earliest, shift
  rest).
- **Unit (SDK Kotlin)**: `ChainTipEstimator` with injected clock (floor; negative-elapsed
  clamp; max(scanned, estimated)).
- **Rust**: effective-tip used for due-ness only — expiry/rebuild on scanned tip even with a
  huge estimate (M2 regression test); tri-state statuses + terminal guard;
  `rescheduleUnprovenTransfer` (boundary redraw, empty-candidate fallback, expiry untouched,
  PCZT bytes untouched, **past-bucket boundary provable after retention** — the
  `AnchorNotFound` question); invalidation persistence + reason mapping (foreign spend →
  InvalidTransfer; plan-internal spend → not invalid); submit-crash reconciliation (Proved +
  on-chain txid → Broadcast/Mined).
- **Emulator (project practice — testnet/foss/debug)**: full background automatic migration
  (MIGRATION_DIAG: Lane A every 5 min, proofs ahead of windows, broadcasts inside estimated
  windows, no sync within ±buffer of any broadcast from ANY source); cancel Lane A → verify
  silent shifts + 3rd-shift notification; spend a funding note externally → `invalid/`
  routing; kill the process between submit and record → verify self-heal; permission-matrix
  quadrants; reboot → due-alarm re-armed.

## 13. Out of scope / open items

- Kris's ack on estimated-height due-ness (asked in the thread; we proceed with our own
  computation behind `ChainTipEstimator`, mainnet enablement may be feature-flagged).
- **Past-bucket boundary retention vs the live `AnchorNotFound` observation** (§7.4): the
  debug reschedule path deliberately avoids past bucket-grid boundaries because one failed to
  prove live. Whether `anchor_retention_interval` now guarantees those checkpoints must be
  settled by the Rust tests before the boundary-redraw half of `rescheduleUnprovenTransfer`
  is enabled; the M4 fallback (keep old boundary) is the safe default meanwhile.
- iOS implementation (expected shape documented in §10).
- Batch-sending multiple overdue transfers with pauses (engine policy question for core team).
- Track epic librustzcash#2630: #2801 (`sync_wakeup_schedule`) replaces Lane A's fixed
  cadence when exposed through the SDK; a future engine reschedule primitive replaces the
  internals of §7.4.

## 14. Adversarial review changelog (2026-07-27)

Independent review (fresh-context agent, spec + code, no conversation bias) found and this
revision incorporates: **B1** engine reschedule did not exist (now new work, §7.4, with the
Kotlin stub's epoch-seconds-vs-height units bug scheduled for deletion); **B2**
at-most-one-overdue did not exist (now app-side catch-up, §5); **M1** InvalidNote/Expired
recording was a no-op and `RequiresAttention(InvalidTransfer)` unreachable (now §6/§7.5/§7.8);
**M2** estimates could falsely expire transfers (now §1.4/§4 hard rule); **M3** quiet gap
ignored foreground/daily sync (now all-source overlap checks + shared timestamp); **M4** empty
boundary-candidate fallback; **M5** Lane A hot-loop + live data source named; **M6**
submit-crash reconciliation; **M7** shift-counter semantics made implementable; minors: boot
receiver, terminal guard in `nextDueTransfer`, Lane A wallet-global, estimator base pinned to
scanned tip, network-scaled privacy buffer, router limitation named.
