# Orchard→Ironwood migration: open points as of 2026-07-18

## Context

This is a consolidated punch-list of everything left open across the sign-now/prove-later
implementation, the delivery-mode unification, and today's live-testnet testing session. It exists
so nothing found today gets lost between sessions — each item below was identified but not yet
fixed (or was explicitly deferred by product decision). Severity/priority is noted per item; none
of these block the currently-shipped behavior, but several are real gaps a longer testnet run or a
production rollout would eventually hit.

## Open items

### 1. No timeout/escalation if a note-split (or any transfer) never confirms

`MigrationWorker`'s "not ready yet" branch (fixed today to stop silently stalling) now retries every
`ZcashSdk.BLOCK_INTERVAL_MILLIS` (~75s) indefinitely. If the underlying transaction genuinely never
confirms — dropped from mempool, replaced, or otherwise stuck — nothing ever notices: the Rust engine
only tracks invalidity/expiry for *migration transfers*, never for the note-split itself
(`MigrationContext::record_transfer_result`'s doc comment: "a prep transaction is never itself
reported as invalid or expired"), so `hasInvalidTransfers()`/`hasOverdueTransfers()` both stay false
and `CheckMigrationRecoveryUseCase` never redirects the user. The retry loop just spins forever,
silently.

**Needs a design decision**, not just a fix: either a Rust-side staleness/expiry concept for the
note-split, or a simpler Kotlin-side elapsed-time check (e.g. against `MigrationPlan.createdAtEpochSeconds`)
that eventually fires a one-time "this is taking unusually long" notification. Deferred pending that
decision — this is a rare edge case (splits essentially always confirm), not urgent.

**Files:** `ui-lib/.../work/MigrationWorker.kt`, `librustzcash/zcash_pool_migration/src/context.rs`
(`record_transfer_result`, `has_invalid_transfers`).

### 2. Transient "Overdue" flash right after confirming an AUTOMATIC plan

`delayUntilFirstTransfer`'s estimate for transfer #0 is effectively zero (the Rust schedule builder
pins `anchor_height == target_height` for the first transfer, `first_delay_blocks = 0` always). A
few seconds after confirming, `MigrationProgressVM.hasOverdue` (a raw wall-clock check against that
near-zero `scheduledAtEpochSeconds` estimate) can read `true` before the background worker has
actually had a chance to run, showing "Resume Migration / Send Now" prematurely. Tapping Send Now in
that window calls `executeNextPendingTransfer()`, gets `null`, and silently no-ops — confusing but
harmless (no double-send risk, no crash).

Low priority/cosmetic. Not fixed.

**Files:** `ui-lib/.../screen/migration/progress/MigrationProgressVM.kt`.

### 3. No real duplicate-navigation guard on the Migration Review screen

`NavigationRouter.navigateWithBackoff()` only dedupes an identical navigation command within a
500ms window (`job?.isActive == true && command == lastNavCommand`) — it is a debounce, not true
idempotency. A slower duplicate `forward(MigrationReviewArgs(...))` (more than 500ms after the
first) would push a second back-stack entry, spin up a second `MigrationReviewVM` instance, and
re-run its `init{}` proposal call (`proposeMigrationTransfers()`/`proposeImmediateMigration()`)
redundantly. Not confirmed to have caused today's specific glitch (see item 4, which is the more
likely explanation), but is a real, unguarded gap independent of it.

**Files:** `ui-lib/.../NavigationRouter.kt`, `ui-lib/.../screen/migration/review/MigrationReviewVM.kt`.

### 4. No sync-readiness gate anywhere in the migration entry flow

Confirmed by grep: zero references to `Synchronizer.Status`/`SYNCED`/scan-progress anywhere under
`ui-lib/.../screen/migration/` — not in `MigrationSetupVM`, not in `MigrationReviewVM`, not
anywhere along Setup → How This Works → Privacy → Battery → Notification → Review. `MigrationSetupVM`
reads the current Orchard balance (`GetOrchardBalanceUseCase`) unconditionally, and
`MigrationReviewVM.init{}` calls `proposeMigrationTransfers()`/`proposeImmediateMigration()`
unconditionally the moment the VM is created — regardless of whether the wallet has actually
finished syncing.

