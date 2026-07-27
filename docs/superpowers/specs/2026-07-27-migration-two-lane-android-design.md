# Migration two-lane background execution — Android design

Date: 2026-07-27
Status: Approved design (brainstormed with Dominik), pending implementation plan.

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

Scope decision: **app + SDK, full** — including SDK due-ness semantics. Estimated-height
due-ness is computed by us now (Kotlin layer), designed to be swapped for a core-provided value
when librustzcash exposes one.

## 1. Goal and principles

Make a scheduled (AUTOMATIC) migration progress in the background per Kris's model, best-effort:

1. **Sync+prove and broadcast never share a window** (ZIP 318 MUST). Lane B performs no network
   activity other than the submit itself.
2. **Proving is opportunistic** — it happens whenever a sync runs (background Lane A or
   foreground), never at broadcast time.
3. **Missed windows shift the plan** — a transfer that cannot proceed is silently rescheduled
   into the future by the engine (Kris: the whole plan shifts). No sync bursts from the
   broadcast lane; the burst-in-`WAIT_AND_RETRY` mechanism is removed.
4. **Notifications are never load-bearing** — every notification is a shortcut to an app-open
   flow that works identically without it.
5. **App-open is the universal fallback driver** — with no background and no notifications the
   migration still progresses (slowly) on app opens; nothing is ever lost (durable anchor
   retention, 30–60-day ZIP 203 expiry, engine reschedule).

## 2. Architecture — two lanes

