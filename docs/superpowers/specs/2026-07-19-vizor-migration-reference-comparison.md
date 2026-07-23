# Vizor PR #73 vs. our Orchard→Ironwood migration: a code-grounded comparison

## Status: supersedes `2026-07-18-vizor-architecture-comparison.md`

That prior document searched `chainapsis/vizor-wallet` — an unrelated fork with **no migration
code at all** — and concluded "Vizor has no migration feature." That conclusion is wrong. The
correct reference implementation lives at `valargroup/vizor-wallet`, PR #73, and was pointed to
explicitly in Slack:

> **#ext-zodl-valargroup, Adam, 2026-06-17 18:07 CEST:** "We have a working integration in Vizor,
> which is messy but can be used as reference for other implementors: <valargroup/vizor-wallet#73>"
> (message permalink `p1781712451235229`)

This document is a full re-investigation against the correct repo, cloned locally at
`/Users/micutad/Projects/AndroidStudioProjects/vizor-wallet`, checked out on branch
`pr-73-migration` (exactly PR #73's head commit, `d458e7f7`, "Match Keystone batch limit at 40").
Every claim below is grounded in that checkout's actual code, not inference from the old
(wrong-repo) document, which should now be treated as void.

---

## 1. Vizor's migration architecture (PR #73)

### 1.1 State machine

Top-level **run phases** (`PHASE_*` string constants, `rust/src/wallet/sync/migration.rs:50-62`,
persisted in table `vizor_migration_runs.phase`):

```
no_orchard_funds → waiting_for_spendable_orchard → ready_to_prepare →
waiting_denom_confirmations → ready_to_migrate → broadcast_scheduled →
broadcasting → waiting_migration_confirmations → complete
```
with side-states `paused`, `failed_recoverable`, `failed_terminal`, `abandoned`. Transitions are
explicit function calls from `send.rs` (`mark_run_phase`, `migration.rs:460`), not a poller.

Nested inside each split step is a **denomination-stage sub-state-machine**
(`DenominationStageStatus`, `stages.rs:21-26`): `AwaitingInputs → Pending → Broadcasted →
Confirmed`, transitioned monotonically (`transition_stage`, `stages.rs:728-790`, guarded by SQL
`WHERE status = ...`).

**Fully SQLite-persisted, resumable across restarts.** Tables: `vizor_migration_runs`,
`vizor_migration_prepared_notes`, `vizor_migration_pending_txs`,
**`vizor_migration_signed_child_pczts`**, `vizor_migration_denomination_stages(_inputs/_outputs)`
(`migration.rs:45-48`, `stages.rs:170-246`). Secrets (raw txs, PCZTs, signatures) are stored
encrypted with the wallet's session password/salt (`secret_payload::encrypt_payload`, e.g.
`migration.rs:603-607`). `active_migration_run` reloads from disk on every entry point
(`migration.rs:396-404`); the only in-memory-only piece is a mutex guard against re-entrancy
(`ActiveIronwoodMigration`, `send.rs:3666-3696`), not state.

**Not concurrent with regular sync — explicitly serialized.** Every migration-mutating Dart call
wraps in `syncNotifier.pauseForWalletMutation(...)` / resume-in-`finally`
(`migration_run_controller.dart:234-287`). A separate lightweight 5s `Timer.periodic`
(`global_migration_warning_banner.dart:77-140`) submits due-scheduled broadcasts and nudges sync
to pick up confirmations while any run is active/visible — this is what keeps a paused/scheduled
migration progressing without a dedicated migration screen open.

**Reorg handling**: `reset_denomination_stage_for_reorg` (`stages.rs:800-842`) recursively resets a
stage and all descendants back to `AwaitingInputs` but **retains the base PCZT and signatures**, so
re-anchoring/re-proving after a reorg does not require a new Keystone scan (doc comment,
`stages.rs:14-19`). This is a direct, implemented answer to the "anchor section + transaction
expiry" open question flagged in the 2026-06-15 Slack sync.

### 1.2 Balance-splitting algorithm (`split_plan.rs`, `migration.rs`)

