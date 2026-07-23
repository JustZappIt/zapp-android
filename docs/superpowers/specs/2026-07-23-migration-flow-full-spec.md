# Orchard → Ironwood Migration: Full Current-State Spec (2026-07-23 update)

**Status:** consolidated reference, written 2026-07-23, superseding
`docs/superpowers/specs/2026-07-22-migration-flow-full-spec.md` (which described the state as of app commit
`58691ab70` / SDK commit `dada0d3b`). This revision folds in ~15 app commits and ~12 SDK commits that landed
between then and app `a4faa707e` / SDK `64dbd83e` (2026-07-21 afternoon through 2026-07-23 morning), read from
each repo's `backup/feature-orchard_migration-20260723` branch (the pre-squash history — the live
`feature/orchard_migration` branch in both repos has since been squashed to a single commit:
`b7f0afb51` app-side, `49488df1` SDK-side).

Describes the feature as implemented on:
- `zashi-android` branch `feature/orchard_migration` (app)
- `zcash-android-wallet-sdk` branch `feature/orchard_migration` (JNI/Kotlin SDK)
- `librustzcash`, tracked at a commit that has itself moved substantially in this window — see §2 and §7.

**Headline changes since 07-22:**
1. **The Keystone hardware-signer batch path was non-functional end-to-end** as of the 07-22 snapshot (unscannable QR → unrecognized account even if scanned → firmware gate that always blocked real batches → crash on the one case that got furthest) — all four root causes are now fixed (§4.5).
2. **The SINGLETON_ID cross-account collision (old §5.2 item 1) is fixed**, exactly as the spec anticipated (§5.1).
3. **`migration_finalize.rs` (Zashi's own proving stopgap) is retired** — proving now goes through core's own `WalletMigrationProver`, moving that logic from the "ours" to the "core/upstream" side of the §2 ownership split (§3.7).
4. **`lockRemainingOrchardBalance()` is no longer a stub** — real note-locking, both SDK and app sides (§5.1).
5. **Keystone round-count is now real** (`estimateMigrationRunCount()`), and the multi-round auto-continuation bug (banner going silent after round 1) is fixed (§4.5, §5.1).
6. **The gross-vs-net amount marshal bug is fixed** — independently confirms/resolves iOS's finding on the same defect (§5.1, and see the separate iOS cross-check).
7. New open items surfaced by cross-referencing the iOS team's 2026-07-23 findings handoff, not previously documented in our own spec: `mark_mined` is never wired (engine progress never truly advances), and IMMEDIATE mode commits the same N-transfer plan instead of a real send-max — see §5.2 items 3-5. (A third item from that handoff, `isSyncRequiredBeforeNextTransfer()` being a false-stub, was resolved same-day by removing the dead method entirely rather than fixing it — see §5.1.)

---

## 1. Overview

*(Unchanged from 07-22 — see prior doc; reproduced here for completeness.)*

Zcash's NU6.3 upgrade (ZIP 374) introduces a new shielded pool, **Ironwood**, alongside the existing Orchard
pool. Ironwood is intended to eventually replace Orchard, and the Zashi wallet ships a feature that
automatically moves a user's Orchard funds into their own Ironwood addresses ("the migration"), because leaving
funds in a deprecating pool has both a UX cost (Orchard support fades over time) and, if enough of the network
migrates, a privacy cost for stragglers.

The mechanical problem is not "send everything in one transaction" — that would trivially leak the user's whole
Orchard balance and its migration timing to any observer. Instead, funds are split into standardized
denominations and moved in several separate transactions, spread randomly over hours, so that any single
transfer blends into a large anonymity set of same-shaped transfers from many wallets, rather than being
attributable to one wallet's balance. This scheme is specified by **ZIP 318** ("Pool Migration"); the mechanism
that lets a transaction be signed before its anchor/witness are known (needed because transfers are scheduled
far in advance of being broadcast) is **ZIP 374** (deferred/PCZT-based anchor resolution, "Ironwood pool").

**One-paragraph mental model:** the user reviews and confirms a migration plan (how much, roughly how long);
the wallet splits Orchard notes into canonical `{1,2,5}×10ⁿ` denominations via "preparation" self-send
transactions if needed; it then builds and pre-signs a set of transfer transactions (Orchard-in, Ironwood-out)
up front, but defers choosing their commitment-tree anchor and witness until just before each one is
broadcast; a background worker (or, for the "immediate" mode, the foreground UI) broadcasts them one at a time
on a randomized schedule over roughly a day, skipping windows that would coincide with a wallet sync (to avoid
correlating sync traffic with a broadcast); Keystone hardware-wallet users sign everything out-of-band via
batched QR codes instead of an in-process key.

---

## 2. Architecture across the 3 repos

```
┌─────────────────────────────────────────────────────────────────────────┐
│ zashi-android  (APP — "ours", freely changeable)                        │
│  ui-lib: screens/VMs, WorkManager scheduling, Keystone QR UX,           │
│  transaction history display, nav graph, Koin DI                        │
│  → talks only to the public Kotlin `OrchardMigrationSdk` interface      │
└───────────────────────────────┬───────────────────────────────────────┘
                                 │ Kotlin suspend fns / Flow
┌───────────────────────────────▼───────────────────────────────────────┐
│ zcash-android-wallet-sdk  (SDK — "ours", freely changeable)             │
│  sdk-lib: OrchardMigrationSdk / OrchardMigrationSdkImpl (public API)    │
│  backend-lib (JNI):                                                     │
│    migration.rs           — all JNI entry points, JNI↔engine encoding   │
│    migration_engine.rs    — Backend adapter: wallet DB → engine traits  │
│    migration_plan_cache.rs— in-memory plan cache (commit = sign cache)  │
│    migration_keystone.rs  — PCZT batch ↔ Keystone UR/QR bridge          │
│  → drives the core engine's traits/entry points from repo #3            │
│  NOTE (2026-07-23): migration_finalize.rs is RETIRED — see §3.7. Proving│
│  now goes through core's WalletMigrationProver, not app-adjacent glue.  │
└───────────────────────────────┬───────────────────────────────────────┘
                                 │ Rust crate deps
┌───────────────────────────────▼───────────────────────────────────────┐
│ librustzcash  (CORE — Zcash core team, shared across wallets,           │
│                changes need upstream coordination)                      │
│  zcash_pool_migration_backend — #![no_std], pure planning/state-machine │
│    engine.rs / state.rs / note_splitting.rs / preparation.rs /          │
│    scheduling.rs / build.rs / wallet.rs / WalletMigrationProver (new)   │
│  zcash_client_sqlite::pool_migration — persistence for the above,       │
│    NOW ACCOUNT-KEYED (folded in from the old standalone                │
│    zcash_pool_migration_sqlite crate — see §3.8)                        │
└─────────────────────────────────────────────────────────────────────────┘
```

**Ownership split (updated 2026-07-23):**
- **App (zashi-android)**: everything user-facing — screens, ViewModels, navigation, WorkManager scheduling,
  notifications, Keystone QR scanning UX, firmware gating UI, transaction-history display. Fully ours to fix.
- **SDK (zcash-android-wallet-sdk)**: the Kotlin `OrchardMigrationSdk` public surface, plus a thin(ish) but
  real layer of Zashi-specific Rust glue (`migration_engine.rs`, `migration_plan_cache.rs`,
  `migration_keystone.rs`) that adapts the wallet's own SQLite DB, account/key material, and Keystone signing
  model onto the core engine's traits. **Proving/finalizing has moved out of this layer** as of `89080a35`
  (§3.7) — that logic is now core-owned. What remains ours is smaller than the 07-22 doc described, but still
  real (e.g. §5.1's Keystone batch-signing fixes were all here).
- **Core (librustzcash, `zcash_pool_migration_backend` + `zcash_client_sqlite::pool_migration`)**: the actual
  planning/scheduling/state-machine engine and its persistence, intended as the canonical implementation "every
  Zcash wallet should eventually use," built by the Zcash core team. Changing this needs upstream coordination.
  **The tracked commit has moved substantially** in this window: per `a33c04cc`'s commit message, the SDK has
  absorbed upstream PRs #2669, #2712+#2720 (account-keyed store), and #2710+#2728/#2729/#2730/#2734 (the new
  `WalletMigrationProver`) since the `083bb6131805` pin the 07-22 doc cited. §7's "not independently
  re-confirmed" PR list is now further stale — treat any PR-number references in §7 as historical breadcrumbs,
  not a current tracking list.

**The engine rewire.** *(Unchanged historical background — see 07-22 doc §2 for the full account of the
`zcash_pool_migration` → `zcash_pool_migration_backend`/`_sqlite` rewire, commit `9d93b4de`/`9e97d88e`.)*

---

## 3. The engine's data/state model

*(§3.1-3.6 largely unchanged from 07-22 — reproduced with updates below. All types are from
`librustzcash/zcash_pool_migration_backend/src/{engine.rs,state.rs}` unless noted.)*

### 3.1 Traits the SDK implements to drive the engine

```rust
trait MigrationBackend {                 // planning inputs
    fn spendable_orchard_note_values(&self) -> Result<Vec<Zatoshis>, Self::Error>;
    fn chain_tip_height(&self) -> Result<BlockHeight, Self::Error>;
}
trait PoolMigrationRead  { fn get_migration(&self) -> Result<Option<MigrationState>, _>; }
trait PoolMigrationWrite: PoolMigrationRead {
    fn put_migration(&mut self, state: &MigrationState) -> Result<(), _>;
    fn update_transaction(&mut self, id: MigrationTxId, state: MigrationTxState) -> Result<(), _>;
}
trait MigrationCrypto {                  // build/sign inputs
    fn orchard_fvk(&self) -> Result<orchard::keys::FullViewingKey, _>;
    fn resolve_wallet_note(&self, index: usize) -> Result<orchard::note::Note, _>;
    fn sign(&self, pczt: pczt::Pczt) -> Result<pczt::Pczt, _>;
}
```
Notably, **no anchor and no witness appear in any trait** — ZIP 374 defers both to proving time, which is the
whole point of the sign-now/prove-later design (§3.5). In `zcash-android-wallet-sdk`, these traits are
implemented by `Backend<'a, W>` in `backend-lib/src/main/rust/migration_engine.rs`, wired to a real wallet DB
(`WalletDb`) and account.

**Updated 2026-07-23 (`a33c04cc`):** `Backend::new` is now **fallible** and takes the account explicitly:
```rust
pub fn new(wallet: &'a W, account: AccountUuid, usk: Option<UnifiedSpendingKey>, conn: &'a mut Connection)
    -> Result<Self, EngineError> {
    let store = PoolMigrations::for_account(conn, account)
        .map_err(|e| anyhow::anyhow!("opening pool-migration store failed: {e:?}"))?;
    Ok(Self { wallet, account, usk, store })
}
```
Every JNI call site in `migration.rs` (~15 of them) changed from `Backend::new(...)` to `Backend::new(...)?`.
This is the fix for the old §5.2 item 1 collision bug — see §5.1.

### 3.2 Core types

*(Unchanged — see 07-22 doc for full type listing: `MigrationTxId`, `MigrationTxKind`, `MigrationTxState`,
`MigrationTransaction`, `MigrationStatus`, `MigrationState`, `MigrationPlan`, state-view types, errors.)*

### 3.3 Engine entry points and driving order

*(Unchanged core description — `plan_migration`, `commit_preparation`, `build_preparation_unsigned`, the
driving loop over `next_step`. One caveat sharpened by the iOS cross-check, not by any commit in this window —
see §5.2 item 4: no commit found in either repo's diff wires `mark_mined` into the JNI/app driving loop. The
engine-level contract described here — "Mining is detected by the caller's own chain view →
`mark_mined(id, height)`" — still appears to be unimplemented on our side. Flagging explicitly since it means
`InProgress` counts and `Complete` derivation may never actually advance in practice; not independently
re-verified against the full JNI surface in this pass, but no fix commit was found for it in either repo's
07-21→07-23 history.)*

### 3.4 Note-splitting / denomination design

*(Unchanged.)*

### 3.5 Scheduling — timing, shuffling, and anchor bucketing (ZIP 318 privacy properties)

*(Unchanged.)*

### 3.6 Anchor bucketing vs. pruning depth — a confirmed structural tension

*(Unchanged — still listed as open in §5.2 item 7 (renumbered from the old item 2). No commit in this window
touches `PRUNING_DEPTH` or boundary-aware checkpoint retention.)*

### 3.7 Two signing paths — proving moved to core (2026-07-23)

- **In-process (software key)**: `commit_preparation` — build and sign the *entire* migration (split +
  transfers) in one pass using `SpendAuthorizingKey::from(usk.orchard())`, wrapped by
  `zcash_pool_migration_backend::build::sign_pczt`. Used by hot-wallet accounts.
- **External/hardware (Keystone)**: `build_preparation_unsigned` — same build, but PCZTs are left
  `AwaitingSignature`. Zashi batches these into Keystone QR sessions (see §4.5), then feeds the signed PCZTs
  back via `MigrationState::apply_signature`.
- **Build internals** (`build.rs`): `build_prep_tx` produces one padded 16-action preparation PCZT with *no*
  anchor/witnesses (bare notes — ZIP 374 deferral); `build_transfer_pczt` spends one self-funding note and
  outputs the crossing value to the account's own internal Ironwood change address. Both require the
  NU6.3/V6 transaction format, whose txid/sighash deliberately exclude the shielded anchor.
- **Proving/finalizing at broadcast time — RETIRED AND REPLACED (`89080a35`, 2026-07-23).** The 07-22 doc
  described this as living in Zashi's own `migration_finalize.rs`. That file (253 lines) is **deleted**, along
  with its `migration_proven_cache` side table and the `mod migration_finalize;` declaration in `lib.rs`.
  Proving now goes through core's own `zcash_pool_migration_backend::wallet::WalletMigrationProver` +
  `engine::prove_transfer`/`prove_preparation` (upstream PR #2710 + follow-ups). Proven state lives directly in
  the canonical `MigrationState` (`Signed → Proved`) instead of a side cache. A small local `try_prove`/
  `is_prove_ready` helper in `migration.rs` just wraps calling the upstream prover per-transaction. New public
  error types `ProveError`/`WalletProveError` come along with this. Also incidentally: `addProofsToPczt` for
  *ordinary* (non-migration) shielded sends now reuses `zcash_primitives::cached_orchard_proving_key` instead
  of rebuilding the Orchard proving key per call — a perf side-effect of the same commit.
  **This is the architecturally significant change in this update**: proving logic moves from the "ours,
  freely fixable" side of the §2 split to the "core, needs upstream coordination" side.

### 3.8 SQLite persistence — now account-keyed, no longer a singleton store

*(Old 07-22 §3.8 described a `SINGLETON_ID = 0` design with no per-account column, in a standalone
`zcash_pool_migration_sqlite` crate. **This is now stale.**)*

As of `a33c04cc` (2026-07-23), the store is opened per-account via `PoolMigrations::for_account(conn, account)`
— `SINGLETON_ID` is gone from the code path we call. Separately, the standalone `zcash_pool_migration_sqlite`
crate itself no longer exists as such; its tables have been folded into `zcash_client_sqlite::pool_migration`
(seen both in `cebdd2c2`'s commit message and in `migration_engine.rs::open_at()`'s updated comment — table
init now happens via `zcash_client_sqlite`'s own schema migration, `orchard_ironwood_migration_tables`, not a
separate `init_migration_tables` call). `put_migration` was also renamed to `replace_migration` upstream.
Historical note: the old singleton design and its `PoolMigrations::for_account` upstream fix (`#2712`) are
described in full in §5.1 (Fixed) rather than re-derived here.

---

## 4. The app-facing flow

*(All paths under `zashi-android/ui-lib/src/main/java/co/electriccoin/zcash/ui/` unless noted.)*

### 4.1 Entry point

A home-screen banner (`screen/home/migration/MigrationMessage.kt`, state in `MigrationMessageState.kt`,
rendered via `screen/home/HomeMessage.kt`) is the **highest-priority** message on the home screen. Its content
is one of three phases (`MigrationBannerPhase`): `REQUIRED`, `IN_PROGRESS`, `COMPLETE`. Produced by
`common/usecase/GetHomeMessageUseCase.kt`.

**Updated 2026-07-22/23:** the message-selection logic was extracted as a pure, independently-testable function
`migrationMessageFor()` (`c5dd05cf2`) and then wired into the actual banner (`9cc09a978`). That same commit
**removed a bug where `HomeVM` latched the "seen migration complete" flag as a side effect of the user tapping
the banner** — that responsibility now lives entirely in `MigrationCompleteVM.onDone()` (§4.2 step 15), which
needs to decide between "mark seen" and "clear the plan, don't mark seen" depending on whether a Keystone
account still has more migration rounds pending. Before this fix, tapping the banner on any completed round
could suppress the banner from ever re-appearing, even for the same user with a second (or third) required
round. Cosmetic styling was also fixed in the same window (`3f09d4174`): the banner's gradient previously read
theme-reactive Gray tokens (nearly invisible in dark mode) instead of theme-independent `ZashiLightColors`, and
its progress-ring track color was hardcoded purple instead of matching the surrounding card palette.

### 4.2 Screen sequence (as a user experiences it)

1. **Setup** — unchanged; a minor QA fix (`3f09d4174`) added `weight(1f, fill=false)` to the info-note text so
   a recently-lengthened string no longer overflows the row.
2. **How It Works** — unchanged.
3. **Routing decision** — unchanged.
4. **Privacy (Tor) sheet** — unchanged; see §4.6 for a related (compile-compat only) SDK-side fix.
5. **Battery-optimization exemption ask** — unchanged.
6. **Notification permission ask** — unchanged.
7. **Review ("Confirm Transfer Plan")** (`screen/migration/review/MigrationReviewVM.kt`) — several updates:
   - **Security fix (`3f09d4174`, 2026-07-23):** `onConfirm` previously signed with **no authentication
     step at all**. It now gates behind `biometricRepository.requestBiometrics()` before signing, matching the
     existing pattern used by `SubmitProposalUseCase` for ordinary sends. This was not flagged as a gap in the
     07-22 doc — it's a newly-identified-and-fixed issue, not a regression tracked previously.
   - The Confirm button now reads "Signing..." while `isConfirming` (same commit).
   - **Duration display consistency (`3f09d4174`):** Review previously computed `estimatedDuration` from
     firstAtHeight→lastAtHeight (omitting the wait before the first transfer), while the Scheduled-confirmation
     and Progress screens computed it from createdAt/anchorHeight→last-scheduled. Review now uses the same
     anchor-based span as the other two screens and as each transfer's own `scheduledLabel()` — the three
     screens' duration displays were inconsistent with each other until this fix.
   - **Round X of Y indicator is now real** (see §4.5) instead of always-null, and the transfer list now sits
     in a `LazyColumn` (`4ecb1a636`) so the header and Confirm button stay pinned instead of scrolling off
     with a long transfer list.
   - The split-balance row icon changed `ic_migration_coins_swap` → `ic_migration_check` on Review per Figma
     (a same-device split isn't a pool-crossing "swap"); a now-dead icon param was removed from the Progress
     screen, which already always rendered the check icon when done.
   - Confirm still branches three ways as before (IMMEDIATE / AUTOMATIC+Keystone / AUTOMATIC+hot-wallet). See
     §5.2 item 5 for a newly cross-referenced (iOS-sourced) concern about what IMMEDIATE actually builds.
8. **Scheduled confirmation** — unchanged besides the duration-consistency fix noted above.
9. **Sending** — unchanged.
10. **Tor Failure sheet** — unchanged.
11. **Success** — unchanged.
12. **Progress / Resume** — unchanged besides the dead icon-param removal noted above.
13. **Transfer Review** — still orphaned; no forward-navigation found in this window either (§4.9).
14. **Transfer Invalid** — unchanged.
15. **Complete** (`screen/migration/complete/MigrationCompleteVM.kt`) — substantially updated:
    - **Multi-round continuation fix (`16fec7ff4`/`a0b9764e4`/`1310b6cd8`, 2026-07-22):** `onDone()` now checks
      `getSelectedWalletAccount() is KeystoneAccount && getOrchardBalance().value > 0L`. If true, it **clears
      the stored `MigrationPlan` without setting the "seen" flag** (instead of marking seen) — this lets
      `migrationMessageFor()` (§4.1) naturally re-derive `plan == null` → `REQUIRED` and replay the whole flow
      from Setup for the next round. There is deliberately no "skip the intro" shortcut for round 2+. The whole
      decision sequence is now wrapped in try/finally so `backToRoot()` fires even if an exception occurs
      partway through (`1310b6cd8`) — previously an exception here could silently skip navigation and strand
      the user on the Complete screen.
    - **`CheckMigrationRecoveryUseCase.kt` updated in tandem (`a0b9764e4`):** now requires
      `migrationPlanRepository.load() != null` before showing the Complete celebration screen — otherwise a
      mid-campaign Keystone user (whose SDK-side `MigrationState` stays `Complete` between the engine's own
      rounds, since it only advances on the next commit) got trapped in a Complete-screen loop on every app
      relaunch.
    - **Hot-wallet multi-round continuation is explicitly deferred** — this fix is scoped to Keystone accounts
      only; see the new design doc referenced in §7.
    - **"Lock balance" is no longer a no-op** (`a4faa707e` app-side kdoc update, matching SDK commits
      `6adf2a39`+`64dbd83e`) — see §5.1. The UI-side gap flagged in the 07-22 doc (no loading/disabled state on
      the button while the call is in flight) is **still open** — no `isLocking` flag was added in this window;
      see §5.4 item 7 (revised).
    - **"Migrate anyway" still does not send** — confirmed still wired to `::onDone` (pure dismiss, no
      transaction built). §5.4 item 6 is unchanged/still open.

Migration state persistence (encrypted-prefs `MigrationPlanRepository`) is unchanged.

### 4.3 Delivery modes and background work

**Updated 2026-07-23:** `isSyncRequiredBeforeNextTransfer()` — flagged by the iOS cross-check (old §5.2 item 6)
as a false-stub always returning `false` on both platforms — has been removed entirely from both the SDK
interface and `MigrationWorker`'s three-step sequence (it was step "(2)"; the worker now falls straight from
`finalizeReadyTransfers()` to `executeNextPendingTransfer()`/scheduling unconditionally). It was never a working
gate to begin with. `isSyncBlocked()`'s `next_broadcastable`-driven overdue check (already wired into
`WalletCoordinator`, §4.4) is confirmed as the real, sufficient mechanism enforcing ZIP 318's sync/broadcast
decoupling MUST in both directions — nothing replaces the deleted method. See §5.1 for the resolution note.

### 4.4 Recovery / resume

`common/usecase/CheckMigrationRecoveryUseCase.kt` — updated as described in §4.2 step 15 (now requires an
active `MigrationPlan` before offering the Complete celebration).

**New fix (`a9dd9d30`, SDK-side, 2026-07-22):** `WalletCoordinator`'s `combine()` → `flatMapLatest` chain
(the same one this section already described as wiring `isSyncBlocked()` into sync pausing) had a cold-start
bug: `isSyncBlocked`'s first real value always arrives asynchronously, after `combine()` had already fired once
with a stale placeholder. That redundant re-emission hit `flatMapLatest` and **cancelled an in-flight
`Synchronizer.new()`/checkpoint-loading on every cold start**, even when nothing had actually changed. Fixed by
inserting `.distinctUntilChanged()` before the `flatMapLatest`. Not migration-specific in cause, but directly
affects how reliably migration's sync-pause wiring behaves on app start.

### 4.5 Keystone hardware-wallet signing path — was broken end-to-end, now fixed

**Important framing for this revision:** as of the 07-22 snapshot, four independent bugs — two SDK-side, found
and fixed only in this window — combined so that the Keystone batch-signing path could not actually complete a
real migration:

1. **Unscannable QR (`0051cdd9`, SDK):** `build_sign_batch_qr_parts` fed raw, unredacted PCZTs into the batch-
   sign request. The wallet's own IO-finalizer had already `spend_auth_sig`-signed dummy padding spends, and
   Keystone's batch firmware **rejects outright any batch containing a pre-existing Orchard `spend_auth_sig`**
   — so the generated QR was unscannable on a real device, every time. Fixed by routing every PCZT through
   `redact_pczt_for_batch_signer` first (matches the Vizor reference implementation's PR #84).
2. **Unrecognizable account even if scanned (`78429631`, SDK):** builders never set `spend_zip32_derivation` on
   migration PCZT actions; combined with the redaction above (which also clears `spend_fvk`), Keystone **had no
   way to identify which account owned the inputs at all**, surfacing "None of inputs belongs to the provided
   account" on-device. Fixed via a new `annotate_spend_zip32_derivation` step, applied post-IoFinalizer.
3. **Firmware gate always blocked real batches (`8d9f53cd` SDK + `0e68647f0` app):** the gate scanned the
   **signed PCZT's** bytes for a `keystone:fw_version` proprietary stamp, but the compact batch-sign protocol's
   response is signatures-only and never echoes PCZT bytes back — so the stamp could never be found, and the
   check always reported the device as unsupported, blocking every real batch-signed (i.e. every real
   multi-item) migration. Fixed: firmware version is now read directly from the `zcash-batch-sig-result`
   envelope's own field (new `firmwareVersion: ByteArray?` plumbed through `DecodePartResult` →
   `JniKeystoneBatchDecodeResult` → `KeystoneBatchDecodeResult`), and the app-side check moved to right after
   decode (round 0 only) instead of after signature application (`MigrationKeystoneScanVM.kt`).
4. **Crash on the path that got furthest (`cebdd2c2`, SDK):** `storeSignedNoteSplitPcztNative` applied the
   Keystone signature and returned raw bytes for extraction **without resolving the deferred witness/anchor
   first**, so `extractBroadcastTxNative` crashed with a `MissingAnchor`-class error. Fixed by extracting a
   shared `finalize_note_split()` helper, now used by both the software-key and Keystone paths. The same commit
   also fixes the amount-marshal bug described in §5.1 (independently overlaps with an iOS finding).

All four are fixed as of `64dbd83e`/`a4faa707e`. Additional app-side Keystone fixes in this window:

- **`92b07a7e9`:** `SelectKeystoneAccountViewModel` derived the paired device's address inside a `mapLatest`
  flow transform with no error handling — pairing a Mainnet Keystone device against a testnet build threw
  inside the SDK and crashed the app. Fixed by deriving the address once in `init` (a side effect, not inside
  the flow), routing failure through the generic error screen instead.
- **`ec74cc1b2`:** the Keystone sign screen had no user interaction during animated multi-part QR display, so
  the screen could dim/sleep mid-scan. Fixed by reusing the existing `DisableScreenTimeout()` mechanism.
- **`3f09d4174`:** added a scoped `finalizingLce`/`isFinalizing` state around the last round's finish (Tor
  submit, schedule storage, finalize) — previously this real network/JNI work gave zero UI feedback; now shows
  a `CircularScreenProgressIndicator` overlay.

