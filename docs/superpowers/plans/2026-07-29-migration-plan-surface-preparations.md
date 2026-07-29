# Surface note-split preparations in the migration plan — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carry note-split (preparation) transactions through the propose-time migration schedule so the UI shows `Split balance 1..N` above `Transfer 1..M`, the Lane B/due-alarm initial arm targets the earliest not-yet-broadcast step, and app-open auto-navigation to Progress is removed.

**Architecture:** One additive `preparations` channel: Rust `encode_migration_schedule` → JNI `JniMigrationSchedule.preparations` → Kotlin `MigrationSchedule.preparations` → app `MigrationPlan.preparations` → UI. Runtime execution (Lane A/B) is untouched — it already reads the engine's kind-agnostic runtime next-due. Two coupled fixes (initial arm, auto-nav) ride along.

**Tech Stack:** Rust hand-written JNI (backend-lib), Kotlin SDK (sdk-lib), Kotlin/Compose (ui-lib), Koin, JUnit/MockK.

## Global Constraints

- Additive only: `MigrationSchedule.transfers` / `MigrationPlan.transfers` stay crossings-only; preparations live in a NEW parallel list. No existing `transfers` consumer changes semantics.
- Headline/user-facing count = crossings (`transfers.size`), never crossings+preparations.
- No execution-path changes (Lane A/B runtime loops out of scope).
- Anchor boundaries are NOT in the propose schedule (preparations use natural anchor). Model carries broadcast heights + dependency ids only.
- Two repos, same branch `feature/ironwood-slipstream`: app = this worktree; SDK = `../zcash-android-wallet-sdk`.
- Build/verify: `export PATH="$HOME/.cargo/bin:$PATH"` then gradle with `-Pcoverage=false --max-workers=1 -PSDK_INCLUDED_BUILD_PATH=../zcash-android-wallet-sdk`; variant tokens are `Zcashtestnet…` (e.g. `:ui-lib:compileZcashtestnetFossDebugUnitTestKotlin`). Emulator-only device is emulator-5556.
- Commits local, not pushed. Trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Reference wallet for test expectations: **4 preparations across 3 layers** — id0 `L0/0` (deps: none), id1 `L0/1` (deps: none), id2 `L1/0` (deps: id0,id1), id3 `L2/0` (deps: id2) — plus **11 crossings** (ids 4..14).

## File Structure

- `../zcash-android-wallet-sdk/backend-lib/src/main/rust/migration.rs` — `encode_migration_schedule`: emit preparation entries. Add a pure helper for the entry computation.
- `../zcash-android-wallet-sdk/backend-lib/src/main/java/cash/z/ecc/android/sdk/internal/model/migration/JniMigrationModels.kt` — `JniPreparationStep` + `JniMigrationSchedule.preparations`.
- `../zcash-android-wallet-sdk/sdk-lib/src/main/java/cash/z/ecc/android/sdk/MigrationSdk.kt` — `PreparationStep` + `MigrationSchedule.preparations`.
- `../zcash-android-wallet-sdk/sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/OrchardMigrationSdkImpl.kt` — `toPublic()` mappings.
- `ui-lib/.../common/model/migration/MigrationPlan.kt` — `MigrationPreparation` + `MigrationPlan.preparations` + `toMigrationPlan` + `withLiveState`.
- `ui-lib/.../screen/migration/progress/{MigrationProgressState,MigrationProgressVM,MigrationProgressScreen}.kt` — render `Split balance 1..N` + debug `sync` field.
- `ui-lib/.../common/usecase/FinalizeMigrationScheduleUseCase.kt` — `delayUntilFirstTransfer`.
- `ui-lib/.../common/usecase/CheckMigrationRecoveryUseCase.kt` — strip auto-nav except Tor.

---

### Task 1: SDK Rust + JNI — emit preparation entries in the schedule

**Files:**
- Modify: `../zcash-android-wallet-sdk/backend-lib/src/main/rust/migration.rs` (`encode_migration_schedule`, ~line 660–720; the fn that already loops `migration_plan.schedule()`)
- Modify: `../zcash-android-wallet-sdk/backend-lib/src/main/java/cash/z/ecc/android/sdk/internal/model/migration/JniMigrationModels.kt` (`JniMigrationSchedule`, line 106)
- Test: `../zcash-android-wallet-sdk/backend-lib/src/main/rust/migration.rs` `#[cfg(test)]` module (pure helper)