**Digit decomposition, confirmed real**: `plan_denominations` (`migration.rs:74-156`) greedily
decomposes the whole-ZEC balance into base-10 power denominations (1, 10, 100, 1000 ZEC, ...),
capped at `MIGRATION_MAX_PREPARED_NOTES_PER_RUN = 64` outputs (`migration.rs:38`). This matches the
"digit decomposition" concept discussed on Slack (Schell/Dev, 2026-06-16), evolved into a
deterministic, non-randomized (fee-exact) algorithm rather than the randomized two-random-digit
sampling floated in that thread.

**No separate "user-click" vs "background" code path** — a key finding relative to the pre-PR
Slack design discussion (which anticipated ~4-6 tx for click-through vs. many more for background).
The shipped code has exactly **one** algorithm (`plan_padded_denominations` →
`plan_exact_stage_count`, `send.rs:2979-2986`, `max_stages = 64` always), invoked identically from
both the user-triggered start and the background broadcast-due poller. It self-scales: small
balances naturally need few digits and therefore few splitting-transaction stages; large balances
need more, up to 64. The Slack framing ("few tx for click, many for background") evolved into "the
algorithm always emits the minimum stage count for the note set," which happens to be small for
small balances — not a mode switch.

**Splitting-transaction packing** (`split_plan.rs`): packs digit-decomposed outputs plus the
wallet's real input notes into the fewest 16-action-padded Orchard transactions
(`DENOMINATION_SPLIT_ACTIONS = 16`), tried in order: (1) `exact_two_root_plan` — meet-in-the-middle
exact-subset-sum for a 2-stage case; (2) `bounded_forest_plan` — bitmask-DP bounded search
(`FOREST_SEARCH_NODE_LIMIT = 1_000_000`) preferring independent parallel roots over long chains;
(3) `connected_chain_plan` — deterministic greedy chain fallback. A `Continuation` output type
carries leftover value forward stage-to-stage, chaining up to 64 links for very large holders — the
closest concept to "many cascading transactions for whales" from the Slack discussion.

**Privacy-motivated choices actually in code**: all splitting transactions padded to exactly 16
actions regardless of real input/output count (`send.rs:2988-2994`); broadcast-time timing
randomization via `random_schedule_offsets` (`migration.rs:2083-2107`) — an exponential
inter-arrival draw spread across a `MIGRATION_BROADCAST_WINDOW_SECS = 180`s window, sorted
ascending. There is **no amount-randomization or split-count randomization** — denominations are
deterministic given the balance, and the stage count is always the provably-minimal one, contrary
to what the "random two-digit decomposition" idea floated early in the Slack thread might suggest.
Rust unit tests pin exact behavior, e.g. `one_input_and_thirty_outputs_need_three_padded_stages`
(30 outputs from 1 input note require stages `[16, 16, 3]`), `split_plan.rs:1006-1298`.

### 1.3 The presign / deferred-proof question — DEFINITIVE ANSWER

**Vizor's PR #73 does presign with a placeholder witness and defers proof generation, exactly
matching our own "sign-now, prove-later" design.** This is directly evidenced in code, not
inferred:

`rust/src/wallet/sync/pczt.rs:3296-3303` (send.rs, migration-child PCZT construction):
```rust
// Migration children are built from predicted denomination notes before the
// split tx is mined. This dummy anchor is v6-only scaffolding. Orchard
// spend signatures do not commit to it, and finalization replaces it with
// the real anchor/witness before creating proofs.
let dummy_witness = dummy_orchard_merkle_path()?;
let dummy_anchor = {
    let cmx: orchard::note::ExtractedNoteCommitment = predicted.note.commitment().into();
    dummy_witness.root(cmx)
};
```
followed by building and **signing** the PCZT against this placeholder witness/anchor
(`send.rs:3305-3350`).

`rust/src/wallet/sync/pczt.rs:434-443,480-488` — the finalize-time counterpart,
`set_orchard_anchor_and_witnesses`:
```rust
/// Replaces a deferred v6 Orchard anchor and sets spend witnesses selected by
/// their nullifiers. ... The existing anchor is cleared because staged
/// transactions are initially constructed with a placeholder anchor; the
/// upstream Updater then enforces that the transaction format supports
/// deferred anchor updates and that no proof is already present.
...
let pczt = Redactor::new(pczt)
    .redact_orchard_with(|mut redactor| redactor.clear_anchor())
    .finish();
let updated = Updater::new(pczt)
    .set_orchard_anchor(anchor)
    .set_orchard_spend_witnesses(witness_updates)
    .finish();
```

