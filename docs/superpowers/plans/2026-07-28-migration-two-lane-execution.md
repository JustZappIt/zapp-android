# Two-Lane Migration Background Execution — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement spec `docs/superpowers/specs/2026-07-27-migration-two-lane-android-design.md`: Lane A (periodic sync+prove worker) and Lane B (broadcast-only worker) with estimated-tip due-ness, engine shift for missed windows, invalidation persistence, and submit-crash reconciliation.

**Architecture:** SDK Rust FFI changes land first (tri-state due result, reschedule primitive, invalidation store, reconciliation), then the SDK Kotlin surface (estimator, tri-state outcome, syncToTip, in-flight gate), then the app workers/lifecycle. Each layer is testable on its own.

**Tech Stack:** Rust (JNI, `zcash_pool_migration_backend` engine), Kotlin (coroutines, WorkManager, Koin), JUnit + kotlin.test.

## Global Constraints

- App worktree: `/Users/micutad/Projects/AndroidStudioProjects/z/wt/migration/zodl-android` (branch `android-slipstream-ironwood-chp`).
- SDK worktree: `/Users/micutad/Projects/AndroidStudioProjects/z/wt/migration/zcash-android-wallet-sdk` (same branch). Rust code: `backend-lib/src/main/rust/migration.rs`; Rust tests run with `cd <sdk>/backend-lib && cargo test <filter>`.
- App unit tests: `./gradlew :ui-lib:testZcashtestnetFossDebugUnitTest -Pcoverage=false --max-workers=1` (OOM guard — always pass both flags). SDK Kotlin tests: `./gradlew :sdk-lib:testDebugUnitTest -Pcoverage=false --max-workers=1` in the SDK worktree.
- **Never modify `expiry_height`** of any transaction — the ZIP 374 sighash covers it; changing it invalidates signatures (Keystone especially).
- **Estimated tip is used ONLY for `scheduled_height` due-ness.** Every expiry/rebuild/invalidity check uses the scanned tip. The `isSyncBlockedNow` gate always passes `-1` (disabled estimate).
- Privacy buffer: mainnet `10.minutes`, testnet `3.minutes` (network-scaled, NOT build-type-scaled). Lane A cadence: mainnet `60.minutes` (±10 min jitter), testnet `5.minutes`.
- ktlint runs on app commits — watch `chain-method-continuation` (break chains before `.`); commit messages end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Commit in the worktree the task touches (SDK tasks commit in the SDK worktree, app tasks in the app worktree).

---

### Task 1: Rust — tri-state `nextDueTransferNative` with `estimated_tip` + terminal guard + scanned-tip expiry filter

**Files:**
- Modify: `<sdk>/backend-lib/src/main/rust/migration.rs` (`nextDueTransferNative` ~line 1372, `hasOverdueTransfersNative` ~line 1187)
- Modify: `<sdk>/backend-lib/src/main/java/cash/z/ecc/android/sdk/internal/model/migration/JniMigrationModels.kt`
- Modify: `<sdk>/backend-lib/src/main/java/cash/z/ecc/android/sdk/internal/jni/MigrationRustBackend.kt` (~lines 118-126, 298-305, 575-581, 674-680)
- Test: Rust `#[cfg(test)]` module in `migration.rs` (follow the existing test style there, e.g. `store_signed_note_split_resolves_anchor_before_extraction`)

**Interfaces:**
- Produces JNI model (backend-lib Kotlin):
```kotlin
@Keep
class JniDueTransferResult(
    /** 0 = NOTHING_DUE, 1 = READY, 2 = AWAITING_PROOF */
    val status: Int,
    /** Non-null when status == 2 (the due-but-unproven transfer). */
    val awaitingProofTransferId: String?,
    /** Non-null when status == 1. */
    val prepared: JniPreparedTransfer?,
)
```
- Produces native signatures:
```kotlin
private external fun nextDueTransferNative(
    dbDataPath: String, networkId: Int, accountUuidBytes: ByteArray, estimatedTip: Long
): JniDueTransferResult
private external fun hasOverdueTransfersNative(
    dbDataPath: String, networkId: Int, accountUuidBytes: ByteArray, estimatedTip: Long
): Boolean
```
  with suspend wrappers `nextDueTransfer(dbDataPath, networkId, accountUuidBytes, estimatedTip): JniDueTransferResult` and `hasOverdueTransfers(..., estimatedTip): Boolean` (same `withContext(SdkDispatchers.DATABASE_IO)` pattern as today).

- [ ] **Step 1: Write failing Rust tests** in `migration.rs`'s test module. Use the existing test harness helpers in that module (in-memory/basen wallet setup used by the current reschedule/note-split tests). Cover:

```rust
// 1. Terminal migration yields NOTHING_DUE even with a Proved+due transfer persisted.
#[test]
fn next_due_is_nothing_when_migration_terminal() { /* build state, set status Failed via clear path, assert status==0 */ }

// 2. Signed (unproven) transfer whose scheduled_height <= effective tip -> AWAITING_PROOF with its id.
#[test]
fn next_due_reports_awaiting_proof_for_due_signed_transfer() { /* assert status==2, id matches */ }

// 3. estimated_tip accelerates due-ness: scheduled at scanned+5, estimated=scanned+6 -> READY/AWAITING_PROOF; estimated=-1 -> NOTHING_DUE.
#[test]
fn estimated_tip_accelerates_due_ness_only() { }

// 4. A transfer past expiry at the SCANNED tip is never returned, even when the ESTIMATE is huge
//    (and conversely: expiry between scanned and a huge estimate must NOT hide a transfer that is
//    unexpired at the scanned tip). (M2 regression)
#[test]
fn expiry_is_evaluated_against_scanned_tip_never_estimate() { }
```

- [ ] **Step 2: Run to verify failure**: `cd <sdk>/backend-lib && cargo test next_due` — expect compile errors (new signature) or FAIL.