**Round-count / multi-round auto-continuation (2026-07-22, 4-commit chain):**
- SDK now exposes `OrchardMigrationSdk.estimateMigrationRunCount(): Int?` (`a1940c54`+`5c867693`, wrapping
  upstream's `engine::estimate_migration_runs`). **Important nuance, documented explicitly in the SDK's own doc
  comments:** this is a **stateless, read-only preview with no memory of prior calls or already-committed
  rounds** — the app must call it fresh each time (e.g. on every Review-screen entry) and derive "Round X of Y"
  itself; it does not track an in-progress campaign's current round by itself.
- App-side (`d34357c45` mock stub → `c852ef2e5` populate `MigrationKeystoneRound` from it → `8dcdb8804` fix):
  any non-null estimate — including 0 or 1 — previously built a `MigrationKeystoneRound`, producing nonsensical
  "Round 1 of 0" / spurious "Round 1 of 1" for genuinely single-round migrations. Now only estimates `> 1`
  populate it.
- The **actual multi-round continuation bug** (distinct from the display bug above) is the Complete-screen fix
  described in §4.2 step 15 — this is what makes round 2+ actually get offered instead of the banner going
  silent.
- **Hot-wallet accounts are explicitly out of scope for auto-continuation** in this window — see the new
  design doc in §7.

**Firmware version gate** — unchanged in its core policy description (3.0.2 "cypherpunk" floor, no-stamp-means-
`LEGACY`, round-0-only check) except for the version-source fix described above (item 3).

### 4.6 Tor during migration — current behavior vs. the flow-scoped design

*(Behavior unchanged from 07-22 — the flow-scoped `useTor` flag design is still not implemented; every
broadcast site still independently reads the global flag; the custom-server hard-block sheet and per-attempt
IMMEDIATE retry decision still work as described.)*

**New (`505c766f`, SDK, compile-compat only):** upstream `zcash_client_backend` changed `allow_onion_services`
from internally-inferred to an explicit caller-supplied parameter on `connect_to_lightwalletd` (a breaking API
change). Fixed by re-deriving it locally from `endpoint.host().ends_with(".onion")` at each call site. This is
**not a behavior change** — custom `.onion` lightwalletd servers keep working exactly as before — and it does
**not** touch the two Tor gaps this section and the iOS cross-check both flag as still open: the hardcoded
`useTor = false` on the software note-split broadcast (§5.2 item 5, iOS-sourced), and the missing flow-scoped
flag itself.

### 4.7 Transaction history

*(Unchanged.)*

### 4.8 DI and mock

*(Unchanged, plus:)* `common/migration/OrchardMigrationSdkMock.kt` gained a stub implementation of
`estimateMigrationRunCount()` (`d34357c45`) to keep the mock interface in sync with the real SDK surface; it
remains unbound in any Koin module / unused in any build variant, per the 07-22 doc's note.

### 4.9 Things searched for but not found in the app code

- No forward-navigation call into `MigrationTransferReviewArgs` — still true in this window.
- No implemented flow-scoped Tor flag (§4.6) — still true.
- No transaction-history migration labels/filters (§4.7) — still true, unaudited this pass.
- ~~No Keystone branch for IMMEDIATE mode~~ — unchanged claim, still true (IMMEDIATE always signs with the
  software key); newly relevant given §5.2 item 5 (iOS's finding that IMMEDIATE should be an ordinary send-max,
  which would make a Keystone branch for IMMEDIATE the natural next step per iOS's own note).
- ~~`MigrationPlan.keystoneRound` / `MigrationReviewState.keystoneRound` are always `null`~~ — **RESOLVED this
  window**, see §4.5 and §5.1. Removed from this list.
- Displayed fees on Review/Transfer-Review are still hardcoded 1,000-zatoshi placeholders — not touched in this
  window; still open.

---

## 5. Known issues

### 5.1 Fixed

**Carried forward from 07-22 (historical, not re-litigated):** `isNoteSplitNeeded` false positive, multi-
witness resolution in finalize (pre-retirement of `migration_finalize.rs`), missing anchor fallback for
preparation transactions, schedule/amount mispairing, "everything due Now" display bug, UI transfer ordering,
MANUAL-mode confirm-time immediate broadcast privacy bug, and the various earlier fixes against the retired
custom engine.

**New in this revision (2026-07-21 afternoon → 2026-07-23 morning):**

- **Cross-account migration-state collision (old §5.2 item 1) — fixed `a33c04cc` (2026-07-23).** The store is
  now account-keyed via `PoolMigrations::for_account`; `Backend::new` is fallible and every call site threads
  the `Result`. Adopted from upstream `librustzcash` main (PR #2712 + FK fixup #2720), exactly as the 07-22 doc
  anticipated — see §3.1/§3.8. The regression test `singleton_id_collision_between_accounts` (§6) should now
  exercise its "fixed" branch; worth confirming it was updated/still passes rather than assuming so.
- **`lockRemainingOrchardBalance()` — fixed `6adf2a39`+`64dbd83e` (SDK) / `a4faa707e` (app docs), 2026-07-23.**
  Real implementation: selects every spendable Orchard note (`LockFilter::Unfiltered`, adopted from upstream
  note-locking PR #2716) and calls `WalletWrite::lock_outputs(&outputs, DUST_LOCK_OWNER, BlockHeight::MAX)` — a
  fixed well-known `LockOwner` constant, non-expiring lock, idempotent on repeat taps (same-owner re-lock).
  Every real note-selection call site (sends, shielding, migration itself) now passes
  `LockFilter::Policy(&LockedInputPolicy::Exclude)`, so locked notes are respected everywhere automatically.
  Public Kotlin signature unchanged (`OrchardMigrationSdk.lockRemainingOrchardBalance(): Unit`); only the body
  changed from a no-op to a real call.
- **Keystone batch-signing path (4 bugs) — fixed, see §4.5 for full detail.** QR redaction (`0051cdd9`), spend
  ZIP-32 derivation annotation (`78429631`), firmware-version source (`8d9f53cd`+`0e68647f0`), and note-split
  anchor resolution before extraction (`cebdd2c2`). Combined severity note: these four together meant the
  Keystone path was non-functional end-to-end at the 07-22 snapshot.
- **Gross-vs-net amount marshal bug — fixed `cebdd2c2` (2026-07-22 14:41).** `encode_migration_schedule`
  previously passed the gross `funding_notes()` value (crossing + fee buffer) as the displayed transfer amount;
  this commit subtracts `note_fee_buffer` to recover the round `{1,2,5}×10ⁿ` crossing value. This fix predates
  and independently resolves the iOS team's finding on the same defect at their audited snapshot — see the
  separate iOS cross-check for detail.
- **Home banner seen-flag latching bug — fixed `9cc09a978` (2026-07-22).** See §4.1.
- **Multi-round Keystone continuation (banner going silent after round 1) — fixed `16fec7ff4`/`a0b9764e4`/
  `1310b6cd8` (2026-07-22).** See §4.2 step 15 and §4.5.
- **`WalletCoordinator` cold-start `flatMapLatest` cancellation — fixed `a9dd9d30` (2026-07-22).** See §4.4.
- **Missing biometric auth gate on migration Confirm — fixed `3f09d4174` (2026-07-23).** See §4.2 step 7.
- **`isSyncRequiredBeforeNextTransfer()` false-stub (old §5.2 item 6) — removed as dead code (2026-07-23).**
  Deleted entirely (SDK interface, Kotlin/JNI chain, Rust function, and the app-side `MigrationWorker` guard
  that consulted it) rather than fixed — `isSyncBlocked()`'s `next_broadcastable`-driven check already provides
  the real ZIP 318 sync/broadcast decoupling, so there was no gap to fill. See §4.3.
- **Various QA-pass cosmetic fixes (`3f09d4174`, `4ecb1a636`)** — banner dark-mode gradient/progress-ring
  color, Setup-screen text overflow, top-app-bar font-family, split-vs-swap icon correctness, duration-display
  consistency across Review/Scheduled/Progress, independently-scrolling Review transfer list.

### 5.2 Currently open

Renumbered from 07-22; items 1 (SINGLETON_ID) and the old item-3 framing have moved/changed as noted.

#### 1. `backend-lib` compile status against the currently tracked librustzcash commit — status uncertain, needs a fresh check

The 07-22 doc documented a concrete compile break (`E0599`/`E0282`/`E0061`) against commit `083bb6131805`. Since
then the tracked commit has moved substantially (§2) — the SDK now builds against upstream PRs #2669, #2712/
#2720, and #2710+#2728/#2729/#2730/#2734, all landed via the commits described in §3.7/§3.8/§5.1. It's likely
these specific errors are moot (the `ufvk` import, the `create_proposed_transactions` arg-count mismatch, etc.
would need to have been resolved as part of adopting those PRs), **but this was not independently re-verified
in this pass** — no fresh `./gradlew compileZcashtestnetFossDebugKotlin` run was performed. Treat as "probably
fine, confirm before relying on it."

#### 2. Anchor-bucketing minimum age exceeds wallet pruning depth — unresolved, unchanged

*(= old item 2, renumbered. No commit in this window touches `PRUNING_DEPTH` or boundary-aware checkpoint
retention. See §3.6.)*

#### 3. `mark_mined` may never be wired into the driving loop — newly flagged (iOS cross-check), not independently re-verified this pass

Cross-referencing the iOS team's 2026-07-23 findings: their audit reports that our driving loop broadcasts and
re-arms transfers but never calls `mark_mined(id, height)`, so `InProgress` counts never advance and a finished
migration never derives `Complete` — currently masked because the home banner ships behind
`MIGRATION_BANNER_ENABLED = false`, but the Complete screen path itself is not masked. No commit in either
repo's 07-21→07-23 history touches this. Not independently re-verified against the full JNI surface in this
pass (the diff-analysis forks that produced most of this update were scoped to the specific commit ranges, not
a full audit) — flagging as a credible, actionable, real gap pending our own verification. See the separate
iOS cross-check document for the suggested portable fix (reconcile mined-ness at every state read, per the iOS
SDK's own approach).

#### 4. IMMEDIATE mode may commit the same N-transfer engine plan instead of an ordinary send-max — newly flagged (iOS cross-check)

The iOS team's audit reports that Android's IMMEDIATE mode, after the engine rewire (`9d93b4de`), lost a
single-transaction "immediate = ordinary send-max" behavior and now commits the same ZIP-318 denominated
N-transfer plan as AUTOMATIC, just with broadcasts collapsed together — producing a "different amount every
attempt" symptom, since the shuffle (§3.5, a genuine ZIP-318 MUST) reorders denominations per proposal. Neither
repo's diff in this window shows a fix. Not independently re-verified against current source in this pass — see
the iOS cross-check document (§1.2 there) for the suggested portable fix (SDK-orchestrated ordinary send-max,
bypassing the migration engine).

#### 5. IMMEDIATE mode may surface Progress/Complete UI it shouldn't — newly flagged (iOS cross-check), depends on item 4

Related to item 4: if IMMEDIATE still runs through the engine's state machine, a recorded immediate run could
derive `InProgress`/`Complete` and re-trigger banner/status machinery for what should be an ordinary "Sent!"
experience. Not independently re-verified this pass.

*(Old item 6, `isSyncRequiredBeforeNextTransfer()` false-stub, moved to §5.1 (Fixed) — removed as dead code,
2026-07-23.)*

### 5.3 Send screen: no NU6.3/Orchard-spend privacy warning

*(= old §5.4 item 4, unchanged/still not started.)*

### 5.4 App-layer gaps carried forward from 07-22, with updates

4. **Send screen NU6.3/Orchard-spend privacy warning** — unchanged, not started.
5. **Tor-connection-failure background notification not wired** — unchanged, not started; no commit in this
   window touches `MigrationNotifier.kt`'s Tor-specific gap.
6. **Migration Complete "Migrate anyway" doesn't actually send** — confirmed **still open** in this window;
   `MigrationCompleteVM.onMigrateAnyway` remains wired to `::onDone` (dismiss-only). The `onDone()` changes in
   this revision (§4.2 step 15) are about Keystone round-continuation bookkeeping, not about making "Migrate
   anyway" send anything.
7. **Migration Complete "Lock balance" loading state** — **split status**: the underlying SDK call is
   **no longer a no-op stub** (fixed, §5.1) — but the UI-side gap (no `isLocking`/disabled-button state while
   the call is in flight) is **still open**; not addressed in this window.

---

## 6. Test coverage

### 6.1 Live Rust integration tests (`zcash-android-wallet-sdk/backend-lib/src/main/rust/migration.rs`)

*(Structure unchanged from 07-22 — same opt-in, `#[ignore]`-gated, real-wallet-DB-fixture design; same
`--test-threads=1` caveat for the edge-case module.)* Updated in this window: the `build_and_finalize_all_unsigned`
test helper was deleted along with `migration_finalize.rs` (§3.7) — its witness/anchor bug class is now covered
by the upstream prover's own test suite instead. A new regression test,
`store_signed_note_split_resolves_anchor_before_extraction`, was added (`cebdd2c2`) covering fix #4 in §4.5.
Every `Backend::new` call site in tests now uses `.expect(...)` to handle the newly-fallible constructor.

### 6.2 Kotlin/app-side tests

Existing Keystone-firmware tests unchanged. New in this window: `FinalizeMigrationScheduleUseCaseTest.kt`,
`GetHomeMessageUseCaseMigrationTest.kt` (covering the `migrationMessageFor()` extraction, §4.1), and
`MigrationCompleteVMTest.kt` (covering the round-continuation decision logic, §4.2 step 15) were added.
`MigrationKeystoneScanVMTest.kt` was updated for the firmware-version-source fix (§4.5).

### 6.3 Real gaps

*(Unchanged — anchor-bucketing/pruning-depth tension still has no automated coverage; cross-account collision
still only covered by the one dedicated reproduction test, whose "fixed" branch should now be re-checked given
§5.1's fix; multi-round Keystone signature accumulation is still in-memory only with no persisted partial-
progress recovery.)*

---

## 7. References

*(Historical/decision-rationale docs, updated list.)*

- **`2026-07-22-keystone-multi-round-migration-continuation-design.md`** — new in this window (added by
  `41be8f42d`). Resolves how a Keystone account gets re-offered migration across multiple 50-note-cap runs:
  (1) auto-continuation via plan-clear-not-seen-flag is the must-fix root cause, independent of round display;
  (2) round-count display depends on the `estimateMigrationRunCount()` API, which itself depended on a pending
  librustzcash bump that also incidentally carried the SINGLETON_ID fix (#2712) and the compile-break-relevant
  changes; (3) explicitly Keystone-only scope, hot-wallet continuation deferred; (4) round N+1 always replays
  the full flow from Setup, no shortcut. See §4.2 step 15 and §4.5 for what's actually implemented vs. this
  doc's original scope.
- **`2026-07-22-migration-open-points.md`** — replaced the old `2026-07-18-migration-open-points.md` (deleted
  in the same commit, `41be8f42d`). Its 7 items map 1:1 onto this doc's §5.2 items 1-2 (renumbered) and §5.4
  items 4-7 — nothing in it is new beyond what's already folded in here. **Caveat carried over:** the *old*
  07-18 doc's items (stuck-note-split timeout, transient-overdue-flash UI, nav-debounce not idempotent, missing
  sync-readiness gate at migration entry) do not reappear in the new doc and were not independently re-verified
  as fixed, re-flagged, or simply dropped in this pass — worth a follow-up check if they still matter.
- All other 07-22-doc references (`2026-07-21-current-migration-implementation-spec.md`,
  `2026-07-16-migration-denomination-and-tor-redesign.md`,
  `2026-07-16-migration-manual-scheduling-unification-design.md`,
  `2026-07-17-migration-tor-flow-scoped-flag-design.md`, `2026-07-18-vizor-architecture-comparison.md`,
  `2026-07-19-vizor-migration-reference-comparison.md`,
  `2026-07-20-keystone-firmware-version-check-migration-design.md`) — unchanged, still accurate as historical
  record; see the 07-22 doc for full annotations.

Upstream PRs referenced across this revision (not independently re-verified against GitHub beyond what's noted
in commit messages):
- `zcash/librustzcash#2712` (+ FK fixup `#2720`) — account-keyed `PoolMigrations::for_account`; **now adopted**
  (§3.1/§3.8/§5.1), superseding the "open/unmerged" status the 07-22 doc recorded.
- `zcash/librustzcash#2710` (+ `#2728`/`#2729`/`#2730`/`#2734`) — the new `WalletMigrationProver`; **now
  adopted** (§3.7).
- `zcash/librustzcash#2716` — note-locking `LockFilter`; **now adopted** (§5.1).
- `zcash/librustzcash#2669` — absorbed per `a33c04cc`'s commit message; not independently characterized in this
  pass.
- Vizor reference PR `#84` (`valargroup/vizor-wallet`) — source of the PCZT-redaction pattern adopted in
  `0051cdd9`.
- `zodl-inc/zodl-ios#1689` — iOS counterpart of the firmware version check (unchanged reference).

See the separate document, `2026-07-23-ios-findings-cross-check.md`, for a full item-by-item comparison of this
spec against the iOS team's 2026-07-23 findings handoff.