This is the identical technique our own design doc (`2026-07-17-migration-sign-now-prove-later-design.md`
§4.2) describes: construct with a placeholder `MerklePath`/synthetic anchor, sign (Orchard spend
signatures don't commit to the anchor/witness), then later replace the placeholder with the real
anchor+witness before running the `Prover` role. The `vizor_migration_signed_child_pczts` table
(§1.1) is the persisted "signed but not yet proven/finalized" artifact — direct schema evidence of
the deferred stage. Vizor's own reorg-recovery logic (§1.1) explicitly relies on this: it retains
signatures across a reorg specifically because they don't depend on anchor/witness, only
re-finalization does.

**Resolving the apparent Slack contradiction.** The Dev quote — "with our plan B its n[o] longer
relevant right? we dont want to presign the transactions... balance split will be much more simple"
(2026-06-12, thread `p1781258709310909`, actually authored by `dominik`/us, not Dev) — was about
**ZODL's own default in-app flow ("plan B")**, not the shared Vizor reference implementation. Dev's
reply confirms the scope: "True, pre-signing is not relevant for ZODL on that. But its fair for some
wallets to want to do BG submission! Even in the broadcasting on user-click in plan B, you could
keep that pre-signed for a keystone user if you'd like" (`p1781281687828979`). The shipped PR #73
code — built for the shared Keystone/multi-wallet reference case, not ZODL's simplified default —
does presign, and does so specifically to support batch Keystone signing (§1.4) and background
submission. There is no contradiction once "plan B" is understood as ZODL-specific messaging
scope, separate from the reference-implementation code in PR #73.

### 1.4 Keystone batch QR signing flow

- **Encode**: PCZTs are wrapped in the `pczt::roles::signer::batch::{BatchSignRequest,
  BatchSignResponse}` types, surfaced to Keystone as UR registry types
  **`zcash-sign-batch`** (`ZCASH_SIGN_BATCH_TYPE`, `keystone.rs:118`) and
  **`zcash-batch-sig-result`** (response), with a legacy single-PCZT `zcash-sign-result` type also
  still supported for older Keystone firmware (confirmed in Dart:
  `migration_scan_screen.dart:47-52,91-101`). This is a genuine multi-PCZT batch protocol, not
  single-PCZT-at-a-time signing.
- **Batch size cap**: `pub(crate) const ZCASH_SIGN_BATCH_MAX_MESSAGES: usize = 40;`
  (`rust/src/wallet/keystone.rs:124`), enforced both on request construction
  (`keystone.rs:449-451`) and on response parsing (`keystone.rs:608-611,635-637`). Git history in
  this repo shows the team **oscillated** between 35 and 40 several times before settling — commits
  in order (oldest→newest): "Raise Keystone Zcash batch limit to 35" → "Exercise the 50-message
  Keystone batch limit" → "Match Keystone batch limit at 35"/"40" alternating four more times,
  ending at **40** (`d458e7f7`, the PR #73 head commit itself). **This is a genuine discrepancy
  with the Slack claim** ("tested 50 tx/batch, hit OOM, and settled conservatively on 35") — the
  code that actually shipped in PR #73 settled on **40**, not 35. Either the 35 in Slack predates a
  later re-tune, or the final commit represents a still-in-flux value; either way, the two sources
  disagree and should be reconciled with whoever owns that constant before treating either number
  as authoritative.
- **Correlation**: signatures returned from a batch are matched back to pending transfers **by
  position, not by an explicit id** (consistent across both our SDK's equivalent
  `migration_keystone.rs` and Vizor's approach) — caller must preserve request ordering.
  Oversized migrations (more denomination outputs than fit one 40-message QR batch) fall back to a
  **two-scan staged flow**: the split batch is signed and scanned back first, then — after the
  split confirms — the terminal transfers are signed in a second QR round
  (`migration_view_state.dart:294-301,337`; `migration_screen.dart:467-476`).
- **Proof generation is a distinct, later step from signing** in the Keystone UI too: the "Get
  signature" import button is gated behind `_keystoneProofReady`/`_keystoneProofProgress` — local
  proof generation happening after signatures are scanned back, not before
  (`migration_screen.dart:273-285`) — additional UI-level confirmation of the sign-then-prove
  ordering.

### 1.5 UI/UX flow

Single adaptive screen (`MigrationScreen`) driven by a 16-value `MigrationViewState` enum rather
than a multi-screen wizard: idle/entry card → warning-dialog confirm (not a separate screen) →
embedded 3-node timeline (Split / Confirm / Send) → Keystone scan/sign modal (for hardware
accounts) → done state. `MigrationCloseGuard` only intercepts the **desktop window-close** event
(`isDesktopLayoutPlatform`-gated) with a confirmation dialog ("migration steps will pause... will
not update until Vizor is reopened") — not a hard block, and not present on mobile at all; a
`paused`/`abandoned` view state exists for resuming a stalled run. A **global, app-wide warning
banner** (not scoped to the migration screen) is shown whenever any of {active run needing "keep
open", scheduled-but-unbroadcast splits, unconfirmed broadcasts, signed-but-unfinalized child
PCZTs} is true, and doubles as the mechanism that keeps a paused run progressing via its own 5s
tick. Privacy rationale for multiple transactions is communicated only implicitly ("Vizor splits
them into standard notes, then sends them over a short window") — no explicit "why this improves
your privacy" copy was found anywhere in `migration_copy.dart`.

---

## 2. Our own migration architecture

### 2.1 State machine & persistence

`zcash_pool_migration` (Rust crate, `librustzcash/zcash_pool_migration/src/`) tracks 14 internal
vizor-compatible phase strings collapsed to a 6-value public `MigrationState`
(`state.rs:11-99`). Persisted in five `ext_ironwood_migration_*` tables added to the **shared**
wallet SQLite DB (not a separate store) — `runs`, `prepared_notes`, `prep_tx`, `pending_txs`,
`staged_pczts` (`store.rs:218-283`). `pending_txs` carries a `proof_status` column (default
`'ready'`) added specifically for sign-now/prove-later; an idempotent `PRAGMA table_info`-guarded
`ALTER TABLE` handles pre-existing DBs (`store.rs:282,285-307`) — this migration-of-the-migration-
schema was a real bug hit live on testnet ("no such column" crash), now fixed. Fully resumable:
the crate is synchronous, holds no long-lived state beyond a call, and `migration_state()`
re-derives everything from persisted rows on every invocation (`lib.rs:38-42`).

### 2.2 Sign-now, prove-later (design doc `2026-07-17-migration-sign-now-prove-later-design.md`)

Same core mechanism as Vizor's, arrived at independently (confirmed correctness came from direct
input by Zcash core team members, quoted in the doc): construct a placeholder
`orchard::tree::MerklePath` via `MerklePath::from_parts(...)`, compute a synthetic anchor matching
that placeholder path's own root, build and sign normally, then **redact the synthetic anchor and
witness back to absent** via `pczt::roles::redactor::Redactor::redact_orchard_with(...)` (doc §4.2).
A correction made during implementation: redaction must clear only the **real spend's own action**
by index, not all actions — clearing a padded/dummy action's witness too breaks `Prover
(MissingWitness)` later.

Two persisted sub-states, implemented in `backend.rs`:
- **`SignedAwaitingProof`** — fully signed, anchor/witness redacted to `None`, not eligible for
  `next_due_transfer()` (`context.rs:1273-1311`, `backend.rs:277-329`).
- **`ReadyToBroadcast`** — real witness set + `Prover` role run (`backend.rs:604-700`).

Public completion entry point: `MigrationContext::finalize_ready_transfers()`
(`context.rs:1329-1350`), called from `MigrationWorker.doWork()` before the next-transfer check
(`work/MigrationWorker.kt:43-46`).

**Correction (2026-07-19, post-publication):** an earlier revision of this document claimed our
external-signer (Keystone) PCZT path still proves eagerly and skips note-splitting entirely for
`KeystoneAccount`. That was **stale** — it was based on `2026-07-18-migration-open-points.md`
without cross-checking that this exact gap was closed later the same day. Verified directly against
current code: `create_unsigned_transfer_pczts` (`context.rs:841-976`) *does* build self-funding
transfers against a placeholder witness/synthetic anchor (`context.rs:828-846`, doc comment
`context.rs:144-146`) and stages them `SignedAwaitingProof`, completed later by
`finalize_ready_transfers()` — committed as `a36ddbe711` ("defer proof on the external-signer
schedule path"), `git status` clean. `MigrationKeystoneSignVM.buildBatch()`
(`ui-lib/.../screen/migration/keystonesign/MigrationKeystoneSignVM.kt:57`) calls
`sdk.isNoteSplitNeeded()`/`createUnsignedNoteSplitPczt()` itself — note-split awareness moved from
`MigrationReviewVM` into the sign screen, per this session's own plan §7 ("no code change needed").
**Keystone does get the sign-now/prove-later benefit on our side, matching Vizor.** The real,
remaining gap is narrower — see the corrected §3.3 and §4 below.

### 2.3 Balance-splitting

`denominations.rs`: same deterministic `{1,2,5} × 10^n` decomposition, largest-first, **explicitly
ported from Vizor** (`plan_denominations`, `denominations.rs:1-16,47-131`). Each note self-funds:
denomination + `TRANSFER_FEE_BUFFER_ZATOSHI` (20,000 zatoshi, 4× ZIP-317 marginal fee).
`MIGRATION_THRESHOLD_ZATOSHI = 1,000,000` (0.01 ZEC) floor — sub-threshold residue stays as
Orchard change rather than being folded into fee, specifically to avoid deanonymizing
dust-attacked wallets. Cap: `MIGRATION_MAX_PREPARED_NOTES_PER_RUN = 64`, identical to Vizor's.

No user-click vs. background distinction in denomination sizing (matching Vizor) — the distinction
instead lives one layer up, in the SDK's `proposeMigrationTransfers` (scheduled, multi-transfer)
vs. `proposeImmediateMigration` (single full-balance sweep / "Send Now" opt-out, no split).

Randomization: `scheduling.rs` staggers send heights via independent exponential draws per gap
(mean `TARGET_CADENCE_BLOCKS=288` ≈6h, capped `MAX_CADENCE_BLOCKS=1152` ≈24h) — broadly the same
idea as Vizor's `random_schedule_offsets`, but operating over a much longer horizon (hours/day vs.
Vizor's 180-second broadcast window) since our design targets multi-day background scheduling
rather than a single foreground session. **A documented reversal worth flagging**: an earlier
design bucketed the anchor to a network-wide 288-block window for cross-wallet privacy, but this
was found unworkable against the SDK (checkpoints only exist at ~100-block scan-batch boundaries)
and was dropped (`scheduling.rs:14-19`). **`MigrationSdk.kt:88-90,375-376` still contain stale doc
comments describing the abandoned 288-block-bucket behavior** — a real doc/code drift that should
be corrected independent of this comparison.

### 2.4 Keystone batch QR signing

`backend-lib/src/main/rust/migration_keystone.rs` deliberately bypasses the stale compiled
`keystone-sdk-android` AAR and talks to the `ur-registry`/`ur` crates directly, **explicitly
mirroring Vizor's approach** per the file's own header comment. Uses the same
`pczt::roles::signer::batch::{BatchSignRequest, BatchSignResponse}` machinery wrapped in the same
Keystone UR types, `ZcashSignBatch`/`ZcashBatchSigResult`. The split PCZT and every transfer PCZT
are batched into one multi-frame animated QR; signatures are correlated **by position**, requiring
the Kotlin caller to preserve split-then-transfers ordering — same positional-correlation approach
Vizor uses. Decoding is stateful across JNI calls via a `static Mutex<Option<ur::Decoder>>`,
`request_id`-checked to reject stale scans.

**Resolved (2026-07-19):** confirmed no batch-size cap constant existed anywhere in our code (direct
grep across `migration_keystone.rs`, `migration.rs` (SDK), `MigrationSdk.kt` — only the unrelated
`MIGRATION_MAX_PREPARED_NOTES_PER_RUN = 64` note-count cap existed). Vizor hit this exact issue in
testing (OOM at 50, oscillated 35↔40↔50 before shipping 40) and hard-coded a guard; we've now done
the equivalent. Decision: **35** (the more conservative of the two documented, real-device-tested
numbers from Slack, vs. Vizor's shipped 40) — `KEYSTONE_BATCH_MAX_ITEMS`,
`ui-lib/.../screen/migration/keystonesign/KeystoneBatchChunking.kt`. Because our Keystone path
already defers proof for every item (§2.2 correction above), the oversized-migration fallback did
**not** need Vizor's "wait for split to confirm before round 2" staging: a migration whose split +
schedule exceed 35 items is chunked into multiple sign→scan round trips
(`keystoneBatchTotalRounds`/`keystoneBatchRoundSlice`), each built against a placeholder witness up
front same as the single-round case, with signed results accumulated across rounds
(`PendingKeystoneMigrationPczts.accumulatedSplitSigned`/`accumulatedTransferSigned`) and only
stored/broadcast/finalized once the last round completes. `MigrationKeystoneScanVM` uses
`NavigationRouter.replace()` to hop straight into the next round's fresh sign screen, keeping the
back stack at constant depth regardless of round count.

### 2.5 UI/UX flow

Multi-screen flow under `ui-lib/.../screen/migration/`: `howitworks → setup → battery → privacy →
review → keystonesign/keystonescan → progress/scheduled/sending → success/complete`, plus
`invalid`/`notification` and a now-unreferenced `transferreview` (dead code kept intentionally per
the manual-scheduling-unification design, in case of a future role). `MigrationReviewVM
.confirmAutomatic()` implements: `isNoteSplitNeeded()` → `prepareNoteSplit()` → `submitNoteSplit()`
→ `signAndStoreMigrationSchedule()`, matching the design doc's required sequence.

The **manual-scheduling-unification** work (`2026-07-16-...-design.md`) is fully implemented and
verified in the current tree: `FinalizeMigrationScheduleUseCase` no longer branches on delivery
mode — always `save → MigrationScheduler.schedule(delay) → navigate to Scheduled`; the old
`MigrationDeliveryMode.MANUAL` / `MigrationNotifyWorker` code paths are gone. `MigrationWorker`
reschedules on a "not ready yet" result using one block interval (~75s) rather than silently
stalling — a bug fixed in the same session that produced `2026-07-18-migration-open-points.md`.
`MigrationScheduler` uses WorkManager (`enqueueUniqueWork` + `ExistingWorkPolicy.REPLACE`);
Doze/App-Standby deferral is handled by on-launch reconciliation (`CheckMigrationRecoveryUseCase`
+ `hasOverdueTransfers()`) rather than iOS-style timing-margin tricks.

Unlike Vizor, there is **no dedicated close-guard widget and no persistent app-wide warning
banner** equivalent found in our UI layer during this investigation's source material — the
in-progress-migration nudge relies on `CheckMigrationRecoveryUseCase` redirecting on next app
launch/foreground rather than a live ticking banner while the app is open. (This wasn't
independently re-verified against the UI code in this pass beyond what the research agent
reported; flagged here as an asymmetry worth a follow-up look, not a confirmed gap.)

---

## 3. Comparison and honest engineering assessment

### 3.1 Where the two designs agree

- **Same core cryptographic technique**: both presign with a placeholder Orchard witness/synthetic
  anchor, then redact it back to absent, deferring the real witness fill and ZK proof generation to
  a later, persisted stage. Arrived at independently on both sides (ours cites core-team input
  directly in the design doc; Vizor's is embedded as inline code comments with the same
  justification — "Orchard spend signatures do not commit to [the anchor]"). This is strong
  cross-validation that the technique is sound: two teams, working from the same underlying
  protocol facts, converged on the identical mechanism.
- **Same denomination algorithm** (ours is an explicit, acknowledged port of Vizor's `{1,2,5}×10^n`
  greedy decomposition), same `MIGRATION_MAX_PREPARED_NOTES_PER_RUN = 64` cap, same
  dust-avoidance floor reasoning (leave sub-threshold residue as change rather than folding into
  fee, to avoid deanonymizing dust-attacked wallets).
- **Same Keystone batch-signing protocol shape**: both use `pczt::roles::signer::batch` wrapped in
  Keystone's `ZcashSignBatch`/`ZcashBatchSigResult` UR types, both correlate signatures by
  position rather than an explicit id, both fall back to a legacy single-PCZT path for older
  firmware/compatibility.
- **Same privacy-timing idea** (randomized broadcast scheduling to decorrelate migration
  transactions), differing only in horizon (Vizor: seconds-to-minutes, single foreground session;
  ours: hours-to-a-day, background-oriented).

### 3.2 Where they genuinely diverge

- **Session model**: Vizor's migration is fundamentally a *foreground, keep-the-app-open* flow with
  a short (180s) broadcast window and a close-guard/warning-banner UX built around that assumption.
  Ours is fundamentally a *background-scheduled* flow (WorkManager, hours/day cadence,
  `MigrationWorker`) with no equivalent "please keep the app open" framing — a different product
  bet, not a technical superiority either way, but it means Vizor's UX safety net (close guard,
  ticking banner) solves a problem our design doesn't have in the same form, and vice versa: our
  design needs OS-background-execution robustness (Doze, App Standby, process death) that a
  keep-foreground design like Vizor's never has to contend with.
- **State machine granularity**: Vizor's nested `DenominationStageStatus` state machine with
  explicit forest/chain packing (`bounded_forest_plan`, independent-roots-over-chains preference)
  is more elaborate than anything in our `split.rs`/`scheduling.rs` — Vizor is optimizing the
  splitting-transaction graph itself (fewer, shallower stages) as a first-class concern; our
  design treats denomination planning as more purely arithmetic and leans on the scheduler for
  privacy timing instead.
- **Reorg recovery**: Vizor has an explicit, tested reorg-recovery path
  (`reset_denomination_stage_for_reorg`, recursively resetting descendants while retaining
  signatures) that our own materials (from this investigation) don't show an equivalent for. This
  is a real, concrete robustness feature we should check we have parity on, not just a stylistic
  difference — reorgs are a certainty over a multi-day background migration schedule, arguably
  *more* likely to matter for our longer-horizon design than for Vizor's short foreground window.

### 3.3 Honest assessment — where one side has a real advantage the other should know about

**Corrected: Keystone completeness is close, not far behind.** (See correction note in §2.2.) Both
sides presign with deferred proof for the Keystone path, both use the same batch UR protocol, both
correlate by position. **The one concrete, verified gap remaining is the batch-size cap and its
oversized-migration fallback** (§2.4): Vizor enforces `ZCASH_SIGN_BATCH_MAX_MESSAGES` and falls back
to a two-round staged scan when a migration's split+transfers exceed it; we enforce no cap at all and
have no fallback for a batch that doesn't fit one QR session. Given our `MIGRATION_MAX_PREPARED_NOTES_PER_RUN
= 64` note cap, a large-balance migration can produce up to 65 signing items (1 split + 64
transfers) — comfortably over any Keystone-safe batch size — so this is a real, reachable gap, not a
theoretical one. Because our Keystone path already defers proof (unlike what this document
originally, incorrectly, claimed), our oversized-fallback does **not** need Vizor's
"wait for the split to confirm before round 2" staging — every item in every round can be built
against a placeholder witness up front, so a straightforward "chunk into ≤N-item rounds, sign all
rounds before broadcasting anything" design is viable and arguably simpler than Vizor's.

**We are ahead on background-scheduling robustness and bug-hardening from live testnet use.**
Vizor's design centers on a foreground session with a short, human-scale broadcast window; ours has
been stress-tested against real background-execution failure modes (silent-stall bug in
`MigrationWorker`'s retry loop, fixed; a real SQLite schema-migration crash on upgrade, fixed and
now idempotently guarded; block-height/epoch-seconds conflation bugs caught and fixed). These are
exactly the class of bug a foreground-only design like Vizor's would never surface, because it
doesn't need to survive process death, Doze, or multi-day elapsed time between steps. If Vizor's
reference implementation is ever adapted to a background-submission mode (which their own Slack
history flags as wanted for large holders — "I'm imagining whales just do background submission
with like 100 txs" — Dev, 2026-06-15), they will likely hit some version of these same bugs; our
fixes are a genuinely transferable lesson.

**Resolved (2026-07-19): batch-size cap + oversized-migration fallback implemented.** We adopted
**35** (the more conservative of Vizor's own 35↔40↔50 history) as `KEYSTONE_BATCH_MAX_ITEMS`, with
a multi-round sign→scan fallback for migrations whose split+schedule exceed it — see §2.4's
resolution note. The discrepancy between Vizor's shipped 40 and the Slack-quoted "settled on 35" is
still worth confirming with Adam/Dev directly (our own 35 is a documented choice, not a resolution
of *why* their two sources disagree), but no longer blocks anything on our side.

**Neither side has solved reorg-recovery-during-migration equivalently as far as this investigation
could confirm** — Vizor has an explicit, tested mechanism; nothing equivalent was surfaced on our
side by this investigation's source material. This should be explicitly checked (not assumed
either way) before treating our design as reorg-safe over a multi-day background schedule, where a
reorg is considerably more likely to occur mid-migration than during Vizor's ~3-minute broadcast
window.

**On privacy communication to the user**: neither implementation is clearly better. Vizor's copy
explains the mechanical "why" (splitting, timing window) without an explicit privacy rationale;
this investigation did not independently re-verify our own screen copy for the same gap, so no
claim is made either way here — worth a follow-up copy audit on our side specifically for this
point.

---

## 4. Summary table

| Dimension | Vizor PR #73 | Ours | Verdict |
|---|---|---|---|
| Presign + defer-proof (software path) | Yes, code-confirmed | Yes, code-confirmed | Converged independently — validates the technique |
| Presign + defer-proof (Keystone path) | Yes, full support | Yes (`a36ddbe711`) — corrected, was misreported | Equivalent |
| Denomination algorithm | `{1,2,5}×10^n` greedy | Same (explicit port) | Equivalent |
| Max notes/run cap | 64 | 64 | Equivalent |
| Keystone batch UR types | `ZcashSignBatch`/`ZcashBatchSigResult` | Same | Equivalent |
| Keystone batch size cap | 40 (tuned via device testing, history shows 35↔40 churn) | 35, enforced with a multi-round fallback (2026-07-19) | Equivalent — resolved |
| Session model | Foreground, ~180s broadcast window | Background, hours/day, WorkManager | Different bets, not comparable head-to-head |
| Reorg recovery during migration | Explicit, recursive, signature-preserving | Not confirmed present | Vizor ahead or at minimum better-evidenced |
| Background-execution robustness | Not applicable (foreground-only design) | Hardened via live-testnet bug fixes | We are ahead, but only because our design needs it |
| Close-guard / persistent warning UX | Yes (desktop close-guard + app-wide ticking banner) | Not confirmed equivalent | Possible gap, needs follow-up |

---

## Sources

- `valargroup/vizor-wallet` PR #73, local checkout `/Users/micutad/Projects/AndroidStudioProjects/vizor-wallet` @ `pr-73-migration` (`d458e7f7`).
- `librustzcash/zcash_pool_migration/**`, `librustzcash/docs/superpowers/specs/2026-07-17-migration-sign-now-prove-later-design.md`.
- `zcash-android-wallet-sdk/backend-lib/src/main/rust/migration.rs`, `migration_keystone.rs`, `sdk-lib/.../MigrationSdk.kt`.
- `zashi-android/ui-lib/.../screen/migration/**`, `ui-lib/.../work/MigrationWorker.kt`, `MigrationScheduler.kt`, `docs/superpowers/specs/2026-07-16-migration-manual-scheduling-unification-design.md`, `docs/superpowers/specs/2026-07-18-migration-open-points.md`.
- Slack `#ext-zodl-valargroup`: Adam, 2026-06-17 (`p1781712451235229`, PR #73 pointer); dominik/Dev exchange, 2026-06-12 (`p1781258709310909`, `p1781281687828979`, "plan B" scope clarification); Ironwood Migration Sync notes, 2026-06-15 (`p1781541101404659`) and 2026-06-08 (`p1780938195696499`).