This is the most likely explanation for today's live glitch: `propose_migration_transfers` logged
`total_spendable=0` (`MIGRATION_DIAG` trace) at the exact tail of a large historical rescan, while
`getWalletSummary` in the same moment reported the correct non-zero balance — consistent with the
Rust engine's own anchor/checkpoint bookkeeping not having fully settled yet, even though the SDK's
own scan-progress counter had already ticked to 1.0. Backing out of the screen and re-entering (which
recreates the VM and re-fires the propose call) resolved it on retry.

The app already has an established pattern for this exact class of guard —
`WalletSnapshotRepositoryImpl` waits for `Synchronizer.Status.SYNCED` (combined with the wallet's
restoring-state) before treating wallet data as authoritative. The migration flow doesn't reuse it
anywhere.

**Not yet planned in detail** — needs its own design pass (where exactly to gate: Setup entry,
Review entry, or both; what to show while waiting; whether to just disable Confirm until synced or
block navigation entirely).

**Files:** `ui-lib/.../screen/migration/setup/MigrationSetupVM.kt`,
`ui-lib/.../screen/migration/review/MigrationReviewVM.kt`,
`ui-lib/.../repository/WalletSnapshotRepository.kt` (the pattern to reuse).

### 5. `MigrationTransferReviewScreen` is now unreferenced

Removed from all routing (`CheckMigrationRecoveryUseCase`, `HomeVM`) as part of today's
delivery-mode unification, since its only prior use was the now-retired MANUAL-overdue routing.
Left in place (screen/VM/args/nav-graph registration all intact) rather than deleted, per explicit
direction, in case it has a future role. **What that future role should be, if any, is undecided.**

**Files:** `ui-lib/.../screen/migration/transferreview/*`.

### 6. Duplicated migration duration/span calculation logic across 4 VMs

Found by an earlier subagent review: `MigrationReviewVM.kt`, `MigrationProgressVM.kt`,
`MigrationScheduledVM.kt`, and `MigrationCompleteVM.kt` each independently compute a duration/span
from block heights, using two different reference points across the four. Explicitly deferred by
product decision ("if we have tests on the Rust side then that's enough, because it controls
everything") — not fixed, not currently planned.

### 7. Keystone/external-signer migration path is incomplete

Pre-existing, not something today's work touched: `MigrationReviewVM.confirmAutomatic()` skips the
note-split step entirely for `KeystoneAccount` (the split submission for Keystone doesn't exist
yet — marked mock/TODO in `MigrationKeystoneSignVM.kt`), and the external-signer PCZT path
(`create_unsigned_transfer_pczts`) still proves eagerly on the Rust side, never migrated to the
sign-now/prove-later placeholder-witness scheme the software-signing path uses. Keystone migration
therefore still requires the wallet's existing notes to already fund the schedule exactly.

**Files:** `ui-lib/.../screen/migration/keystonesign/MigrationKeystoneSignVM.kt`,
`librustzcash/zcash_pool_migration/src/context.rs` (`create_unsigned_transfer_pczts`).

### 8. No dedicated Kotlin test coverage for `MigrationWorker`/`MigrationReviewVM`

Explicitly accepted, not a gap needing action right now — product decision was that Rust-side test
coverage (104 unit + 12 e2e tests in `zcash_pool_migration`) is sufficient since Rust drives the
actual migration logic; Kotlin is thinner orchestration/UI. Noted here only so it isn't silently
re-litigated — the one caveat surfaced during this session is that the two real Kotlin-only bugs
found today (block-height/epoch-seconds conflation, missing worker reschedule) were entirely in that
"thinner" Kotlin layer and wouldn't have been caught by Rust tests regardless.

### 9. `TransactionSubmitResult → TransferResult` mapping heuristic is untested against real rejections

Carried over from the original JNI-wiring plan (now implemented): the mapping from lightwalletd's
`TransactionSubmitResult` (`Success`/`Failure(grpcError, code, description)`/`NotAttempted`) to the
public `TransferResult` (`InvalidNote` vs `Expired` vs `NetworkError`) is a heuristic — checking
chain tip against `expiryHeight` first, then pattern-matching `code`/`description` for
double-spend/missing-input signals — since lightwalletd has no dedicated expired/invalid-note
signal. This still has no unit tests against representative real-world rejection codes/descriptions,
because none have been observed on testnet yet. Flagged as a follow-up once real rejections are
seen, not a current blocker.

**Files:** wherever this mapping lives in `OrchardMigrationSdkImpl.kt` (sdk-lib).