Both lanes are WorkManager `OneTimeWorkRequest`s that re-arm themselves ("each one sets up the
next"). No `PeriodicWorkRequest` — its 15-minute minimum equals the testnet bucket interval,
and self-chaining gives per-run jitter control. Both lanes are active only while a migration is
in progress (scheduled at commit, cancelled at terminal state, reconciled at app open).

### Lane A — `MigrationSyncWorker` (new): sync + prove, never broadcast

Fixed cadence, independent of the plan: mainnet 60 min ± jitter, testnet 5 min. Run body:

1. **Window check**: if `now ≥ nextEstimatedDue − privacyBuffer` → skip this run, re-arm for
   `nextEstimatedDue + privacyBuffer`. Checked at execution time, so a system-delayed run that
   lands inside a broadcast window still steps aside.
2. **Gate check**: if `isSyncBlocked()` (transfer overdue by scanned tip, or post-broadcast
   buffer active) → skip, re-arm.
3. **Sync to tip**: `syncToTip(timeout)` — thin wrapper over the existing `syncBurst`
   machinery terminating on `SYNCED` (no height-gate predicate).
4. **Prove sweep**: `finalizeReadyTransfers()` (idempotent, skip-not-fatal — unchanged).
5. **Invalidation check**: `reconcileInvalidations()` (new, §6). If the plan became invalid →
   `notifyMigrationPlanInvalid` (if permitted) and stop re-arming; the app-open router takes
   over.
6. Record run-end timestamp in `LastMigrationSyncStore` (feeds Lane B's quiet gap), re-arm.

### Lane B — `MigrationWorker` (slimmed): exclusively broadcast

Per-transfer scheduling via `MigrationScheduler` as today. Run body — the current
`finalizeReadyTransfers()` call and the whole sync-burst `WAIT_AND_RETRY` branch are removed:

1. **Overlap check**: if Lane A is RUNNING (`WorkManager.getWorkInfosForUniqueWork`) or
   `now − lastLaneASyncEnd < privacyBuffer` → local delay to `lastSyncEnd + privacyBuffer`
   (WorkManager only; the engine schedule is untouched — proof and schedule are fine, this is
   window hygiene).
2. `nextDueTransfer(estimatedTip)` → tri-state:
   - **READY** → extract → submit (Tor per flow-scoped flag) → record → post-broadcast privacy
     buffer (existing) → schedule next per plan. An overdue-but-proved transfer is simply READY
     and self-heals here.
   - **AWAITING_PROOF** → **engine shift**: reuse the existing reschedule path (redraws the
     anchor boundary on the network's bucket grid). Increment the per-transfer consecutive-shift
     counter; a successful later prove resets it. On the **3rd consecutive shift** run
     `reconcileInvalidations()`; if clean → `notifyManualConfirmationRequired` (something is
     systematically wrong or the OS is hostile) instead of a 4th silent shift. Schedule next.
   - **NOTHING_DUE** → schedule for the next plan time.
3. Result handling (Success/NetworkError/InvalidNote/Expired/Tor) unchanged from today,
   including plan-repo write-through and notifications.

### Foreground equivalent of Lane A

During an active migration, when the foreground synchronizer reaches `SYNCED`, a
WalletCoordinator-level observer calls `finalizeReadyTransfers()` + `reconcileInvalidations()`.
`MigrationSendingVM`'s `finalize + execute` weld is removed; the VM uses the same tri-state
call as Lane B.

## 3. Window hygiene without a lock

No shared mutex. Two cheap, complementary checks (discussed and chosen over a lock object):

- **Lane A schedule awareness** (§2.A.1): skips runs that would execute inside
  `[due − buffer, due + buffer]`.
- **Lane B runtime checks** (§2.B.1): Lane A running-state query + quiet gap from
  `LastMigrationSyncStore`.

Residual risk: WorkManager offers no timing guarantees, which is exactly why both checks run at
execution time, not scheduling time. Combined with the existing post-broadcast buffer this
yields ≥ privacyBuffer of network silence on **both** sides of every broadcast.

## 4. Estimated chain tip

Broadcast due-ness must not depend on a fresh scan (that is what welded sync to broadcast).
The wallet derives the current height from its own data:

```
estimatedTip = scannedTip + floor((now − blockTime(scannedTip)) / 75 s)
```

- Computed in the SDK Kotlin layer (`ChainTipEstimator` interface) from the scanned tip's block
  header timestamp — no extra persistence. Conservative: floor.
- Passed to JNI as `estimated_tip: jlong` (−1 = disabled). Rust uses
  `effective_tip = max(scanned_tip, estimated_tip)` — an estimate can never regress below scan.
- **The sync-blocking gate stays on the scanned tip**: `isSyncBlockedNow()` call sites pass −1.
  A manipulated device clock must be able to cause at most a mistimed submit (node rejects →
  existing recovery), never a blocked sync.
- `ChainTipEstimator` is the seam for the future core-provided value: when librustzcash returns
  an estimated/target height itself, the Kotlin computation is replaced behind the same
  interface.

Worst-case failure of the estimate is a submit the node rejects or a slightly early/late
broadcast — both land in existing recovery paths. No fund-safety impact (proofs, deps, expiry
are all satisfied by construction before READY is possible).

## 5. Shift semantics — two distinct kinds

| Kind | Trigger | Who moves what |
|---|---|---|
| **Engine shift** | AWAITING_PROOF (missed Lane A) | Rust reschedules the transfer: new scheduled height + fresh boundary from the bucket grid. Plan repo write-through updates banner/Progress times. Silent (up to 3×). |
| **Local delay** | READY but Lane A running / quiet gap unmet | WorkManager delay by minutes; engine untouched. Always silent. |

With no background at all, neither lane runs; accumulated overdue transfers are handled at app
open by the engine's existing **at-most-one-overdue** policy (one offered to the user, the rest
reshuffled into the future).

## 6. Invalidation detection (defense in depth)

When can "a funding note is already spent elsewhere" be known? Four points, ordered:

- **A. During sync (new, primary).** The scan sees the note's nullifier in a foreign
  transaction — certain knowledge, potentially days before the send window. New Rust
  `reconcileInvalidatedTransfersNative`: for each pending (Signed/Proved) transfer, check its
  funding note against the wallet DB's spent set; spent outside the plan → engine
  `Failed`/`RequiresAttention(InvalidTransfer)`. Called from Lane A (§2.A.5) and the foreground
  hook. This is what enables proposing a new plan early.
- **B. At prove time (unreliable, unchanged).** The prover cannot distinguish "spent" from
  "not yet scanned" (`UnknownSpentNote` stays in the transient set). Not used for detection;
  the 3×-shift bound (§2.B.2) is the backstop that converts a persistent prove failure into a
  detection-A run + escalation.
- **C. At submit (existing, last line).** Node rejects the double-spend → `InvalidNote` →
  RequiresAttention, as today. With A in place this should become rare.
- **D. After broadcast + later sync (existing).** `read_reconciled`/`mark_mined` confirm
  mined-ness. A broadcast that never mines (lost race, reorg) surfaces via A (note spent by the
  winning tx) or via ZIP 203 expiry → RequiresAttention. Deliberately no new machinery (YAGNI —
  the race is only against the user's own other devices).

## 7. SDK changes

1. `nextDueTransferNative(estimated_tip)` returning tri-state `JniDueTransferResult`:
   `NOTHING_DUE` / `READY(JniPreparedTransfer)` / `AWAITING_PROOF(transferId)` (parity with
   swift #1853's `DueMigrationTransfer`).
2. `hasOverdueTransfersNative(estimated_tip)` — same parameter; gate call sites pass −1.
3. Kotlin `ChainTipEstimator` (§4) wired through `OrchardMigrationSdk`.
4. Reschedule-unproven: expose the existing reschedule path (bucket-grid boundary redraw,
   mainnet 144 / testnet 12) for a due-but-unproven transfer.
5. `reconcileInvalidatedTransfersNative` + Kotlin `reconcileInvalidations(): Boolean` (§6.A).
6. `Synchronizer.syncToTip(timeout)` — `syncBurst` variant terminating on SYNCED.
7. Unchanged: `finalizeReadyTransfers`, `isSyncBlocked` semantics, post-broadcast buffer,
   Keystone signing paths (pre-signed PCZTs flow through the same lanes).

## 8. App changes

New: `MigrationSyncWorker` + `MigrationSyncScheduler` (Lane A; per-network cadence constants);
`LastMigrationSyncStore` (run-end timestamp); per-transfer consecutive-shift counter store.

Modified: `MigrationWorker` decision core → tri-state (§2.B; `decideNullResultAction`, the
burst branch, and the run-start finalize are removed); `MigrationSendingVM` (weld removed);
WalletCoordinator foreground hook; commit/terminal/app-open lifecycle wiring for Lane A.

Removed: Lane B sync-burst path (`isBroadcastableAfterBurst`, `rescheduleDelayAfterSyncBurst`
and the `WAIT_AND_RETRY` burst branch); `SYNC_BURST_TIMEOUT` moves to Lane A.

## 9. UI and state mapping (no new screens)

Router: `CheckMigrationRecoveryUseCase` (priorities unchanged). Screens referenced below all
exist under `ui/screen/migration/`.

| State / error | Origin | Surface |
|---|---|---|
| Silent shift (≤3×) | Lane B AWAITING_PROOF | None. `progress/` + home banner show updated times (withLiveState / plan write-through). |
| Spent note | Lane A / foreground detection (§6.A) | `notifyMigrationPlanInvalid` → router → `invalid/` (screen already distinguishes InvalidTransfer vs TransferExpired; replan flow exists). |
| Expired transfer | engine RequiresAttention(TransferExpired) | `invalid/` (existing copy). |
| 3rd shift, detection clean | Lane B counter | `notifyManualConfirmationRequired` → app open → foreground sync+prove usually self-heals → else `transferreview/`. |
| Ready, no background | router `isTransferReadyToSendWithoutBackground` | `transferreview/` — Send now (overdue gate pauses sync automatically) / Reschedule. Existing. |
| Submit rejected (InvalidNote) | Lane B | `invalid/` as today. |
| Tor failure | Lane B / `sending/` | `pendingTorFailure` → `sending/` → `torfailure/` — unchanged. |
| Overdue with proof (app open) | router `hasOverdueTransfers` | `progress/` (Resume Migration) — unchanged. |
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
battery state stops WorkManager **and** alarms). Exact alarms are deliberately not used — they
would require `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`; the existing inexact
`MigrationDueAlarmScheduler` needs no permission and stays as-is. Android has no iOS-style
system-delivered future local notifications; alarm → receiver → post is the closest idiom.
Server push (FCM) is rejected outright: a server knowing the wallet's transfer times is exactly
the correlation metadata this design eliminates.

| | Notifications ✓ | Notifications ✗ |
|---|---|---|
| **Background ✓** | Happy path: lanes do everything; notifications inform. | Lanes do everything; user learns state from the home banner at open. Nothing requires a notification to progress (§1.4). |
| **Background ✗** | Doze case: due-alarm fires → "ready to send" notification → app open → `transferreview/`. Hard-restricted case behaves like the right cell. | **App-open is the only driver**: reconciliation → foreground sync + prove → at-most-one-overdue offers one transfer (`transferreview/`/`progress/`), rest shift. Slow but lossless. |

Platform comparison (iOS column is the *expected* sibling design, not implemented here):

| | Android (this design) | iOS (expected) |
|---|---|---|
| Primary driver | WorkManager lanes A (sync+prove) + B (broadcast) | BGProcessingTask (1–2×/day sync+prove); background broadcast rarely possible |
| Future-scheduled notification | Not available; inexact alarm → receiver → post | Native (`UNUserNotificationCenter`), system-delivered at time S without app execution |
| Notification role | Status visual + fallback CTA | Primary broadcast channel: notification at S → tap → open → send/reschedule |
| Invariant | Progress never depends on a notification | Progress depends on notification *or* app open — estimated-height rescheduling matters even more there |
| Last-resort fallback | App-open reconciliation | App-open reconciliation |

## 11. Testnet fast mode

Already-merged pieces: 12-block bucket (15 min) + scaled send delays (#2042) → a full plan
spans ~1–2 h. This design adds: Lane A cadence 5 min on testnet (self-chaining permits < 15
min), privacy buffer scaled to ~3 min on testnet (constant alongside the existing #2042
scaling), estimated-tip due-ness so transfers fire in their own windows without waiting for
the next sync. Result: a complete automatic background migration observable on an emulator
within a couple of hours, with the same code paths as mainnet.

## 12. Testing

- **Unit (app)**: Lane B decision table (tri-state × quiet-gap × LaneA-running ×
  shift-counter); Lane A pre-due-window skip; re-arm delay + jitter computation.
- **Unit (SDK Kotlin)**: `ChainTipEstimator` with injected clock (floor; max(scanned,
  estimated)).
- **Rust**: `estimated_tip` effective-tip semantics; tri-state statuses; spent-check
  reconciliation (foreign spend → Failed; plan-internal spend → not); reschedule-unproven with
  testnet bucket.
- **Emulator (project practice — testnet/foss/debug)**: full background automatic migration
  (MIGRATION_DIAG: Lane A every 5 min, proofs ahead of windows, broadcasts inside estimated
  windows, no sync within ±buffer of any broadcast); cancel Lane A → verify silent shifts;
  spend a funding note externally → verify `invalid/` routing; permission-matrix quadrants.

## 13. Out of scope / open items

- Kris's ack on estimated-height due-ness (asked in the thread; we proceed with our own
  computation behind `ChainTipEstimator` and swap when core provides a value).
- Mainnet enablement of estimated-tip due-ness may be feature-flagged pending that ack;
  testnet/debug uses it from day one.
- iOS implementation (expected shape documented in §10 for cross-team alignment).
- Batch-sending multiple overdue transfers with pauses (engine policy question for core team).