- [ ] **Step 3: Implement.** In `nextDueTransferNative`:
  - Add param `estimated_tip: jlong`.
  - `let scanned_tip = target_height(&wallet)? - 1;`
  - `let effective_tip = if estimated_tip >= 0 { std::cmp::max(scanned_tip, BlockHeight::from(estimated_tip as u32)) } else { scanned_tip };`
  - After `read_reconciled`, add: `if state.is_terminal() { return NOTHING_DUE; }`.
  - Filter (replacing the current `due` filter): kind Transfer AND `t.scheduled_height() <= effective_tip` AND `state.deps_mined(t.depends_on())` AND NOT expired at **scanned** tip (`u32::from(t.expiry_height()) != 0 && u32::from(t.expiry_height()) < u32::from(scanned_tip + 1)` → excluded).
  - Among the filtered, sorted by `scheduled_height`: first with state `Proved` → build `JniPreparedTransfer` exactly as today and return `status=1`; else if any with state `Signed` → return `status=2` with its encoded id; else `status=0`.
  - Construct `JniDueTransferResult` via `env.new_object("<pkg>/JniDueTransferResult", "(ILjava/lang/String;L<pkg>/JniPreparedTransfer;)V", ...)` (add the class constant next to `JNI_PREPARED_TRANSFER`).
  - `hasOverdueTransfersNative`: add the same `estimated_tip` param; `state.next_broadcastable(tip)` call keeps its semantics but pass `effective_tip` for the schedule comparison — since `next_broadcastable` internally also checks `is_expired`, do NOT pass the estimate into it; instead replicate the schedule check: a transfer counts as overdue when `Proved && scheduled_height <= effective_tip && deps_mined && !is_expired(scanned)`. Copy the small filter rather than calling `next_broadcastable(effective_tip)`.

- [ ] **Step 4: Update the Kotlin JNI layer**: `JniDueTransferResult` class (code in Interfaces), new params threaded through `MigrationRustBackend` + `TypesafeMigrationBackend`(+Impl) (`estimatedTip: Long` parameter, `-1` sentinel documented). Fix all existing call sites by passing `-1L` for now (they migrate in Task 6).

- [ ] **Step 5: Run tests**: `cargo test next_due` → PASS; `./gradlew :backend-lib:assembleDebug -Pcoverage=false --max-workers=1` compiles.

- [ ] **Step 6: Commit** (SDK worktree): `feat: tri-state nextDueTransfer with estimated tip and terminal guard`

---

### Task 2: Rust — `rescheduleUnprovenTransferNative` (the engine-shift primitive)

**Files:**
- Modify: `<sdk>/backend-lib/src/main/rust/migration.rs` (new fn next to `debugRescheduleTransfersNative` ~line 1756)
- Modify: `MigrationRustBackend.kt`, `TypesafeMigrationBackend(.Impl).kt` (new method)
- Test: Rust test module in `migration.rs`

**Interfaces:**
- Produces:
```kotlin
suspend fun rescheduleUnprovenTransfer(
    dbDataPath: String, networkId: Int, accountUuidBytes: ByteArray, transferId: String
): Long  // new scheduled_height, or -1 if the transfer was not reschedulable (not found / not Signed / terminal)
```

- [ ] **Step 1: Write failing Rust tests:**

```rust
#[test]
fn reschedule_unproven_moves_scheduled_height_into_future_and_persists() {
    // Signed transfer due at tip; after call: persisted scheduled_height > tip,
    // expiry_height UNCHANGED, pczt bytes byte-identical, state still Signed.
}
#[test]
fn reschedule_unproven_redraws_boundary_when_candidates_exist() {
    // Funding mined long ago: new anchor_boundary is a bucket-grid multiple <= scanned tip - interval,
    // != old boundary or == old (allowed), but always Some and on-grid.
}
#[test]
fn reschedule_unproven_keeps_old_boundary_when_no_candidate() {
    // Funding mined < 2 bucket intervals before tip (empty candidate set):
    // boundary unchanged, only scheduled_height moves. (spec M4)
}
#[test]
fn reschedule_unproven_rejects_proved_and_terminal() { /* returns -1, state untouched */ }
```

- [ ] **Step 2: Run**: `cargo test reschedule_unproven` → FAIL/compile error.

