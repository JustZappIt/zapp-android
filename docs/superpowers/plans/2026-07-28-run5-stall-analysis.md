# Run #5 stall analysis (2026-07-28, 18:21–18:47)

Read-only diagnosis of why run #5 (9 transfers, committed 18:21:09) showed `proved=5` at 18:23
but no send until 18:44:41, plus an audit of the remaining path to `migration complete`.

## 1. Current state (as of 18:47)

- App process alive (pid 29027, the post-collision-fix build installed ~18:42).
- Wallet DB (`orchard_ironwood_migration_transactions`):

  | crossing | state | scheduled | boundary |
  |---|---|---|---|
  | 1 | **broadcast** (send #1, txId `dfdae74d…`, 18:44:41) | 4212879 | 4212864 |
  | 0, 2, 3, 4, 5, 6, 8 | **proved** | 4212882–4212911 | 4212864 / 4212876 |
  | 7 | signed (boundary not yet settled — provable at 4212926, tip ~4212899 at 18:44) | 4212945 | 4212924 |

- 18:46:58: Lane B Success branch completed (`next transfer scheduled in 0s`), Lane A correctly
  `SKIP_GATE_BLOCKED` (post-broadcast privacy gate), Lane B's immediate re-run correctly
  `DEFER_OVERLAP` (quiet gap unmet — the foreground hook stamped network activity at 18:46:58).
  **Send #2 expected ~18:50.** The run is MOVING; migration status `in_progress`.

## 2. Why nothing was sent 18:23 → 18:43 (two stacked causes, both now addressed)

Timeline (all from `/tmp/e2e_run4.log`):

1. `18:22:09` Lane A run start (RUN) → `syncToTip` instantly SYNCED → `proved=5` at `18:23:19`
   (proving ≈70 s) → **but the run only re-armed at `18:26:36`**. The tail of the run
   (`reconcileInvalidations` + post-run states/wake computation) serialized on
   `MIGRATION_DB_ACCESS_MUTEX` against the foreground app's own SDK traffic (the app had been
   foregrounded for the E2E commit; the hook's finalize/reconcile and the Progress screen's polls
   hold the same mutex). So Lane A's WorkManager unique work was legitimately RUNNING for ~4.5 min.
2. Lane B's wake at `18:24:33` therefore saw `laneARunning=true` → deferred a full privacy buffer
   (3 min) and re-armed +3 min.
3. From then on the two lanes were in **deterministic lockstep**: Lane A's `SKIP_NEAR_DUE`
   re-arm ("wait out the window" = due+buffer−now ≈ 3 min, with the due estimate clamped to
   "now" for overdue proved transfers, so the window never closes) and Lane B's defer are the
   SAME ~3-minute delay, and WorkManager **batches similarly-due jobs into the same wake** —
   observed pairs: 18:31:41.273/.527, 18:36:56.455/.720, 18:40:44.486/.689. Every cycle Lane B
   checked `laneARunning` a few hundred ms after WM marked Lane A RUNNING → defer → identical
   re-arm → re-collision, forever. Five proved, due transfers sat for 20 minutes.
4. The fix installed at ~18:42 (Lane B polls Lane A out for up to 30 s instead of deferring a
   full buffer; bug #6) broke the lockstep on the first post-fix wake: `18:43:44
   preflight=BROADCAST (laneARunning=false)` → **send #1 at 18:44:41**.

Secondary observation (not a blocker, but a stall-amplifier while the app is FOREGROUND): the
foreground SYNCED hook's `finalizeReadyTransfers` (3 × ~10–20 s proving) + `reconcileInvalidations`
(mined-height probes over 8 PCZTs) held the shared mutex `18:43:54 → 18:46:58`, which is why the
Success branch's plan write-through and "next transfer scheduled" log appeared ~2 min after the
send. With the app closed (the actual test scenario) this contention does not exist.

## 3. Remaining-path audit (to `migration complete`)

Checked against current code (MigrationWorker/MigrationSyncWorker/OnMigrationSyncCompleted +
migration.rs). No further hard blockers found; expected dynamics:

- **Sends #2–#8** (all proved): Success → re-arm `nextDelay(plan)`=0 s (overdue) → immediate
  Lane B run → `DEFER_OVERLAP` only while the 3-min quiet gap since the last STAMPED activity is
  unmet → effectively one send ≈ every 3 min. Lane A between sends: `SKIP_GATE_BLOCKED`
  (post-broadcast gate) — correct, and it keeps re-arming (3 min), so it is not lost.
- **tx7 (boundary 4212924)**: needs scanned tip ≥ 4212926 + one Lane A RUN (or Lane B's
  AwaitingProof sync-fallback, which respects the gate and floors at 60 s). Converges; the
  empty-gap checkpoint backfill covers a skipped grid height at 4212924.
- **Completion**: after the 9th Success, `updatedPlan.nextPending == null` → "migration
  complete!" + notification. Engine `Complete` additionally needs all txs MINED — that happens
  via `read_reconciled`'s `mark_mined` on later Lane A/hook reads once the chain mines them; Lane
  A then stops itself on the terminal check (its cadence fallback keeps it alive until then even
  with nothing to prove). One-time Complete screen routes via recovery on next app open
  (orchard balance must be ≤ dust — true once all 9 broadcast).
- **Known cosmetic risks** (log-visible, non-blocking): long mutex holds if the app is
  foregrounded mid-run (see §2); `MigrationTransferDueReceiver` no-ops (bg execution available).

## 4. Fix plan

**No new code changes are required to complete run #5.** Bug #6's fix is live and verified by
send #1. Operational instructions:

1. **Do NOT reinstall or reset — run #5 is healthy.** Keep the app BACKGROUNDED (HOME, not
   swipe-away is fine either way — WorkManager persists) and let it run ~30–45 min.
2. Watch: `grep MIGRATION_DIAG` on the live logcat; expected lines, in order:
   `LaneB … preflight=BROADCAST` → `transfer sent — txId=…` (≈ every 3 min, 8 more times);
   around tx7: either `LaneA … proved=1` or `LaneB: awaiting proof … (count=…)` followed by its
   sync-fallback and a later send; finally `MigrationWorker: migration complete!` and, on the
   next Lane A wake, `LaneA: migration terminal — stopping Lane A.`
3. Failure triggers that WOULD need action (none expected): `unrecoverable (non-empty gap)` for
   boundary 4212924; `Action Required` escalation; any `FATAL EXCEPTION`/`Fatal signal`;
   Lane B logging `DEFER_OVERLAP` with `laneARunning=true` repeatedly again (would mean a real
   >30 s Lane A run overlapping every wake — mutex contention; only plausible if the app is
   kept foregrounded).
4. **Optional hardening for later (not tonight):** (a) move `reconcileInvalidations`'s network
   mined-height probes outside `MIGRATION_DB_ACCESS_MUTEX` or bound them with a timeout so a
   foregrounded app cannot pin the mutex for minutes; (b) add ±20 s jitter to Lane B's re-arm
   to de-batch WM wakes structurally (the 30 s wait-out already handles the common case);
   (c) report to slipstream-core: sub-batch cuts should land on the anchor-retention grid
   (root cause of bug #3; the backfill is an app-layer workaround).

Verification of full success (§3 of the canonical two-lane spec): 9 × `transfer sent`,
`migration complete!`, Lane A terminal stop, Complete screen once on next open, Orchard balance
≈ 0 / Ironwood credited.