**Interfaces:**
- Consumes (from librustzcash, already present): `MigrationPlan::planned_transactions() -> Vec<PlannedTx>` (each has `id()` + `kind()` where `MigrationTxKind::Preparation { layer, index }`); `MigrationPlan::prep_schedule() -> &[Vec<BlockHeight>]` (broadcast height per `[layer][index]`); `MigrationPlan::preparation() -> &PreparationPlan` and the deps (reuse whatever the commit path already uses to know a prep's `depends_on` ids — the same dependency structure the engine persists to `orchard_ironwood_migration_transaction_deps`).
- Produces: `JniMigrationSchedule.preparations: Array<JniPreparationStep>` where `JniPreparationStep(id: Long, layer: Int, index: Int, broadcastHeight: Long, dependsOn: LongArray)`.

- [ ] **Step 1: Add the Kotlin JNI carrier class + field.** In `JniMigrationModels.kt`, add above `JniMigrationSchedule`:
```kotlin
@Keep
class JniPreparationStep(
    val id: Long,
    val layer: Int,
    val index: Int,
    val broadcastHeight: Long,
    val dependsOn: LongArray,
)
```
and add a field to `JniMigrationSchedule`:
```kotlin
class JniMigrationSchedule(
    val transfers: Array<JniTransferProposal>,
    val preparations: Array<JniPreparationStep>,
    val estimatedDurationHours: Int,
    val proposalHandle: Long,
)
```
(match the existing `@Keep`/annotation style already on the neighbouring classes in this file.)

- [ ] **Step 2: Write the failing Rust unit test** for a pure helper that turns a plan into prep entries. In `migration.rs` test module:
```rust
#[test]
fn preparation_schedule_entries_cover_all_layers() {
    // Build a MigrationPlan whose preparation has 3 layers: layer 0 = 2 txs, layer 1 = 1, layer 2 = 1.
    // (Reuse the crate's existing plan test fixtures / single_note_setup-style helpers.)
    let plan = fixture_multilayer_plan();
    let entries = preparation_schedule_entries(&plan);
    // 4 preparation transactions, ids 0..3 in planned_transactions order.
    assert_eq!(entries.len(), 4);
    assert_eq!(entries[0].id, 0);
    assert_eq!((entries[0].layer, entries[0].index), (0, 0));
    assert_eq!((entries[2].layer, entries[2].index), (1, 0));
    // layer-1 tx depends on the two layer-0 txs.
    assert_eq!(entries[2].depends_on, vec![0, 1]);
    // broadcast height comes from prep_schedule()[layer][index].
    assert_eq!(entries[2].broadcast_height, plan.prep_schedule()[1][0]);
}
```
Define a small `struct PrepEntry { id: u32, layer: usize, index: usize, broadcast_height: BlockHeight, depends_on: Vec<u32> }` and `fn preparation_schedule_entries(plan: &MigrationPlan) -> Vec<PrepEntry>`.

- [ ] **Step 3: Run the test to verify it fails.** Run: `cd ../zcash-android-wallet-sdk && cargo test -p zcash-android-wallet-sdk preparation_schedule_entries 2>&1 | tail`. Expected: FAIL (function not defined).

- [ ] **Step 4: Implement `preparation_schedule_entries`.** Iterate `plan.planned_transactions()`, keep only `MigrationTxKind::Preparation { layer, index }`, and for each read `plan.prep_schedule()[layer][index]` for the broadcast height and the plan's dependency structure for `depends_on` (the ids of the preparations whose outputs this one spends). Return them in `planned_transactions` (id) order.

- [ ] **Step 5: Run the test to verify it passes.** Run: `cd ../zcash-android-wallet-sdk && cargo test -p zcash-android-wallet-sdk preparation_schedule_entries 2>&1 | tail`. Expected: PASS.

- [ ] **Step 6: Wire the JNI encode.** In `encode_migration_schedule`, after building the existing `transfers` JNI array, build a `JniPreparationStep[]` from `preparation_schedule_entries(...)` and set it on the constructed `JniMigrationSchedule` — mirror EXACTLY the hand-written JNI array/object construction the function already uses for `transfers` (same `env.new_object_array` / field-set / constructor-signature pattern; update the `JniMigrationSchedule` constructor JNI signature to include the new `[Lcash/z/ecc/.../JniPreparationStep;` array parameter in its correct position).

- [ ] **Step 7: Build the SDK Rust to confirm JNI compiles.** Run: `export PATH="$HOME/.cargo/bin:$PATH"; cd .. ; ./gradlew :backend-lib:cargoBuildHostRelease 2>&1 | tail` (or the app assemble in Task 4 will exercise it). Expected: BUILD SUCCESSFUL, `.so` produced.

- [ ] **Step 8: Commit.**
```bash
cd ../zcash-android-wallet-sdk
git add backend-lib/src/main/rust/migration.rs backend-lib/src/main/java/cash/z/ecc/android/sdk/internal/model/migration/JniMigrationModels.kt
git commit -m "feat(sdk): emit note-split preparations in migration schedule JNI"
```

---

### Task 2: SDK Kotlin — MigrationSchedule.preparations

**Files:**
- Modify: `../zcash-android-wallet-sdk/sdk-lib/src/main/java/cash/z/ecc/android/sdk/MigrationSdk.kt` (`MigrationSchedule`, line 135)
- Modify: `../zcash-android-wallet-sdk/sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/OrchardMigrationSdkImpl.kt` (`JniMigrationSchedule.toPublic()`, line 941; add `JniPreparationStep.toPublic()`)
- Test: `../zcash-android-wallet-sdk/sdk-lib/src/test/java/cash/z/ecc/android/sdk/internal/OrchardMigrationSdkImplTest.kt` (or a focused new test if that file is device-only)

**Interfaces:**
- Consumes: `JniPreparationStep(id, layer, index, broadcastHeight, dependsOn: LongArray)` and `JniMigrationSchedule.preparations` from Task 1.
- Produces: `MigrationSchedule.preparations: List<PreparationStep>` where `PreparationStep(id: Long, layer: Int, index: Int, broadcastHeight: Long, dependsOn: List<Long>)`.

- [ ] **Step 1: Add the public data class + field.** In `MigrationSdk.kt`, add near `MigrationSchedule`:
```kotlin
data class PreparationStep(
    val id: Long,
    val layer: Int,
    val index: Int,
    val broadcastHeight: Long,
    val dependsOn: List<Long>,
)
```
and extend `MigrationSchedule`:
```kotlin
data class MigrationSchedule(
    val transfers: List<TransferProposal>,
    val preparations: List<PreparationStep>,
    val estimatedDurationHours: Int,
    val proposalHandle: Long
)
```

- [ ] **Step 2: Write the failing mapping test.** In the SDK test source:
```kotlin
@Test
fun `toPublic maps preparations`() {
    val jni = JniMigrationSchedule(
        transfers = emptyArray(),
        preparations = arrayOf(JniPreparationStep(id = 2, layer = 1, index = 0, broadcastHeight = 4219055, dependsOn = longArrayOf(0, 1))),
        estimatedDurationHours = 1,
        proposalHandle = 7,
    )
    val pub = jni.toPublic()
    assertEquals(1, pub.preparations.size)
    assertEquals(PreparationStep(2, 1, 0, 4219055, listOf(0L, 1L)), pub.preparations.first())
}
```
(`toPublic()` is `private` in `OrchardMigrationSdkImpl.kt`; make the test call the same conversion — if it is a top-level/private extension, test it via the same visibility approach the file's existing `toPublic` tests use, or lift the extension to `internal`.)

- [ ] **Step 3: Run to verify it fails.** Run: `export PATH="$HOME/.cargo/bin:$PATH"; cd .. ; ./gradlew :sdk-lib:testDebugUnitTest --tests "*OrchardMigrationSdkImplTest*toPublic maps preparations*" 2>&1 | tail`. Expected: FAIL.

- [ ] **Step 4: Implement the mapping.** In `OrchardMigrationSdkImpl.kt`, add `private fun JniPreparationStep.toPublic(): PreparationStep = PreparationStep(id, layer, index, broadcastHeight, dependsOn.toList())` and set `preparations = preparations.map { it.toPublic() }` inside `JniMigrationSchedule.toPublic()`.

- [ ] **Step 5: Run to verify it passes.** Same command as Step 3. Expected: PASS.

- [ ] **Step 6: Commit.**
```bash
cd ../zcash-android-wallet-sdk
git add sdk-lib/src/main/java/cash/z/ecc/android/sdk/MigrationSdk.kt sdk-lib/src/main/java/cash/z/ecc/android/sdk/internal/OrchardMigrationSdkImpl.kt sdk-lib/src/test/java/cash/z/ecc/android/sdk/internal/OrchardMigrationSdkImplTest.kt
git commit -m "feat(sdk): MigrationSchedule.preparations public model + mapping"
```

---

### Task 3: App model — MigrationPlan.preparations

**Files:**
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/common/model/migration/MigrationPlan.kt`
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/common/model/migration/MigrationPlanTest.kt` (create if absent)

**Interfaces:**
- Consumes: `MigrationSchedule.preparations: List<PreparationStep>` (Task 2); the existing `MigrationTransferStates` live states, whose entries include preparations with `isTransfer = false`, `isProved`, `isSent`, `scheduledHeight`, and stable `id`.
- Produces: `MigrationPlan.preparations: List<MigrationPreparation>` where
```kotlin
data class MigrationPreparation(
    val id: Long,
    val layer: Int,
    val index: Int,
    val scheduledAtEpochSeconds: Long,
    val dependsOn: List<Long>,
    val status: MigrationTransferStatus,   // PENDING until live isSent → SENT
    val isProved: Boolean = false,          // for the debug "sync" field
)
```

- [ ] **Step 1: Write the failing test** for building + live-overlaying preparations:
```kotlin
@Test
fun `toMigrationPlan carries preparations and withLiveState marks them proved and sent`() {
    val schedule = MigrationSchedule(
        transfers = listOf(TransferProposal(id = 4, amountZatoshi = 500000000, anchorHeight = 100, nextExecutableAfterHeight = 208, expiryHeight = 9999)),
        preparations = listOf(PreparationStep(id = 0, layer = 0, index = 0, broadcastHeight = 137, dependsOn = emptyList())),
        estimatedDurationHours = 1, proposalHandle = 1,
    )
    val plan = schedule.toMigrationPlan(mode = MigrationMode.AUTOMATIC, secondsPerBlock = 28)
    assertEquals(1, plan.preparations.size)
    assertEquals(0L, plan.preparations.first().id)
    assertEquals(MigrationTransferStatus.PENDING, plan.preparations.first().status)

    val live = MigrationTransferStates(
        transfers = listOf(MigrationTransferState(id = 0, isTransfer = false, isSent = true, isProved = true, scheduledHeight = 137, anchorBoundaryHeight = null)),
        tipHeight = 200,
    )
    val overlaid = plan.withLiveState(live, secondsPerBlock = 28)
    assertEquals(MigrationTransferStatus.SENT, overlaid.preparations.first().status)
    assertTrue(overlaid.preparations.first().isProved)
}
```

- [ ] **Step 2: Run to verify it fails.** Run: `export PATH="$HOME/.cargo/bin:$PATH"; ./gradlew :ui-lib:compileZcashtestnetFossDebugUnitTestKotlin 2>&1 | tail` then the test task `:ui-lib:testZcashtestnetFossDebugUnitTest --tests "*MigrationPlanTest*"`. Expected: FAIL (preparations unresolved).

- [ ] **Step 3: Add `MigrationPreparation` + `MigrationPlan.preparations`.** Add the data class (above) and `val preparations: List<MigrationPreparation> = emptyList()` to `MigrationPlan`. Do NOT include preparations in `totalCount`/`completedCount`/`isComplete` (those stay crossings-only per Global Constraints).

- [ ] **Step 4: Populate in `toMigrationPlan`.** Preparations carry no per-item `anchorHeight`, so use the transfers' commit-tip baseline (they were all committed at the same tip): `val baseline = transfers.minOfOrNull { it.anchorHeight } ?: preparations.minOfOrNull { it.broadcastHeight } ?: 0L`. Then map `preparations = preparations.map { p -> MigrationPreparation(id = p.id, layer = p.layer, index = p.index, scheduledAtEpochSeconds = now + estimatedSecondsBetweenHeights(baseline, p.broadcastHeight, secondsPerBlock), dependsOn = p.dependsOn, status = MigrationTransferStatus.PENDING) }`. (`withLiveState` corrects this from the real `tipHeight` once live states exist, so the propose-time estimate only needs to be consistent with the transfers' baseline.)

- [ ] **Step 5: Overlay in `withLiveState`.** After the existing `transfers` overlay, add a `preparations` overlay correlating by id against `byId` (already computed): `status = if (liveTransfer.isSent) SENT else PENDING`, `isProved = liveTransfer.isProved`, and `scheduledAtEpochSeconds = now + estimatedSecondsBetweenHeights(live.tipHeight, liveTransfer.scheduledHeight, secondsPerBlock)`. Update the kdoc line that says preparation live entries "are naturally ignored" — they are now consumed.

- [ ] **Step 6: Run to verify it passes.** Same test task as Step 2. Expected: PASS.

- [ ] **Step 7: Commit.**
```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/common/model/migration/MigrationPlan.kt ui-lib/src/test/java/co/electriccoin/zcash/ui/common/model/migration/MigrationPlanTest.kt
git commit -m "feat: MigrationPlan carries note-split preparations with live status"
```

---

### Task 4: App UI — render Split balance 1..N + debug sync

**Files:**
- Modify: `ui-lib/.../screen/migration/progress/MigrationProgressState.kt`
- Modify: `ui-lib/.../screen/migration/progress/MigrationProgressVM.kt` (`createState`, ~line 226–250)
- Modify: `ui-lib/.../screen/migration/progress/MigrationProgressScreen.kt` (the timeline `Column`, ~line 88–120)
- Test: `ui-lib/src/test/java/.../migration/progress/MigrationProgressVMTest.kt`

**Interfaces:**
- Consumes: `MigrationPlan.preparations: List<MigrationPreparation>` (Task 3).
- Produces: `MigrationProgressState.preparations: List<MigrationProgressPreparationState>` where
```kotlin
data class MigrationProgressPreparationState(
    val number: Int,             // 1..N, display order = broadcast/schedule order
    val statusLabel: StringResource,
    val isSent: Boolean,
    val syncLabel: StringResource? = null,   // debug-only; null in release
)
```

- [ ] **Step 1: Write the failing VM test.** Build a VM state from a plan with 2 preparations (one SENT, one PENDING) and assert the state exposes `preparations` of size 2 with `number` 1 and 2, `isSent` true/false, and `statusLabel` matching the SAME relative formatting the transfer rows use (reuse the VM's existing transfer `statusLabel` helper). Assert `syncLabel` is non-null only when the debug flag is on (inject/override the debug flag in the test).

- [ ] **Step 2: Run to verify it fails.** Run: `export PATH="$HOME/.cargo/bin:$PATH"; ./gradlew :ui-lib:testZcashtestnetFossDebugUnitTest --tests "*MigrationProgressVMTest*" 2>&1 | tail`. Expected: FAIL.

- [ ] **Step 3: Add the state type + map it in `createState`.** Add `MigrationProgressPreparationState` and `val preparations: List<MigrationProgressPreparationState>` to `MigrationProgressState`. In `createState`, sort `plan.preparations` by `scheduledAtEpochSeconds`, map to numbered rows (`number = i + 1`), compute `statusLabel` with the SAME helper the transfers use (Sent → relative "sent" label; else `~relative`), set `isSent = it.status == SENT`, and set `syncLabel` = the debug relative "sync" label from `isProved`/its prove time ONLY when the debug flag is active (reuse whatever debug flag the codebase already exposes — search `BuildConfig`/an injected debug provider used by other MIGRATION_DIAG-gated UI). Use the SAME relative formatter for `syncLabel` as for `statusLabel`.

- [ ] **Step 4: Render in the screen.** In `MigrationProgressScreen.kt`, REPLACE the single `TransferProgressTimelineRow(title = "Split Balance", …)` with `state.preparations.forEach { prep -> TransferProgressTimelineRow(title = "Split balance ${prep.number}", statusLabel = prep.statusLabel, amount = state.totalAmount, isDone = prep.isSent, isActive = <first not-sent prep>, isOverdue = false, isLast = false) }`. Keep the transfer rows exactly as they are below. When the debug `syncLabel` is present, append it to the row's status text (smallest change: pass a combined `statusLabel` "$status · sync $syncLabel" so no new row-composable param is needed — verify `TransferProgressTimelineRow`'s statusLabel is plain text).

- [ ] **Step 5: Run to verify it passes.** Same test task as Step 2. Expected: PASS.

- [ ] **Step 6: Build + install + visually verify on emulator-5556.** `./gradlew :app:installZcashtestnetInternalDebug …`; launch; open Migration Progress; confirm `Split balance 1..N` rows render with per-row status; confirm debug `sync` text present in the internal/debug build. Crash-check logcat.

- [ ] **Step 7: Commit.**
```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/migration/progress/*.kt ui-lib/src/test/java/co/electriccoin/zcash/ui/screen/migration/progress/MigrationProgressVMTest.kt
git commit -m "feat: show Split balance 1..N as transaction rows + debug sync time"
```

---

### Task 5: Lane B / due-alarm initial arm targets earliest step

**Files:**
- Modify: `ui-lib/.../common/usecase/FinalizeMigrationScheduleUseCase.kt` (`delayUntilFirstTransfer`, line 140)
- Modify (verify): `ui-lib/.../work/MigrationDueAlarmScheduler.kt` (ensure its arm uses the same delay)
- Test: `ui-lib/src/test/java/.../usecase/FinalizeMigrationScheduleUseCaseTest.kt` (create if absent)

**Interfaces:**
- Consumes: `MigrationSchedule.preparations` (Task 2) + existing `MigrationSchedule.transfers`.

- [ ] **Step 1: Write the failing test.** A schedule whose earliest crossing `nextExecutableAfterHeight` is far (e.g. 4219108) but a preparation `broadcastHeight` is near (4219043) must yield a SMALL delay (targeting the preparation), not the crossing-based one:
```kotlin
@Test
fun `delay targets earliest step including preparations`() {
    val sched = MigrationSchedule(
        transfers = listOf(TransferProposal(id = 4, amountZatoshi = 1, anchorHeight = 4219036, nextExecutableAfterHeight = 4219108, expiryHeight = 9_999_999)),
        preparations = listOf(PreparationStep(id = 1, layer = 0, index = 1, broadcastHeight = 4219043, dependsOn = emptyList())),
        estimatedDurationHours = 1, proposalHandle = 1,
    )
    // exposed for test: delayUntilFirstStep(sched, secondsPerBlock=28, tipHeight=4219036)
    val d = delayUntilFirstStep(sched, secondsPerBlock = 28, tipHeight = 4219036)
    assertEquals((7 * 28).seconds, d)   // 4219043-4219036 = 7 blocks
}
```

- [ ] **Step 2: Run to verify it fails.** Run: `export PATH="$HOME/.cargo/bin:$PATH"; ./gradlew :ui-lib:testZcashtestnetFossDebugUnitTest --tests "*FinalizeMigrationScheduleUseCaseTest*" 2>&1 | tail`. Expected: FAIL.

- [ ] **Step 3: Rewrite the delay computation.** Replace `delayUntilFirstTransfer` with a computation over the UNION of steps: the earliest broadcast height across `sched.preparations.map { it.broadcastHeight }` + `sched.transfers.map { it.nextExecutableAfterHeight }`, converted to a delay from the current tip via `estimatedSecondsBetweenHeights(tip, earliest, secondsPerBlock)` (floored at 0). Take the tip the caller already has (the same value feeding the transfers' `anchorHeight` baseline). Keep the public call site in `invoke()` pointed at the new function.

- [ ] **Step 4: Verify the due-alarm arm shares it.** Read `MigrationDueAlarmScheduler.kt` and its call site; ensure the ready-to-send alarm is armed with the SAME delay value (not an independent crossing-based one). If it computes its own, route it through the same function. (Live logs showed both Lane B and the due alarm arming at the identical crossing-based "34m" — both must move.)

- [ ] **Step 5: Run to verify it passes.** Same test task as Step 2. Expected: PASS.

- [ ] **Step 6: Commit.**
```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/FinalizeMigrationScheduleUseCase.kt ui-lib/src/main/java/co/electriccoin/zcash/ui/work/MigrationDueAlarmScheduler.kt ui-lib/src/test/java/co/electriccoin/zcash/ui/common/usecase/FinalizeMigrationScheduleUseCaseTest.kt
git commit -m "fix: Lane B/due-alarm initial arm targets earliest step (incl. preparations)"
```

---

### Task 6: Remove app-open auto-navigation except Tor-failure

**Files:**
- Modify: `ui-lib/.../common/usecase/CheckMigrationRecoveryUseCase.kt` (routing block, lines ~146–197)
- Test: `ui-lib/src/test/java/.../usecase/CheckMigrationRecoveryUseCaseTest.kt` (exists or create)

**Interfaces:** none new. Behaviour-only change.

- [ ] **Step 1: Write the failing tests.** Two cases against a `FakeNavigationRouter` capturing `replaceAll`:
  1. `given hasOverdueTransfers() true → no navigation` (router `replaceAll` never called with `MigrationProgressArgs`).
  2. `given pending Tor failure → replaceAll(Home, MigrationSending)` still fires.
  Also assert the Lane A/B revival still schedules when a plan exists (existing revival behaviour unchanged).

- [ ] **Step 2: Run to verify it fails.** Run: `export PATH="$HOME/.cargo/bin:$PATH"; ./gradlew :ui-lib:testZcashtestnetFossDebugUnitTest --tests "*CheckMigrationRecoveryUseCaseTest*" 2>&1 | tail`. Expected: FAIL (overdue still navigates).

- [ ] **Step 3: Strip the auto-routes.** Keep the Lane A/B revival block and the `pendingMigrationTorFailureStorageProvider.get() → replaceAll(HomeArgs, MigrationSendingArgs)` branch. DELETE the `requiresAttention → MigrationTransferInvalidArgs`, `isTransferReadyToSendWithoutBackground → MigrationTransferReviewArgs`, `hasOverdueTransfers() → MigrationProgressArgs`, and `Complete → MigrationCompleteArgs` branches. Keep the stale-write-ahead-plan `migrationPlanRepository.clear()` branch (not navigation). Remove any now-unused imports/helpers (`isTransferReadyToSendWithoutBackground`, `requiresAttention`, etc.) flagged by the compiler.

- [ ] **Step 4: Run to verify it passes.** Same test task as Step 2. Expected: PASS.

- [ ] **Step 5: Verify home-banner reachability (manual read, no code).** Confirm `HomeVM.onMigrationMessageClick` still routes `hasAttention → Invalid`, `isComplete → Complete`, `isReadyToSend → Review`, `plan != null → Progress`, and that `GetHomeMessageUseCase` surfaces each state — so removing the auto-routes leaves every state reachable via the banner + button. Note findings in the ledger.

- [ ] **Step 6: Commit.**
```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/CheckMigrationRecoveryUseCase.kt ui-lib/src/test/java/co/electriccoin/zcash/ui/common/usecase/CheckMigrationRecoveryUseCaseTest.kt
git commit -m "fix: remove app-open auto-navigation except Tor-failure"
```

---

### Task 7: End-to-end verification on emulator

**Files:** none (verification only).

- [ ] **Step 1: Full build + install.** `export PATH="$HOME/.cargo/bin:$PATH"; ./gradlew :app:installZcashtestnetInternalDebug -Pcoverage=false --max-workers=1 -PSDK_INCLUDED_BUILD_PATH=../zcash-android-wallet-sdk`. Expected: BUILD SUCCESSFUL, fresh `.so`.
- [ ] **Step 2: Launch + crash-check.** Confirm Rust backend initialises, no `UnsatisfiedLink`/FATAL.
- [ ] **Step 3: Observe a migration** (fresh seed on testnet, or reuse an in-progress plan): confirm Migration Progress shows `Split balance 1..N` rows; confirm via logcat that the first preparation broadcasts promptly (Lane B arms to the first prep, not ~34m); confirm opening the app during healthy progress does NOT auto-jump to Progress (stays Home with banner).
- [ ] **Step 4: Ledger note** the observed prep-broadcast timing (should be minutes, not ~34m) and no auto-jump.