- [ ] **Step 3: Implement** `rescheduleUnprovenTransferNative(db_data, network_id, account_uuid, transfer_id) -> jlong`, following `debugRescheduleTransfersNative`'s structure:
  - Open, decode id, `Backend::new`, `get_migration`; bail (−1) if none, terminal, id missing, or `state() != MigrationTxState::Signed`.
  - `let tip = target_height(&wallet)? - 1;` and `let interval = state.anchor_bucket_interval();` (the run's committed grid — mainnet 144, testnet 12; same accessor `debugRescheduleTransfersNative` uses at line ~1692).
  - New schedule: `let delay = draw_exponential_delay(&mut OsRng, u32::from(interval));` — implement locally as `(-rand::Rng::gen_range(&mut rng, f64::EPSILON..1.0).ln() * mean).round().clamp(1.0, (4 * mean) as f64) as u32` (mean = one bucket interval; cap 4× keeps testnet shifts short). `new_height = tip + delay`.
  - New boundary: compute candidate range like upstream `candidate_boundary_bounds` — lowest = first grid multiple ≥ funding height (approximate funding height as the mined height of the transfer's dependency; read it from the dep's `Mined { height }` state, else fall back to keep-old), highest = `(tip / interval) * interval - interval` (most recent settled grid point, one interval back). If `lowest > highest` → keep `tx.anchor_boundary()` unchanged; else pick a uniform random grid multiple in `[lowest, highest]` weighted toward recent is NOT required here — uniform is acceptable for a shift (document in a comment; the original draw's recency weighting applies at commit).
  - Rebuild via `MigrationTransaction::from_parts(tx.id(), tx.kind(), tx.pczt().clone(), tx.depends_on().clone(), new_height, tx.expiry_height(), new_boundary, tx.state(), tx.lock_owner())` inside the same map-over-transactions pattern as `debugRescheduleTransfersNative` lines ~1844-1861, preserving `AnchorBucketInterval` in `MigrationState::from_parts` exactly as done there (~1863-1875).
  - `replace_migration`, return `i64::from(u32::from(new_height))`.

- [ ] **Step 4: Kotlin plumbing** (`rescheduleUnprovenTransferNative` external + suspend wrapper + Typesafe pair).

- [ ] **Step 5: Run**: `cargo test reschedule_unproven` → PASS.

- [ ] **Step 6: Commit** (SDK): `feat: rescheduleUnprovenTransfer engine-shift primitive`

---

### Task 3: Rust — invalidation persistence with reason + real InvalidNote/Expired recording

**Files:**
- Modify: `<sdk>/backend-lib/src/main/rust/migration.rs` (`recordTransferResultNative` ~line 983, `derive_migration_state` ~line 420, new side-table helpers near `open_at`)
- Test: Rust test module

**Interfaces:**
- Produces Rust helpers used by Task 4:
```rust
/// Side table owned by backend-lib (NOT core's schema): created lazily.
/// CREATE TABLE IF NOT EXISTS zashi_migration_invalidation
///   (account_uuid BLOB NOT NULL PRIMARY KEY, reason TEXT NOT NULL, transfer_id TEXT)
/// reason ∈ {"invalid_transfer", "transfer_expired"}
fn record_invalidation(conn: &Connection, account: &[u8], reason: &str, transfer_id: Option<&str>) -> anyhow::Result<()>;
fn read_invalidation(conn: &Connection, account: &[u8]) -> anyhow::Result<Option<(String, Option<String>)>>;
fn clear_invalidation(conn: &Connection, account: &[u8]) -> anyhow::Result<()>;
```

- [ ] **Step 1: Failing tests:**

```rust
#[test]
fn record_transfer_result_invalid_note_marks_migration_failed_with_reason() {
    // tag=2 -> migration status Failed persisted AND side-table reason "invalid_transfer" with the transfer id;
    // derive_migration_state -> RequiresAttention(InvalidTransfer(transferId)).
}
#[test]
fn record_transfer_result_expired_marks_failed_with_expired_reason() {
    // tag=3 -> reason "transfer_expired" -> RequiresAttention(TransferExpired).
}
#[test]
fn record_transfer_result_network_error_still_noop() { /* tag=1 unchanged: state untouched */ }
#[test]
fn clear_migration_clears_invalidation_reason() { }
```

- [ ] **Step 2: Run** `cargo test record_transfer_result` → FAIL.

- [ ] **Step 3: Implement:**
  - Side-table helpers (SQL above) on the existing `store_conn`.
  - `recordTransferResultNative` tags `2 | 3`: load state, `state.set_failed()` — the engine exposes status mutation via the same path `clearMigrationNative` uses (~line 1687: reuse that mechanism); persist; then `record_invalidation(&store_conn, account_bytes, reason, Some(&transfer_id_str))`. Tag `1` stays `Ok(())`.
  - `derive_migration_state`: in the `Failed` arm, call `read_invalidation`; `"invalid_transfer"` → construct `JniAttentionReason$InvalidTransfer(transferId)` (constructor signature `(Ljava/lang/String;)V`; empty string when transfer_id is NULL), else `TransferExpired` as today. Thread `&Connection` + account bytes into `derive_migration_state`'s callers (it is called from `migrationStateNative`, which already has both).
  - `clearMigrationNative`: also `clear_invalidation`.

- [ ] **Step 4: Run** `cargo test record_transfer_result migration_state` → PASS.

- [ ] **Step 5: Commit** (SDK): `feat: persist migration invalidation with reason; real InvalidNote/Expired recording`

---

### Task 4: Rust — `reconcileInvalidatedTransfersNative` (spent-check + submit-crash probe)

**Files:**
- Modify: `<sdk>/backend-lib/src/main/rust/migration.rs` (`read_reconciled` ~line 1032, new native fn)
- Modify: `MigrationRustBackend.kt`, `TypesafeMigrationBackend(.Impl).kt`
- Test: Rust test module

**Interfaces:**
- Produces:
```kotlin
suspend fun reconcileInvalidatedTransfers(
    dbDataPath: String, networkId: Int, accountUuidBytes: ByteArray
): Boolean  // true = plan became (or already was) invalidated
```

- [ ] **Step 1: Failing tests:**

```rust
#[test]
fn reconcile_marks_proved_transfer_broadcast_when_its_txid_is_on_chain() {
    // M6: Proved transfer whose extracted txid has get_tx_height(Some) -> Broadcast+Mined, NOT invalid.
}
#[test]
fn reconcile_invalidates_when_funding_note_spent_by_foreign_tx() {
    // Signed transfer; its funding nullifier absent from unspent set; spender txid not in plan ->
    // Failed + reason "invalid_transfer" (+ that transfer's id). Returns true.
}
#[test]
fn reconcile_ignores_spends_by_the_plans_own_transactions() { /* returns false, state untouched */ }
```

- [ ] **Step 2: Run** `cargo test reconcile_` → FAIL.

- [ ] **Step 3: Implement** `reconcileInvalidatedTransfersNative` with this ORDER (order is load-bearing — the txid probe must run before the spent-check so our own crashed broadcast is not misread as a foreign spend):
  1. `read_reconciled` (existing mined-ness pass).
  2. **Submit-crash probe (M6):** for each `Proved` Transfer, extract txid exactly as `nextDueTransferNative` does (`pczt::roles::tx_extractor::TransactionExtractor::new(Pczt::parse(bytes)).extract()`, ~lines 1424-1430); on `wallet.get_tx_height(txid) == Some(h)` → `state.mark_broadcast(id, txid); state.mark_mined(id, h);` persist.
  3. **Spent-check:** for each remaining `Signed | Proved` Transfer whose deps are mined: parse its PCZT, read the single Orchard spend's note fields, reconstruct `orchard::note::Note`, derive `note.nullifier(&fvk)` with the account FVK (`backend.orchard_fvk()`, same accessor `finalizeReadyTransfersNative` uses ~line 1320). Query the wallet's unspent Orchard nullifiers via `WalletRead::get_orchard_nullifiers(NullifierQuery::Unspent)`. If the funding nullifier is NOT in the unspent set → it was spent; since step 2 already reconciled the plan's own broadcasts, this is a foreign spend → `record_invalidation(..., "invalid_transfer", Some(id))`, mark migration Failed (Task 3 mechanism), return `JNI_TRUE`.
  - Notes for the implementer: if reconstructing the Note from the PCZT proves awkward for `Signed` (pre-anchor) PCZTs, the fields are present regardless — ZIP 374 defers the anchor/witness, not the note itself (see `build_transfer_pczt` docs: "bare notes"). If `get_orchard_nullifiers` is not reachable through the `Wallet` wrapper in scope, go through the same `WalletRead` surface `min_pending_anchor_boundary`'s neighborhood uses; it is available on `zcash_client_sqlite`'s `WalletDb`.

- [ ] **Step 4: Kotlin plumbing** (native + suspend wrapper + Typesafe pair).

- [ ] **Step 5: Run** `cargo test reconcile_` → PASS. **Commit** (SDK): `feat: reconcileInvalidatedTransfers — spent-check and submit-crash probe`

---

### Task 5: SDK Kotlin — network-scaled privacy buffer + broadcast-in-flight gate + `syncToTip`

**Files:**
- Modify: `<sdk>/sdk-lib/.../internal/OrchardMigrationSdkImpl.kt` (~lines 443-456, 618-635)
- Modify: `<sdk>/sdk-lib/.../Synchronizer.kt` (~line 756)
- Test: `<sdk>/sdk-lib/src/test/...` next to existing OrchardMigrationSdk tests

**Interfaces:**
- Produces:
```kotlin
// OrchardMigrationSdkImpl companion:
val PRIVACY_SYNC_BUFFER_MAINNET = 10.minutes
val PRIVACY_SYNC_BUFFER_TESTNET = 3.minutes
internal fun privacySyncBufferFor(network: ZcashNetwork): Duration
// Broadcast-in-flight (encrypted pref key, epoch-seconds expiry):
// EncryptedPreferenceKeys.MIGRATION_BROADCAST_IN_FLIGHT_UNTIL
// Synchronizer:
suspend fun syncToTip(timeout: Duration): SyncBurstResult =
    syncBurst(timeout = timeout, isTargetReached = { false })
```

- [ ] **Step 1: Failing tests** (top-level pure function style, like the app's `MigrationWorkerTest`):

```kotlin
@Test fun `privacy buffer is 10 minutes on mainnet and 3 on testnet`() {
    assertEquals(10.minutes, privacySyncBufferFor(ZcashNetwork.Mainnet))
    assertEquals(3.minutes, privacySyncBufferFor(ZcashNetwork.Testnet))
}
@Test fun `sync is blocked while broadcast in flight mark is in the future`() {
    // pure helper: isBroadcastInFlight(nowEpoch, markEpoch) -> Boolean
    assertTrue(isBroadcastInFlight(nowEpochSeconds = 100, inFlightUntilEpochSeconds = 160))
    assertFalse(isBroadcastInFlight(nowEpochSeconds = 200, inFlightUntilEpochSeconds = 160))
}
```

- [ ] **Step 2: Run** SDK Kotlin tests → FAIL.

- [ ] **Step 3: Implement:**
  - `privacySyncBufferDuration()` returns `privacySyncBufferFor(network)`; both the post-broadcast `MIGRATION_SYNC_RESUME_AT` write in `executeNextPendingTransfer` and the buffer the app reads scale automatically (they already call `privacySyncBufferDuration()`).
  - New pref key `MIGRATION_BROADCAST_IN_FLIGHT_UNTIL`. In the broadcast path (Task 6's `executeNextPendingTransfer` rework): write `now + 120s` immediately before `broadcast(...)`, clear (write 0) right after `recordTransferResult`. `isSyncBlockedNow` gains `|| isBroadcastInFlight(now, pref)` — a stale mark self-expires in ≤2 min even after a crash.
  - `Synchronizer.syncToTip` default method (code above — `SYNCED` is already a terminal of `syncBurst`).

- [ ] **Step 4: Run** → PASS. **Commit** (SDK): `feat: network-scaled privacy buffer, broadcast-in-flight gate, syncToTip`

---

### Task 6: SDK Kotlin — `ChainTipEstimator` + tri-state `executeNextPendingTransfer` + new public surface

**Files:**
- Create: `<sdk>/sdk-lib/.../internal/ChainTipEstimator.kt`
- Modify: `<sdk>/sdk-lib/.../internal/db/derived/BlockTable.kt` (add latest-block query)
- Modify: `<sdk>/sdk-lib/.../MigrationSdk.kt` (public interface), `OrchardMigrationSdkImpl.kt`
- Test: SDK Kotlin tests

**Interfaces:**
- Produces public API (consumed by app Tasks 8–12):
```kotlin
sealed class TransferAttemptOutcome {
    object NothingDue : TransferAttemptOutcome()
    data class AwaitingProof(val transferId: String) : TransferAttemptOutcome()
    data class Executed(val result: TransferResult) : TransferAttemptOutcome()
}
interface OrchardMigrationSdk {  // additions; executeNextPendingTransfer REPLACES the old TransferResult? form
    suspend fun executeNextPendingTransfer(options: NetworkPrivacyOptions, useEstimatedTip: Boolean): TransferAttemptOutcome
    suspend fun hasOverdueTransfers(useEstimatedTip: Boolean = false): Boolean
    suspend fun rescheduleUnprovenTransfer(transferId: String): Long
    suspend fun reconcileInvalidations(): Boolean
    suspend fun estimatedChainTip(): Long   // -1 when no scanned block yet
}
internal fun interface ChainTipEstimator { suspend fun estimatedTip(): Long }
internal fun estimateTip(scannedHeight: Long, scannedBlockTimeEpochSeconds: Long, nowEpochSeconds: Long): Long =
    scannedHeight + ((nowEpochSeconds - scannedBlockTimeEpochSeconds).coerceAtLeast(0L) / 75L)
```

- [ ] **Step 1: Failing tests:**

```kotlin
@Test fun `estimateTip floors elapsed over 75s`() { assertEquals(1004, estimateTip(1000, 0, 74 * 5)) } // 370/75 = 4
@Test fun `estimateTip clamps negative elapsed`() { assertEquals(1000, estimateTip(1000, 500, 400)) }
```

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement:**
  - `BlockTable.findLatestBlock(): DbBlock?` — copy `findBlockByExpiryHeight` (lines 31-52) with `orderBy = "height DESC", limit = 1`, no selection.
  - `ChainTipEstimatorImpl(blockTable, clock)`: `estimatedTip() = findLatestBlock()?.let { estimateTip(it.height.value, it.blockTimeEpochSeconds, clock.now().epochSeconds) } ?: -1L`. Wire into `OrchardMigrationSdkImpl` construction (it has DB access already via the derived-db layer that serves the existing table objects).
  - `executeNextPendingTransfer(options, useEstimatedTip)`: compute `est = if (useEstimatedTip) chainTipEstimator.estimatedTip() else -1L`; `wasOverdue = migrationBackend.hasOverdueTransfers(..., est)`; `when (val due = migrationBackend.nextDueTransfer(..., est))` → `status 0` → `NothingDue`; `status 2` → `AwaitingProof(due.awaitingProofTransferId!!)`; `status 1` → set in-flight mark (Task 5) → extract/broadcast/record exactly as the current body (lines 409-439) → clear mark → `Executed(mapped.transferResult)`. Post-broadcast `MIGRATION_SYNC_RESUME_AT` write unchanged.
  - `rescheduleUnprovenTransfer` / `reconcileInvalidations` / `hasOverdueTransfers(useEstimatedTip)` delegate to the Task 2/4/1 backends (gate path `isSyncBlockedNow` keeps passing `-1`).
  - **Delete `rescheduleOverdueTransfer()`** (the non-persisting units-bug stub, lines 467-487) from interface + impl. Its one caller, app-side `MigrationProgressVM.onReschedule`, is migrated in Task 12 — expect the app build to break between Task 6 and Task 12 only at that call site; that is acceptable within this plan's sequence because app tasks compile against the SDK last.
  - Update mock/fake implementations of `OrchardMigrationSdk` in SDK + app test sources to the new surface (compile-driven).

- [ ] **Step 4: Run** SDK Kotlin tests → PASS. **Commit** (SDK): `feat: ChainTipEstimator and tri-state executeNextPendingTransfer`

---

### Task 7: App — `LastNetworkActivityStore` + shift-counter store

**Files:**
- Create: `ui-lib/.../ui/common/provider/LastNetworkActivityStorageProvider.kt`
- Create: `ui-lib/.../ui/common/provider/MigrationShiftCounterStorageProvider.kt`
- Modify: `ui-lib/.../di/ProviderModule.kt` (~line 138 area)
- Test: `ui-lib/src/test/.../provider/MigrationShiftCounterStorageProviderTest.kt` (pure-logic parts only)

**Interfaces:**
- Produces:
```kotlin
interface LastNetworkActivityStorageProvider {           // wallet-global, NOT per-account
    suspend fun stampNow()
    suspend fun get(): Instant?                          // null = never
}
interface MigrationShiftCounterStorageProvider {
    /** Returns the updated consecutive count for (accountKeyId, transferId); resets when transferId differs. */
    suspend fun incrementIfSameTransfer(accountKeyId: String, transferId: String, syncCompletedSinceLastShift: Boolean): Int
    suspend fun reset(accountKeyId: String)
    suspend fun lastShiftAt(accountKeyId: String): Instant?
}
```
Semantics (spec §2.B.4): `incrementIfSameTransfer` stores `(transferId, count, shiftAtEpoch)`; a different transferId → count = 1 if `syncCompletedSinceLastShift` else 0-but-stored; same id → `count + 1` only when `syncCompletedSinceLastShift`, else count unchanged. It always updates `shiftAtEpoch = now`.

- [ ] **Step 1: Failing test** for the pure decision (extract as top-level `internal fun nextShiftCount(previousTransferId: String?, previousCount: Int, transferId: String, syncCompletedSinceLastShift: Boolean): Int`):

```kotlin
@Test fun `count increments only for same transfer with a completed sync since last shift`() {
    assertEquals(2, nextShiftCount("t1", 1, "t1", syncCompletedSinceLastShift = true))
    assertEquals(1, nextShiftCount("t1", 1, "t1", syncCompletedSinceLastShift = false))
    assertEquals(1, nextShiftCount("t1", 3, "t2", syncCompletedSinceLastShift = true))
}
```

- [ ] **Step 2: Run** app tests → FAIL. **Step 3:** Implement both providers following `IsMigrationTorEnabledStorageProvider`'s `StandardPreferenceProvider` + `PreferenceKey` pattern (keys: `"last_network_activity_epoch"`, `"migration_shift_${'$'}accountKeyId"` storing `"transferId|count|epoch"`). Register in `ProviderModule` with `singleOf`. **Step 4:** tests PASS. **Step 5: Commit** (app): `feat: last-network-activity and shift-counter stores`

---

### Task 8: App — Lane A: `MigrationSyncWorker` + `MigrationSyncScheduler`

**Files:**
- Create: `ui-lib/.../work/MigrationSyncWorker.kt`
- Create: `ui-lib/.../work/MigrationSyncScheduler.kt`
- Modify: `ui-lib/.../work/WorkIds.kt` (add `WORK_ID_MIGRATION_SYNC = "co.electriccoin.zcash.migration_sync"`)
- Modify: `ui-lib/.../di/ProviderModule.kt` (`factoryOf(::MigrationSyncScheduler)`)
- Test: `ui-lib/src/test/.../work/MigrationSyncWorkerTest.kt`

**Interfaces:**
- Consumes: `OrchardMigrationSdk.estimatedChainTip()/getMigrationTransferStates()/finalizeReadyTransfers()/reconcileInvalidations()/isSyncBlocked()` (Tasks 4–6), `Synchronizer.syncToTip` (Task 5), `LastNetworkActivityStorageProvider` (Task 7).
- Produces top-level pure functions (tested):
```kotlin
internal enum class LaneARunDecision { RUN, SKIP_NEAR_DUE, SKIP_GATE_BLOCKED }
internal fun decideLaneARun(nowEpochSeconds: Long, nextEstimatedDueEpochSeconds: Long?, privacyBufferSeconds: Long, isGateBlocked: Boolean): LaneARunDecision
internal fun laneAReArmDelay(decision: LaneARunDecision, nowEpochSeconds: Long, nextEstimatedDueEpochSeconds: Long?, privacyBufferSeconds: Long, cadenceSeconds: Long, jitterSeconds: Long, random: kotlin.random.Random): Duration
```
`decideLaneARun`: `SKIP_GATE_BLOCKED` when gate blocked; `SKIP_NEAR_DUE` when `nextEstimatedDue != null && now >= nextEstimatedDue - buffer`; else `RUN`. `laneAReArmDelay`: for `SKIP_NEAR_DUE` → `max(nextDue + buffer − now, MIN_BACKOFF 60s)`; otherwise `cadence ± random jitter`.

- [ ] **Step 1: Failing tests** (mirror `MigrationWorkerTest` style):

```kotlin
@Test fun `lane A skips inside the pre-due window`() {
    assertEquals(LaneARunDecision.SKIP_NEAR_DUE, decideLaneARun(1000, nextEstimatedDueEpochSeconds = 1500, privacyBufferSeconds = 600, isGateBlocked = false))
}
@Test fun `lane A re-arm after past-due skip never goes negative`() {
    val d = laneAReArmDelay(LaneARunDecision.SKIP_NEAR_DUE, nowEpochSeconds = 2000, nextEstimatedDueEpochSeconds = 1500, privacyBufferSeconds = 300, cadenceSeconds = 3600, jitterSeconds = 600, random = Random(1))
    assertTrue(d >= 60.seconds) // hot-loop guard (spec M5)
}
@Test fun `lane A runs when no transfer is near due`() {
    assertEquals(LaneARunDecision.RUN, decideLaneARun(1000, 5000, 600, isGateBlocked = false))
}
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** the pure functions + the worker:

```kotlin
@Keep
class MigrationSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase by inject()
    private val synchronizerProvider: SynchronizerProvider by inject()
    private val lastNetworkActivity: LastNetworkActivityStorageProvider by inject()
    private val migrationNotifier: MigrationNotifier by inject()

    override suspend fun doWork(): Result {
        val accountKeyId = inputData.getString(MigrationScheduler.KEY_ACCOUNT_KEY_ID) ?: return Result.success()
        val sdk = getOrchardMigrationSdk(accountKeyId) ?: return Result.success()
        val states = sdk.getMigrationTransferStates() // live scheduled_heights (NOT the plan cache — spec M5)
        if (states == null) return Result.success()   // no migration -> stop re-arming
        val est = sdk.estimatedChainTip()
        val nextDueEpoch = nextEstimatedDueEpochSeconds(states, est) // helper: min over pending transfers of now + (scheduledHeight - est) * 75
        val decision = decideLaneARun(nowEpochSeconds(), nextDueEpoch, sdk.privacySyncBufferDuration().inWholeSeconds, isGateBlocked = sdk.isSyncBlocked().first())
        if (decision == LaneARunDecision.RUN) {
            synchronizerProvider.getSynchronizerOrNull()?.syncToTip(timeout = LANE_A_SYNC_TIMEOUT)
            val proved = sdk.finalizeReadyTransfers()
            Twig.debug { "MIGRATION_DIAG LaneA: proved=$proved" }
            if (sdk.reconcileInvalidations()) {
                migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
                MigrationScheduler(applicationContext).cancel(accountKeyId)   // cancel Lane B (spec m2)
                return Result.success()                                        // and do NOT re-arm Lane A
            }
            lastNetworkActivity.stampNow()
        }
        MigrationSyncScheduler(applicationContext).schedule(accountKeyId, laneAReArmDelay(decision, nowEpochSeconds(), nextDueEpoch, sdk.privacySyncBufferDuration().inWholeSeconds, laneACadence().inWholeSeconds, LANE_A_JITTER.inWholeSeconds, Random))
        return Result.success()
    }
}
internal val LANE_A_SYNC_TIMEOUT = 3.minutes
internal val LANE_A_JITTER = 10.minutes
internal fun laneACadence(): Duration = if (BuildConfig.FLAVOR.contains("testnet", ignoreCase = true)) 5.minutes else 60.minutes
```
  `MigrationSyncScheduler`: clone of `MigrationScheduler` (lines 35-61 pattern) with `WORK_ID_MIGRATION_SYNC` unique-work name (wallet-global: do NOT suffix with account), `ExistingWorkPolicy.REPLACE`, no due-alarm. `cancel()` cancels the unique work.

- [ ] **Step 4: Run** app tests → PASS (worker body itself is exercised on the emulator later; unit scope = pure functions). **Step 5: Commit** (app): `feat: Lane A migration sync worker`

---

### Task 9: App — Lane B: slim `MigrationWorker` to broadcast-only tri-state

**Files:**
- Modify: `ui-lib/.../work/MigrationWorker.kt` (full decision core)
- Modify: `ui-lib/src/test/.../work/MigrationWorkerTest.kt`
- Test: same file

**Interfaces:**
- Consumes: `TransferAttemptOutcome` (Task 6), stores (Task 7), `MigrationSyncScheduler` (Task 8).
- Produces top-level pure function replacing `decideNullResultAction`:
```kotlin
internal enum class LaneBAction { BROADCAST, DEFER_OVERLAP, SHIFT, NOTHING }
internal fun decideLaneBPreflight(
    laneARunning: Boolean, synchronizerSyncing: Boolean,
    nowEpochSeconds: Long, lastNetworkActivityEpochSeconds: Long?, privacyBufferSeconds: Long,
): LaneBAction  // DEFER_OVERLAP if any source live or gap unmet, else BROADCAST (meaning: proceed to the SDK call)
```

- [ ] **Step 1: Rewrite tests first.** Delete tests for `decideNullResultAction`, `isBroadcastableAfterBurst`, `rescheduleDelayAfterSyncBurst`. Add:

```kotlin
@Test fun `lane B defers while lane A is running`() {
    assertEquals(LaneBAction.DEFER_OVERLAP, decideLaneBPreflight(laneARunning = true, synchronizerSyncing = false, nowEpochSeconds = 1000, lastNetworkActivityEpochSeconds = 0, privacyBufferSeconds = 600))
}
@Test fun `lane B defers inside the quiet gap`() {
    assertEquals(LaneBAction.DEFER_OVERLAP, decideLaneBPreflight(false, false, nowEpochSeconds = 1000, lastNetworkActivityEpochSeconds = 700, privacyBufferSeconds = 600))
}
@Test fun `lane B proceeds when all sources quiet past the gap`() {
    assertEquals(LaneBAction.BROADCAST, decideLaneBPreflight(false, false, 1000, 100, 600))
}
@Test fun `lane B proceeds when no sync ever happened`() {
    assertEquals(LaneBAction.BROADCAST, decideLaneBPreflight(false, false, 1000, null, 600))
}
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** New `doWork()` skeleton (replaces lines 45-205; keep account/sdk resolution lines 46-54, Tor flag read, `executeWithRetries`, all Success/NetworkError/Tor/notification handling verbatim where marked):

```kotlin
override suspend fun doWork(): Result {
    val accountKeyId = /* unchanged lines 46-49 */
    val sdk = getOrchardMigrationSdk(accountKeyId) ?: return Result.success()
    val laneARunning = WorkManager.getInstance(applicationContext)
        .getWorkInfosForUniqueWork(WorkIds.WORK_ID_MIGRATION_SYNC).await()
        .any { it.state == WorkInfo.State.RUNNING }
    val syncing = synchronizerProvider.synchronizer.value?.status?.value == Synchronizer.Status.SYNCING
    val preflight = decideLaneBPreflight(laneARunning, syncing, nowEpochSeconds(), lastNetworkActivity.get()?.epochSeconds, sdk.privacySyncBufferDuration().inWholeSeconds)
    if (preflight == LaneBAction.DEFER_OVERLAP) {
        // Local delay (spec §5): engine untouched.
        MigrationScheduler(applicationContext).schedule(accountKeyId, sdk.privacySyncBufferDuration())
        return Result.success()
    }
    val useTor = isMigrationTorEnabledStorageProvider.get(accountKeyId)
    return when (val outcome = executeWithRetries { sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor), useEstimatedTip = true) }) {
        is TransferAttemptOutcome.NothingDue -> {
            // Not due yet by estimate: re-arm for the live next window (states-based, like Lane A).
            scheduleForNextLiveWindow(accountKeyId, sdk)   // private helper; falls back to plan repo scheduledAt
            Result.success()
        }
        is TransferAttemptOutcome.AwaitingProof -> {
            val syncSince = lastNetworkActivity.get()?.let { it > (shiftCounter.lastShiftAt(accountKeyId) ?: Instant.DISTANT_PAST) } == true
            val count = shiftCounter.incrementIfSameTransfer(accountKeyId, outcome.transferId, syncCompletedSinceLastShift = syncSince)
            val newHeight = sdk.rescheduleUnprovenTransfer(outcome.transferId)
            if (count == SHIFT_ESCALATION_THRESHOLD) {
                if (sdk.reconcileInvalidations()) migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
                else migrationNotifier.notifyManualConfirmationRequired(accountKeyId, 0, 0)  // once; counter keeps it single
            }
            scheduleForNextLiveWindow(accountKeyId, sdk)
            Twig.debug { "MIGRATION_DIAG LaneB: shifted ${outcome.transferId} to $newHeight (count=$count)" }
            Result.success()
        }
        is TransferAttemptOutcome.Executed -> when (val result = outcome.result) {
            is TransferResult.Success -> { shiftCounter.reset(accountKeyId); /* existing Success branch lines 144-164 verbatim */ }
            is TransferResult.NetworkError -> { /* existing branch lines 165-186 verbatim */ }
            TransferResult.InvalidNote -> { /* existing branch lines 187-194 verbatim */ }
            TransferResult.Expired -> { /* existing branch lines 195-203 verbatim */ }
        }
        null -> Result.success() // executeWithRetries exhausted on retryable network errors mid-outcome
    }
}
private const val SHIFT_ESCALATION_THRESHOLD = 3
```
  - `executeWithRetries` is retyped to `TransferAttemptOutcome?` (retry only while `Executed(NetworkError(retryable=true))`).
  - `scheduleForNextLiveWindow(accountKeyId, sdk)`: read `sdk.getMigrationTransferStates()`; next pending transfer's `scheduledHeight` → delay = `(scheduledHeight − sdk.estimatedChainTip()).coerceAtLeast(1) * 75.seconds`; fallback: plan-repo `nextPending.scheduledAt − now`.
  - Delete `isBroadcastableAfterBurst`, `rescheduleDelayAfterSyncBurst`, `NullResultAction`, `decideNullResultAction`, `SYNC_BURST_TIMEOUT`, and the burst branch. New injections: `lastNetworkActivity`, `shiftCounter`.

- [ ] **Step 4: Run** `./gradlew :ui-lib:testZcashtestnetFossDebugUnitTest -Pcoverage=false --max-workers=1` → PASS. **Step 5: Commit** (app): `feat: Lane B is broadcast-only — tri-state decisions, engine shift, no sync burst`

---

### Task 10: App — foreground hook + `SyncWorker` no-op during migration + `MigrationSendingVM` weld removal

**Files:**
- Create: `ui-lib/.../ui/common/usecase/OnMigrationSyncCompletedUseCase.kt`
- Modify: `ui-lib/.../work/SyncWorker.kt` (doWork, lines 51-71)
- Modify: `ui-lib/.../ui/screen/migration/sending/MigrationSendingVM.kt` (sendOnce, lines 105-157)
- Modify: `ui-lib/.../di/UseCaseModule.kt`
- Test: `ui-lib/src/test/.../usecase/OnMigrationSyncCompletedUseCaseTest.kt`

**Interfaces:**
- Produces:
```kotlin
/** Foreground Lane-A equivalent: call on every Status.SYNCED transition while a migration is active. */
class OnMigrationSyncCompletedUseCase(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val lastNetworkActivity: LastNetworkActivityStorageProvider,
    private val migrationNotifier: MigrationNotifier,
) { suspend operator fun invoke(accountKeyId: String) { ... } }
```

- [ ] **Step 1: Failing test** (fake sdk): SYNCED completion calls `finalizeReadyTransfers` then `reconcileInvalidations`, stamps activity; on invalidation → notifies + does not stamp-skip. Follow existing use-case test patterns in `ui-lib/src/test/.../usecase/`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement:**
  - Use case body: `sdk.finalizeReadyTransfers(); if (sdk.reconcileInvalidations()) migrationNotifier.notifyMigrationPlanInvalid(accountKeyId); lastNetworkActivity.stampNow()`.
  - Wire: in `SynchronizerProvider`'s status observation site (the `.combine(progress)` collector found in SynchronizerProviderImpl lines 54-81), on transition to `Status.SYNCED` and `migrationPlanRepository.load() != null`, launch the use case. Keep it single-fire per transition (`distinctUntilChanged` on status).
  - `SyncWorker.doWork()` first line: `if (migrationPlanRepository.load() != null) { Twig.debug { "BG Sync: migration active — Lane A supersedes, skipping." }; return Result.success() }` (inject repo via `KoinComponent` like MigrationWorker does). Non-migration path also calls `lastNetworkActivity.stampNow()` before returning success.
  - `MigrationSendingVM.sendOnce`: replace the weld (`sdk.finalizeReadyTransfers(); result = sdk.executeNextPendingTransfer(...)`) with `val outcome = sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor), useEstimatedTip = true)`; map `Executed(r)` to the existing branches, `NothingDue`/`AwaitingProof` to the existing `SendFailure.NotReady` path (foreground sync + the hook will prove; the retry loop's `attempt` semantics keep polling as today).

- [ ] **Step 4: Run** app tests → PASS. **Step 5: Commit** (app): `feat: foreground prove hook; SyncWorker defers to Lane A; Sending weld removed`

---

### Task 11: App — lifecycle wiring + app-open catch-up (at-most-one-overdue)

**Files:**
- Modify: `ui-lib/.../ui/common/usecase/FinalizeMigrationScheduleUseCase.kt` (lines 38-45)
- Modify: `ui-lib/.../ui/common/usecase/CheckMigrationRecoveryUseCase.kt`
- Modify: `ui-lib/.../ui/screen/migration/progress/MigrationProgressVM.kt` (`onReschedule`)
- Modify: cancellation sites: wherever `MigrationScheduler(...).cancel` / `clearMigration()` is called (search `clearMigration(`) — add `MigrationSyncScheduler.cancel()`
- Test: `ui-lib/src/test/.../usecase/CheckMigrationRecoveryUseCaseTest.kt` (extend existing if present, else new for the pure catch-up function)

**Interfaces:**
- Produces:
```kotlin
internal fun overdueTransfersToShift(pendingSortedByHeight: List<String /* transferId */>): List<String> =
    pendingSortedByHeight.drop(1)  // keep the earliest, shift the rest (spec §5, B2)
```

- [ ] **Step 1: Failing test:** `overdueTransfersToShift(listOf("a","b","c")) == listOf("b","c")`; empty and single-element lists → empty.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement:**
  - `FinalizeMigrationScheduleUseCase.invoke`: after the existing `migrationScheduler.schedule(...)`, add `migrationSyncScheduler.schedule(accountKeyId, delay = laneACadence())` — Lane A starts with the plan.
  - `CheckMigrationRecoveryUseCase`: (a) new early step — if a plan exists and Lane A unique work is absent, re-schedule it (reconciliation); (b) in the `hasOverdueTransfers()` branch, BEFORE routing: read tri-state repeatedly? No — read `getMigrationTransferStates()`, collect pending transfers with `scheduledHeight <= tipHeight` sorted ascending, and for `overdueTransfersToShift(...)` call `sdk.rescheduleUnprovenTransfer(id)` for Signed ones (Proved ones keep — they are the single offered transfer's backlog only if earliest; for multiple Proved overdue, shift all but the first via the same primitive? `rescheduleUnprovenTransfer` rejects Proved (Task 2) — for Proved extras simply leave them: the engine will offer them one-per-broadcast with the existing post-broadcast buffer; note this in a comment as the accepted residual until a core primitive exists). Then route as today.
  - `MigrationProgressVM.onReschedule`: replace the `rescheduleOverdueTransfer()` call with: read next pending id from `getMigrationTransferStates()` → `sdk.rescheduleUnprovenTransfer(id)` → recompute the WorkManager delay from the returned height (`(newHeight − sdk.estimatedChainTip()) * 75.seconds`) → `migrationScheduler.schedule(accountKeyId, delay)` + plan-repo `rescheduleTransfer(index, epochSeconds)` write-through.
  - Cancellation sites (`clearMigration`, migration-complete handling, invalidation in Tasks 8/10): add `MigrationSyncScheduler(context).cancel()`.

- [ ] **Step 4: Run** app tests → PASS. **Step 5: Commit** (app): `feat: lane lifecycle wiring and app-open at-most-one-overdue catch-up`

---

### Task 12: App — copy changes + commit-time no-background hint

**Files:**
- Modify: `ui-lib/.../ui/common/model/migration/MigrationDurationFormat.kt` (lines 11-18)
- Modify: `ui-lib/.../ui/screen/migration/scheduled/MigrationScheduledVM.kt` + `MigrationScheduledScreen.kt`
- Test: existing `MigrationDurationFormat` tests if present, else visual check on emulator (Task 13)

- [ ] **Step 1:** `formatMigrationDuration` already prefixes `~` — verify and extend the scheduled screen instead: add to `MigrationScheduledState` a `backgroundHint: StringResource?`; VM sets it when `!isBackgroundExecutionAvailableProvider.isAvailable()` → `stringRes("Transfers run when you open the app — enable background activity in Settings for automatic sending.")`; Screen renders it below the summary rows when non-null. Progress-screen times are computed from live states already (withLiveState) — only ensure the label copy says "approx." where an absolute time renders (search `scheduledLabel` in `ui/common/model/migration/` and prefix the formatted time with `"~"` if not already).
- [ ] **Step 2:** Run `./gradlew :ui-lib:compileZcashtestnetFossDebugKotlin -Pcoverage=false --max-workers=1` → compiles; unit tests still PASS.
- [ ] **Step 3: Commit** (app): `feat: estimate copy and no-background hint on scheduled screen`

---

### Task 13: Emulator verification (project practice — testnet/foss/debug)

**Files:** none (verification only; fixes fold back into the task that owns them)

- [ ] **Step 1:** Build + install: `./gradlew :app:installZcashtestnetFossDebug -Pcoverage=false --max-workers=1`; launch on the emulator.
- [ ] **Step 2:** Run a full AUTOMATIC testnet migration; background the app after commit. Watch `adb logcat -s Twig | grep MIGRATION_DIAG` for: Lane A runs ~every 5 min; `finalizeReadyTransfers` proving ahead of windows; Lane B broadcasts inside estimated windows with **no sync within ±3 min of any broadcast**; post-broadcast buffer honored.
- [ ] **Step 3:** Kill Lane A (`adb shell` → cancel via app debug menu or `WorkManager` cancel in a debug hook) → verify silent shifts (`LaneB: shifted ... (count=...)`) and that counts stay 0/low while no sync runs (counter counts only synced-but-unprovable — spec §2.B.4).
- [ ] **Step 4:** Spend a funding note externally (second device / zcash-cli against the same seed) → verify Lane A `reconcileInvalidations` → notification → `invalid/` routing on open.
- [ ] **Step 5:** Force-stop mid-broadcast (`adb shell am force-stop` timed after a `LaneB` broadcast log, before record) → reopen → verify the reconcile probe marks it Broadcast instead of re-submitting.
- [ ] **Step 6:** Permission-matrix spot checks: revoke notifications (runs still progress); enable battery Restricted (nothing runs; app-open drives + `transferreview/` shows).
- [ ] **Step 7:** Fix anything found (each fix commits into the owning task's area), then final commit (app): `chore: emulator verification notes for two-lane migration`

---

## Self-review notes

- Spec coverage: §2 (Tasks 8, 9), §3 (Tasks 7, 8, 9), §4 (Tasks 1, 6), §5 (Tasks 2, 9, 11), §6 (Tasks 3, 4, 8, 10), §7.1-7.11 (Tasks 1-6), §8 (Tasks 7-11), §9 (Tasks 11, 12), §10 boot-receiver marked optional — deliberately NOT planned (nice-to-have, spec §8); §11 (constants in Tasks 5, 8; bucketing already merged), §12 (test steps inside every task + Task 13).
- The old `rescheduleOverdueTransfer` deletion (Task 6) intentionally breaks the app build until Task 11's `MigrationProgressVM` migration — tasks must land in order.
- `AttentionReason.SyncRequiredBeforeNext` untouched, per spec §9.
