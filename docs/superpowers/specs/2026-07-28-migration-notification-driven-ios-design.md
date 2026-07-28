# Migration notification-driven background execution — iOS design

Date: 2026-07-28
Status: Draft design (sibling of the Android two-lane spec), grounded against the merged Swift
SDK surface as of 2026-07-28; pending iOS-team review and implementation plan. Unknowns are
marked **[VERIFY]** (needs a code/API check) or **[OPEN]** (needs a human decision).

References:
- Android sibling: `2026-07-27-migration-two-lane-android-design.md` (structure mirrored here;
  shared mechanics are cross-referenced, not repeated). Its §10 carries the verified iOS
  background-reality summary this design is built on.
- Kris's verdict (Slack #orchard-ironwood-migration ts 1784910426.257129, 2026-07-25): proving
  belongs to the sync path; "The broadcast step should not sync"; sessions separated; the
  fetch→prove→broadcast weld "is a bug!"; missed windows shift the plan.
- ZIP 318 (https://zips.z.cash/zip-0318): "A single background processing window MUST be used
  either to synchronize (updating the anchors and proofs of upcoming transactions) or to
  broadcast, never both." + the app-open fallback disclosure SHOULD.
- Swift SDK state (checked 2026-07-28):
  [#1813](https://github.com/zcash/zcash-swift-wallet-sdk/pull/1813) **MERGED** 07-27 (FFI,
  welding, models, error codes);
  [#1812](https://github.com/zcash/zcash-swift-wallet-sdk/pull/1812) **MERGED** 07-28
  (Synchronizer migration group — 23 members, dedicated migration Tor, persisted 10-minute
  `MigrationSyncGate`, ZRUST0125/0126 enforcement);
  [#1853](https://github.com/zcash/zcash-swift-wallet-sdk/pull/1853) **OPEN** (prove
  opportunistically: `migrationProvePending`, tri-state `DueMigrationTransfer`,
  `migrationNextDueTransfer` never proves). #1853 was authored to land under #1812; since
  #1812 merged first, #1853 must now absorb the rebase itself — its own "Notes for #1812's
  rebase" section describes exactly the two call-site changes.
- iOS background reality (verified 2026-07-28; full citations in the research report —
  key sources: Apple DTS "iOS Background Execution Limits" forum thread 685525, WWDC20 10063,
  `BGProcessingTask` / `earliestBeginDate` docs, measured reports threads 772426/127500):
  **no frequency guarantee exists anywhere**; `earliestBeginDate` means only "not before";
  `BGAppRefreshTask` gives ~30 s slots (useless for proving); `BGProcessingTask` gives at most
  **~1 usable minutes-long window per day** for a daily-active user (overnight, charging,
  killed the moment the user picks up the device) and **converges to zero** for a
  rarely-opening user; **force-quit = absolute zero** until the next manual launch.
- App state: `zodl-ios` `main` @ `194b89cb` contains **no pool-migration feature code** (the
  only "migration" hits are address-book/metadata migrations) — §8 is greenfield and
  references the Android surfaces as the design contract. **[VERIFY]** whether a feature
  branch already carries UI.

Scope decision: **app + SDK, full** — same as Android. The iOS app-side is new work; the SDK
side builds on the merged #1813/#1812 surface and the open #1853, plus the same
engine-adjacent additions the Android spec defines (reschedule primitive, invalidation
reconciliation, estimated-tip due-ness), implemented in the swift-sdk's Rust layer.

## 1. Goal and principles

Make a scheduled (AUTOMATIC) migration progress on iOS per Kris's model, under the verified
constraint that background execution cannot be load-bearing for anyone:

1. **Notification-driven baseline.** The primary driver is pre-scheduled local notifications
   (`UNUserNotificationCenter` — system-delivered at their fire date with zero app execution)
   that bring the user into the app; the app then performs exactly one kind of work per visit.
2. **ZIP 318 invariants are carried by VISIT TYPES, not worker windows.** A PROVE visit syncs
   and proves and never broadcasts; a SEND visit broadcasts and never syncs. Same MUST, no
   WorkManager.
3. **Background is an opportunistic bonus only.** `BGProcessingTask` is registered and, when
   granted, performs the PROVE-visit body — but nothing is scheduled against its occurrence,
   and no correctness or UX promise depends on it. Proving inside it MUST be checkpointable
   (the task dies when the user picks up the device).
4. **Nothing correctness-critical depends on notifications either** (they can be denied,
   swiped, or silenced): every notification is a shortcut to an app-open flow that works
   identically without it. App-open reconciliation is the universal fallback driver; nothing
   is ever lost (durable anchor retention, 30–60-day expiry, plan shifting).
5. **Missed windows shift the plan** — silently, forward, never touching `expiry_height`
   (Android §1.3 verbatim).
6. **Estimates accelerate due-ness, never expiry or invalidity** (Android §1.4 hard rule,
   M2). On iOS this matters MORE: between rare syncs the scanned tip is stale by hours-to-
   days, so without the estimated tip nothing would ever look due at its send notification.

## 2. Architecture — the notification schedule and two visit types

### 2.1 The notification schedule

Two notification roles per transfer, armed when the plan is committed:

| Role | Fire time | CTA | Visit it opens |
|---|---|---|---|
| **PROVE** ("prepare your next transfer") | random draw within `[B settled + margin, S − privacyBuffer]`, converted to wall clock via the estimated tip (§4) | open the app | PROVE visit |
| **SEND** ("transfer ready to send") | estimated wall-clock time of `S` | open the app | SEND visit |

- **Ceiling: 2 per transfer; merges lower it.** One PROVE visit proves *everything* whose
  boundary has settled, so later transfers' PROVE notifications whose boundary the visit
  already covered are cancelled. 5 transfers ≈ 10 armed notifications at commit, typically
  fewer fire.
- **Adaptive reconciliation on EVERY app open and at the end of every visit** (and on every
  BG bonus window): recompute from live engine state (`migrationTransferStates` equivalent —
  never a UI-side cached plan) which notifications are still needed; cancel satisfied ones
  (transfer proved → its PROVE notification; broadcast → both), re-arm shifted ones (§5),
  re-derive fire times from the current estimated tip. iOS pending-notification limit is 64
  per app — a full plan (≤ ~7 transfers after denomination split, ≤ 14 notifications) fits
  with a wide margin; still cap arming to the next K transfers if plans ever grow.
- The PROVE fire time is a **random draw**, not `B + ε` — this is a privacy property (§3),
  not cosmetics. The draw is per-transfer and re-drawn on re-arm.
- Notification content is generic ("prepare/send" — no amounts, no counts) — lock-screen
  text is world-readable.

### 2.2 PROVE visit (also the body of the BG bonus window)

Trigger: PROVE notification tap, or any organic app open during an active migration (the
foreground hook), or a granted `BGProcessingTask`.

1. Gate check: skip sync if the post-broadcast `MigrationSyncGate` is active (merged #1812
   behavior — `start()` throws `migrationSyncBlocked`/ZRUST0125 while the persisted 10-minute
   broadcast→sync gate is live, aggregated across accounts).
2. Sync to tip (normal synchronizer session; in a BG window: bounded + checkpointable).
3. Prove sweep: `migrationProvePending(for:)` for every account with an active migration
   (#1853 — pending merge; until it lands there is NO standalone prove call on iOS, see §7).
4. Invalidation check: `reconcileInvalidations()` (new, §6). On invalidation → cancel ALL
   migration notifications + pending BG task requests, mark terminal; the app-open router
   takes over.
5. Reconcile the notification schedule (§2.1).

Never broadcasts. `BGAppRefreshTask` (~30 s) runs a micro-variant: advance the tip a few
blocks, stamp state, reconcile notifications — **never proving** (a ZK proof does not fit
30 s on mobile; an interrupted prove wastes the whole slot).

### 2.3 SEND visit

Trigger: SEND notification tap, or the app-open router finding an overdue-with-proof transfer.

1. **Sync must not start.** The visit routes into the send flow before the synchronizer is
   started. The merged SDK enforces the inverse direction only in part: broadcast-during-sync
   throws `migrationBroadcastDuringSync` (ZRUST0126, advisory point-in-time check) and
   post-broadcast→sync is hard-gated; the **overdue→sync-block direction** (Android's
   `isSyncBlockedNow()` overdue clause) must be provided app-side (do not `start()` when a
   send is due) — **[VERIFY]** whether #1812's height-gated members give this for free; if
   not, mirror Android §7.10's gate clause in the Swift layer. Helpfully, migration members
   deliberately **do not require `prepare()`** ("background sessions can broadcast without
   starting sync" — #1812), so a send visit can run with the synchronizer never started.
2. Tri-state (`migrationNextDueTransfer` with the estimated tip, post-#1853):
   - **`.ready`** → submit via the **dedicated migration Tor runtime** (merged: isolated
     circuit per submission, fail-closed `migrationTorUnavailable`, host-required explicit
     `submissionEndpoint`, never the sync server) → record → the persisted 10-minute
     broadcast→sync gate arms itself → reconcile notifications → done. An
     overdue-but-proved transfer is simply `.ready` and self-heals here.
   - **`.awaitingProof(id:)`** → **engine shift** via the NEW reschedule primitive (§5/§7);
     consecutive-shift counter per Android §2.B.4 (including its "count only
     synced-since-last-shift" refinement); re-arm BOTH of the transfer's notifications for
     the shifted times.
   - **`.nothingDue`** → reconcile notifications (the estimate may have drifted), done.
3. Result handling mirrors Android §2.B.3: Tor failure → the iOS equivalent of the
   pendingTorFailure route; non-network rejection → invalidation recording (§6.C);
   submit-crash reconciliation (§6.D) — note this stays consistent with #1812's "no txid
   polling" invariant because the probe reads the **local** wallet DB (mined-ness from block
   scanning), not the network.
4. If the user arrived at a SEND visit with the transfer unproven AND wants to act now:
   the **disclosed Send-now escape hatch** — sync+prove+send in one session behind the
   ZIP 318 sync-correlation disclosure sheet. Never the default; the default is the silent
   shift.

### 2.4 What there is deliberately none of

No periodic lane, no due-alarm (doesn't exist on iOS), no silent push (server-knows-schedule
is exactly the correlation metadata this design eliminates — same verdict as Android's FCM
rejection), no reliance on `BGProcessingTask` cadence.

## 3. Privacy analysis — honest about the correlation

This design is the **plan-derived-sync variant**: PROVE visits happen at times ultimately
derived from the plan's boundaries. That is strictly weaker than Android's unconditional
hourly cadence, and it is accepted only because iOS offers no unconditional channel.

Mitigations, in decreasing strength:

1. **Random draw of the PROVE fire time** across the whole `[B settled, S − buffer]` window
   (hours wide) — the observer learns "synced sometime in a multi-hour window", consistent
   with most wallets.
2. **Human tap jitter** — sync starts when the user taps, minutes-to-hours after the fire.
3. **Merges** — fewer sync events than transfers; the sync-count ↔ transfer-count linkage
   weakens.
4. **Organic opens** — every ordinary app open during the migration is a plan-independent
   cover sync.

**Residual leak (named, not solved): conditionality.** A wallet that otherwise never syncs
and suddenly syncs ~5× over two days, then goes quiet, is identifiable *as migrating around
then* even with perfectly random times. Android's periodic cadence has no such leak
(unconditional). On iOS the leak shrinks with user engagement (organic opens dominate) and
grows for dormant users.

**[OPEN] The plan-independent alternative:** replace per-transfer PROVE notifications with
1–2 daily "wallet check-in" notifications at user-habitual/random times, fully decoupled
from B; a check-in visit proves whatever has settled. Timing then carries zero plan
information (the human is the scheduler, like WorkManager is on Android). Cost: visits that
find nothing to prove (notification fatigue) and more shifts when a window is missed.
Raise with core team / iOS team alongside the estimated-height ack; the two models can also
be mixed (check-ins while >1 transfer pending, targeted PROVE only for the last one).

Tor note: migration **submissions** are already forced through the dedicated migration Tor
runtime (merged #1812), so the correlation surface discussed here is sync traffic only.
Syncing over Tor would blunt the IP half of it — out of scope, tracked with the ZIP 318
app-gaps list.

## 4. Estimated chain tip

Same mechanism, rule and rationale as Android §4 — sibling implementation:

```
estimatedTip = maxScannedBlockHeight + floor((now − headerTime(maxScannedBlock)) / 75 s)
```

- Swift `ChainTipEstimator` equivalent in the SDK's Swift layer, reading the max **scanned**
  block + its header timestamp; floor; clamp negative elapsed to 0; seam for a future
  core-provided value.
- Passed into the FFI as `estimated_tip` (−1 = disabled); Rust uses
  `effective_tip = max(scanned, estimated)` **only** for `scheduled_height` due-ness.
  **`is_expired`, rebuild eligibility and invalidation always evaluate on the scanned tip**
  (M2). The `MigrationSyncGate` stays on its own persisted wall-clock logic (it already is).
- iOS-specific weight: (a) **notification fire times** are wall-clock conversions of heights
  — computed via the estimator at arm time and refreshed at every reconciliation; (b) at a
  SEND tap the scanned tip may be days old — without the estimate the engine would report
  `.nothingDue` for a transfer whose notification just correctly fired. The estimate is what
  makes the notification and the engine agree.

Worst case of a bad estimate: a mistimed submit the node rejects (→ recovery) or a late
broadcast. Never expiry, never invalidation (§1.6).

## 5. Shift semantics

Same two kinds as Android §5, with the notification schedule as an extra write target:

| Kind | Trigger | Who moves what |
|---|---|---|
| **Engine shift** | `.awaitingProof` at a SEND visit (missed PROVE window) | NEW reschedule primitive rewrites `scheduled_height` (+ boundary where possible) in persisted engine state; **both** of the transfer's notifications are re-armed for the shifted times. Silent (one-time notification only on the 3rd counted shift, Android §2.B.4 semantics incl. the synced-since-last-shift counting rule). |
| **Local delay** | `.ready` but a sync session is somehow live | Finish/stop the session, retry the send flow minutes later; engine untouched. Always silent. |

**App-open catch-up (sibling of Android §5/B2):** the engine has no at-most-one-overdue
policy; the iOS app-open reconciliation implements keep-earliest-shift-rest with the same
reschedule primitive, then routes to the send flow for the one kept transfer. Without it a
long-closed wallet would drip its backlog back-to-back — the correlated burst the schedule
exists to prevent.

## 6. Invalidation detection

Defense-in-depth points A–D exactly as Android §6 (nullifier reconciliation during sync as
the primary; prove-time is unreliable; submit-time recording; post-broadcast reconciliation).
iOS deltas only:

- §6.A runs in every PROVE visit and every organic-open foreground hook (there is no Lane A);
  the new FFI + reason persistence is shared Rust work with the Android spec (§7).
- **Invalidation is migration-terminal** and must cancel **all pending migration
  notifications and BG task requests** (the iOS equivalent of "cancel both lanes") — a stale
  SEND notification firing after termination would be a lie on the lock screen.
- §6.D submit-crash probe: local-DB mined-ness check, consistent with the "no txid polling"
  invariant (see §2.3.3).

## 7. SDK changes (Swift)

**Already exists (merged #1813 + #1812)** — build on, don't re-create:

1. The `Synchronizer` migration group (23 members): state/progress, note split, gradual +
   immediate proposal, sign-and-store, height-gated `executeNextPendingMigrationTransfer`,
   reschedule **readback**, overdue/invalid detection + restart/refresh recovery, dust
   opt-in, full external-signer (PCZT/Keystone) path, wallet-scope privacy gate
   (`isMigrationSyncBlocked()` / `migrationSyncBlockedStream` /
   `migrationPrivacySyncBufferDuration`), per-account engines with concurrent migrations.
2. The dedicated migration Tor runtime (fail-closed, isolated circuits, required explicit
   `submissionEndpoint`, sync server never used for submission), the persisted 10-minute
   broadcast→sync `MigrationSyncGate` (ZRUST0125) and the advisory broadcast-during-sync
   error (ZRUST0126), `prepare()`-free migration members, `ironwoodActivationHeight`.

**Pending in the open #1853** — this design assumes it lands (rebased onto the merged #1812):

3. `migrationProvePending(for:)` (the prove sweep — without it iOS has no standalone prove
   call at all) and tri-state `DueMigrationTransfer` with a never-proving
   `migrationNextDueTransfer`. The merged `executeNextPendingMigrationTransfer` is the
   fetch→prove→extract→broadcast→record weld Kris called "a bug!" — per #1853's own rebase
   notes it becomes: match on `.ready` (broadcast path), `.nothingDue` (old else path),
   `.awaitingProof` (run the sweep *in a PROVE visit*, here: shift instead).

**New work (siblings of Android §7 items, shared Rust where possible):**

4. Estimated-tip parameter on due-ness surfaces (`migrationNextDueTransfer`,
   `hasOverdueMigrationTransfers` equivalent) — due-ness only, scanned-tip expiry filter,
   terminal guard (Android §7.1–7.2). Swift `ChainTipEstimator` (§4).
5. Reschedule-unproven primitive (Android §7.4 — including the empty-boundary-candidate
   fallback M4 and the past-bucket retention question; one Rust implementation, two FFI
   skins).
6. `reconcileInvalidations()` + invalidation-reason persistence (Android §7.5/M1) read by
   the state derivation so the InvalidTransfer/TransferExpired split is real.
7. Submit-crash reconciliation via local mined-ness probe (Android §7.7/M6).
8. Overdue→sync-block direction for SEND visits if #1812's surface doesn't already provide
   it **[VERIFY]** (Android §7.10 sibling).
9. Privacy buffer network-scaled (mainnet 10 min / testnet 3 min — Android §7.9); the
   persisted gate constant follows the same scaling.
10. **Parity alignment (from the 07-25 thread mapping):** transient prove-error sets differ
    (Android: `UnknownSpentNote | AnchorNotFound | WitnessNotFound`; Swift: `AnchorNotFound |
    WitnessNotFound | ChainTipUnknown | IronwoodTreeUnavailable`) and Swift carries a stable
    `MIGRATION_PROVING_UNAVAILABLE` → ZRUST0127 code while Android returns anyhow strings.
    Align the transient sets (union is probably right — **[OPEN]** with core) and add the
    error-code mapping on Android rather than dropping it on iOS.

## 8. App changes (zodl-ios) — greenfield

`zodl-ios` `main` has no pool-migration feature code today; this section is the design
contract. Surfaces are named as "iOS equivalents of" the Android §9 screens (`invalid/`,
`transferreview/`, `progress/`, `sending/`, `torfailure/`, `complete/`, home banner).

New modules:

- **MigrationNotificationScheduler** — owns the `UNUserNotificationCenter` schedule: arm at
  commit, the §2.1 reconciliation (called from `applicationDidBecomeActive`, visit ends, BG
  windows), fire-time computation via the estimator, PROVE-time random draw, cancel-all on
  terminal/invalidations. Unit-testable pure core (given engine states + estimator → the
  exact pending-notification set).
- **Visit router** — notification tap → PROVE flow or SEND flow (deep link / launch options);
  organic opens route through the app-open reconciliation (catch-up §5, then the same two
  flows). The SEND flow must be reachable **without starting the synchronizer** (§2.3.1).
- **BG bonus lane** — `BGProcessingTask` registration + handler running the §2.2 body with
  checkpointable proving; `BGAppRefreshTask` micro-sync. Both optional-by-design.
- **Consecutive-shift counter store** (account + transfer id, Android §2.B.4 semantics) and
  the shared last-network-activity stamp if the overlap checks need it (**[VERIFY]** how much
  #1812's gate already covers on-visit overlap).

Permission matrix (two axes — notifications × background bonus):

| | Notifications ✓ | Notifications ✗ |
|---|---|---|
| **BG bonus ✓** | Happy path: notifications drive visits; BG windows pre-prove and cancel PROVE notifications. | BG windows keep proofs fresh silently; progress needs organic opens for sends; home-banner state at open. |
| **BG bonus ✗** | Baseline design case: everything through notification-driven visits. | **App-open is the only driver**: reconciliation → PROVE work → catch-up offers one send, rest shift. Slow but lossless. |

Commit-time UX: if notifications are denied at commit, say so (the migration will only
progress on app opens) and deep-link to settings — sibling of Android's `scheduled/` hint.
Scheduled times everywhere presented as estimates ("approximately").

## 9. Testnet, testing, open items

**Testnet fast mode:** 12-block buckets (15 min) compress the PROVE window to
`[due − ~12.5 min, due − 3 min]` — too tight for human-tap PROVE visits. On testnet the
notification model is exercised with collapsed timing expectations (a PROVE and SEND CTA
minutes apart, or a single visit doing prove then — after the 3-min buffer — send via the
disclosed path); the realistic end-to-end rehearsal of the *notification* choreography needs
mainnet-like spacing. Emulator/simulator scenario tests matter more than wall-clock realism
here.

**Testing:**
- Unit: notification-set reconciliation (arm/cancel/re-arm/merge math against synthetic
  engine states — including "cancel everything on terminal"); estimator (floor,
  negative-elapsed clamp, max(scanned, estimated)); SEND-visit decision table (tri-state ×
  gate states × shift counter); catch-up keep-earliest-shift-rest.
- Rust: shared with the Android spec §12 (M2 regression, tri-state + terminal guard,
  reschedule primitive incl. past-bucket retention, invalidation reasons, submit-crash).
- Scenario/device: the six scenarios of the explainer's iOS tab — all-success with a merge
  cancel; missed PROVE → shift + re-arm; ignored SEND → catch-up; disclosed Send-now
  (buffer violation is user-chosen and disclosed); notifications denied; dormant → expiry.
  Plus: force-quit (everything survives, next launch reconciles), BG window granted mid-plan
  (PROVE notifications get cancelled), notification tap after invalidation (routes to
  `invalid/`-equivalent, no send offered).

**Open items:**
- Kris's ack on estimated-height due-ness (shared with Android §13; feature-flag mainnet).
- **[OPEN]** Plan-independent check-in notifications vs plan-derived PROVE notifications
  (§3) — privacy/product tradeoff, raise with core team.
- Correlation acceptance: explicit sign-off that the plan-derived variant with mitigations
  is acceptable on iOS given the platform constraint (§3's honesty section).
- #1853 landing + rebase onto merged #1812 (this design assumes its API).
- **[VERIFY]** overdue→sync-block direction in the merged surface (§7.8); how the height-
  gated members interact with an estimated tip; whether a zodl-ios feature branch already
  has UI.
- Keystone replan UX on invalidation (new signing ceremony — shared with Android).
- Future: iOS 26 `BGContinuedProcessingTask` when the deployment floor rises (user-visible
  long session — would upgrade the BG bonus lane materially; Vizor's approach).
