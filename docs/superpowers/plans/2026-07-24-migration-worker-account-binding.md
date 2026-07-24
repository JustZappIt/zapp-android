# Migration Worker Account-Binding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the background `MigrationWorker` act on the account its WorkManager job was enqueued for (carried in `inputData`), not on whatever account is currently selected in the UI.

**Architecture:** The scheduler stamps `accountKeyId` into the work request's input data. The worker reads it and threads it through explicit-account overloads of the four things it currently resolves from the selected account (SDK build, plan load, migration-Tor flag, pending-Tor-failure flag). The worker's broadcast is already account-agnostic (`sdk.executeNextPendingTransfer(...)` on a per-account SDK instance), so no send-pipeline changes are needed. Existing selected-account APIs are kept for the unchanged UI/foreground callers.

**Tech Stack:** Kotlin, Coroutines/Flow, Koin DI, AndroidX WorkManager, JUnit + MockK + in-memory preference fakes.

## Global Constraints

- App-side only (`ui-lib`), background worker execution path only. No SDK changes.
- Foreground send pipeline (`SubmitProposalUseCase`, `ZashiProposalRepository`, `KeystoneProposalRepository`) and UI ViewModels are OUT of scope — selected account is correct there.
- Account identity is the `accountKeyId: String` = `account.sdkAccount.accountUuid.toStorageKeyId()` (account UUID hex), the same string used throughout the prior account-scoping change.
- Every parameterized API is an ADDITIVE overload; the existing no-arg (selected-account) versions stay and keep their current behavior/callers.
- WorkManager input-data key constant lives on `MigrationScheduler` (`KEY_ACCOUNT_KEY_ID`).
- Missing input data (a job enqueued before this change) → worker falls back to `getSelectedWalletAccount()` with a `Twig.warn`. Account not found in `getAllAccounts()` → the SDK use case returns `null`; worker treats as nothing-to-do (`Result.success()`), matching its existing null-SDK branch.
- Test infra to reuse (from the prior change's tests): the in-memory `PreferenceProvider` fake and `FakeAccountDataSource`/`AccountFixture` patterns in `MigrationTorPreferenceAccountScopingTest.kt`.
- Unit test command (mirror CI variant): `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "<FQCN>"`. Compile: `./gradlew :ui-lib:compileZcashmainnetStoreDebugKotlin`. Both are SLOW (minutes) — be patient.
- End each commit message with a blank line then: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

---

### Task 1: `MigrationPlanRepository.load(accountKeyId)` overload

**Files:**
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/common/repository/MigrationPlanRepository.kt`
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/common/repository/MigrationPlanRepositoryAccountBindingTest.kt` (create)

**Interfaces:**
- Consumes: nothing new.
- Produces: `suspend fun load(accountKeyId: String): MigrationPlan?` on the `MigrationPlanRepository` interface — loads the plan keyed by the passed `accountKeyId`, independent of the selected account. Returns `null` when none stored.

- [ ] **Step 1: Write the failing test**

Reuse the in-memory `PreferenceProvider` fake + `FakeAccountDataSource`/`AccountFixture` from `MigrationTorPreferenceAccountScopingTest.kt`. Construct a minimal `MigrationPlan` the same way existing tests do (see `CheckMigrationRecoveryUseCaseTest.planWithPendingTransfer`).

```kotlin
package co.electriccoin.zcash.ui.common.repository

// imports: EncryptedPreferenceProvider, the in-memory pref fake, FakeAccountDataSource, AccountFixture,
// MigrationPlan/MigrationMode/MigrationTransfer/MigrationTransferStatus, kotlinx.coroutines.test.runTest,
// kotlin.test.{Test,assertEquals,assertNull}, io.mockk.{coEvery, mockk}

class MigrationPlanRepositoryAccountBindingTest {

    private fun plan(id: String) = MigrationPlan(
        id = id,
        createdAtEpochSeconds = 0L,
        transfers = emptyList(),
        mode = MigrationMode.AUTOMATIC,
    )

    @Test
    fun loadByAccountKeyIdIsIndependentOfSelectedAccount() = runTest {
        val accountA = /* AccountFixture with a known UUID */ TODO_fixtureA()
        val keyA = accountA.sdkAccount.accountUuid.toStorageKeyId()
        val selected = MutableStateFlow<WalletAccount?>(accountA)
        val accountDataSource = mockk<AccountDataSource> {
            every { this@mockk.selectedAccount } returns selected
            coEvery { getSelectedAccount() } answers { selected.value!! }
        }
        val prefs = InMemoryPreferenceProvider()
        val holder = mockk<EncryptedPreferenceProvider> { coEvery { this@mockk() } returns prefs }
        val repo = MigrationPlanRepositoryImpl(holder, accountDataSource)

        // save under account A (selected)
        repo.save(plan("A"))

        // switch selected to B — explicit-account load of A still returns A's plan
        selected.value = /* AccountFixture B */ TODO_fixtureB()
        assertEquals("A", repo.load(keyA)?.id)

        // explicit-account load of a never-written key returns null
        assertNull(repo.load("deadbeefdeadbeef"))
    }
}
```

Replace the `TODO_fixture*` placeholders and `InMemoryPreferenceProvider` with the actual fixtures/fake from `MigrationTorPreferenceAccountScopingTest.kt` (copy the same construction). Keep the test's INTENT: `load(accountKeyId)` resolves the passed account's plan regardless of the selected account.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "co.electriccoin.zcash.ui.common.repository.MigrationPlanRepositoryAccountBindingTest"`
Expected: FAIL — `load(String)` does not exist (compile error).

- [ ] **Step 3: Add the overload**

In `MigrationPlanRepository.kt`, add to the interface:

```kotlin
suspend fun load(accountKeyId: String): MigrationPlan?
```

And to the impl (reuse the existing `loadByKey` + the `migration_plan_` key format):

```kotlin
override suspend fun load(accountKeyId: String): MigrationPlan? =
    loadByKey(PreferenceKey("migration_plan_$accountKeyId"))
```

(The existing `private fun key(account: WalletAccount)` builds the same `"migration_plan_${...toStorageKeyId()}"` string — keep it; this overload takes the already-hex `accountKeyId` directly.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "co.electriccoin.zcash.ui.common.repository.MigrationPlanRepositoryAccountBindingTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/common/repository/MigrationPlanRepository.kt \
        ui-lib/src/test/java/co/electriccoin/zcash/ui/common/repository/MigrationPlanRepositoryAccountBindingTest.kt
git commit -m "Add explicit-account load(accountKeyId) to MigrationPlanRepository"
```

---

### Task 2: Explicit-account overloads on the two migration Tor providers

**Files:**
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/IsMigrationTorEnabledStorageProvider.kt`
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/PendingMigrationTorFailureStorageProvider.kt`
- Test: extend `ui-lib/src/test/java/co/electriccoin/zcash/ui/common/provider/MigrationTorPreferenceAccountScopingTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `IsMigrationTorEnabledStorageProvider`: `suspend fun get(accountKeyId: String): Boolean` — reads the passed account's key.
  - `PendingMigrationTorFailureStorageProvider`: `suspend fun store(accountKeyId: String, value: Boolean)` — writes the passed account's key.

Both impls already have a `private fun default(accountUuid: String)` building the per-account `BooleanPreferenceDefault`; the overloads call it directly with `accountKeyId` (no selected-account read).

- [ ] **Step 1: Write the failing test**

Add to `MigrationTorPreferenceAccountScopingTest.kt` (reuse its existing fakes):

```kotlin
@Test
fun torEnabledGetByAccountKeyIdIgnoresSelectedAccount() = runTest {
    val accountA = account("00112233...")   // full-length fixture UUID, as elsewhere in this file
    val selected = MutableStateFlow<WalletAccount?>(accountA)
    val accountDataSource = fakeAccountDataSource(selected)
    val prefs = InMemoryPreferenceProvider()
    val holder = mockk<StandardPreferenceProvider> { coEvery { this@mockk() } returns prefs }

    val provider = IsMigrationTorEnabledStorageProviderImpl(holder, accountDataSource)
    val keyA = accountA.sdkAccount.accountUuid.toStorageKeyId()

    provider.store(false)                 // writes account A (selected)
    selected.value = account("aabbccdd...") // switch selected to B
    assertEquals(false, provider.get(keyA)) // explicit-account get still reads A
    assertTrue(provider.get("ffffffffffffffff")) // unknown key → default true
}

@Test
fun pendingTorFailureStoreByAccountKeyIdTargetsThatAccount() = runTest {
    val accountA = account("00112233...")
    val selected = MutableStateFlow<WalletAccount?>(accountA)
    val accountDataSource = fakeAccountDataSource(selected)
    val prefs = InMemoryPreferenceProvider()
    val holder = mockk<StandardPreferenceProvider> { coEvery { this@mockk() } returns prefs }

    val provider = PendingMigrationTorFailureStorageProviderImpl(holder, accountDataSource)
    val keyA = accountA.sdkAccount.accountUuid.toStorageKeyId()

    selected.value = account("aabbccdd...")   // B selected
    provider.store(keyA, true)                // but we store for A explicitly
    assertEquals(true, provider.get(keyA))    // A has it
    assertEquals(false, provider.get())       // selected B does not (default false)
}
```

Use the same account-fixture/`fakeAccountDataSource` helpers already present in the file (extract small helpers if the file doesn't have them yet — keep the two existing tests working).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "co.electriccoin.zcash.ui.common.provider.MigrationTorPreferenceAccountScopingTest"`
Expected: FAIL — `get(String)` / `store(String, Boolean)` overloads don't exist.

- [ ] **Step 3: Add the overloads**

In `IsMigrationTorEnabledStorageProvider.kt` — interface:

```kotlin
suspend fun get(accountKeyId: String): Boolean
```
impl:
```kotlin
override suspend fun get(accountKeyId: String): Boolean = default(accountKeyId).getValue(preferenceHolder())
```

In `PendingMigrationTorFailureStorageProvider.kt` — interface:

```kotlin
suspend fun store(accountKeyId: String, value: Boolean)
```
impl:
```kotlin
override suspend fun store(accountKeyId: String, value: Boolean) =
    default(accountKeyId).putValue(preferenceHolder(), value)
```

(`default(accountUuid: String)` already exists in both impls from the earlier per-account change; these overloads reuse it verbatim with the passed key id.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "co.electriccoin.zcash.ui.common.provider.MigrationTorPreferenceAccountScopingTest"`
Expected: PASS (existing 2 + new 2).

- [ ] **Step 5: Commit**

```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/IsMigrationTorEnabledStorageProvider.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/PendingMigrationTorFailureStorageProvider.kt \
        ui-lib/src/test/java/co/electriccoin/zcash/ui/common/provider/MigrationTorPreferenceAccountScopingTest.kt
git commit -m "Add explicit-account overloads to migration Tor providers"
```

---

### Task 3: `GetOrchardMigrationSdkUseCase.invoke(accountKeyId)` + account lookup

**Files:**
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/GetOrchardMigrationSdkUseCase.kt`
- Modify (DI): `ui-lib/src/main/java/co/electriccoin/zcash/di/UseCaseModule.kt` (inject `AccountDataSource` if `factoryOf`/`singleOf` can't already resolve it — verify)
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/common/usecase/FindAccountByKeyIdTest.kt` (create)

**Interfaces:**
- Consumes: `AccountDataSource.getAllAccounts(): List<WalletAccount>`, `AccountUuid.toStorageKeyId()`.
- Produces:
  - A pure, testable helper: `fun List<WalletAccount>.findByAccountKeyId(accountKeyId: String): WalletAccount? = firstOrNull { it.sdkAccount.accountUuid.toStorageKeyId() == accountKeyId }` (top-level in `GetOrchardMigrationSdkUseCase.kt`).
  - `suspend operator fun invoke(accountKeyId: String): OrchardMigrationSdk?` — builds the SDK for the account matching `accountKeyId`, or `null` if no wallet or no matching account.

**Note:** `OrchardMigrationSdk.new(...)` is a real Rust call and cannot run in a JVM unit test, so the *lookup* is what gets unit-tested (the genuinely new logic). `invoke(accountKeyId)` itself is covered by compile + the Task 5 manual check.

- [ ] **Step 1: Write the failing test (pure lookup helper)**

```kotlin
package co.electriccoin.zcash.ui.common.usecase

// imports: WalletAccount, AccountUuid, io.mockk.{every, mockk}, kotlin.test.{Test, assertNull, assertSame}

class FindAccountByKeyIdTest {
    private fun account(hexUuid: String): WalletAccount =
        mockk { every { sdkAccount.accountUuid } returns /* AccountUuid from hexUuid, as elsewhere */ TODO_uuid(hexUuid) }

    @Test
    fun findsAccountWhoseStorageKeyIdMatches() {
        val a = account("00112233445566778899aabbccddeeff")
        val b = account("ffeeddccbbaa99887766554433221100")
        val keyA = a.sdkAccount.accountUuid.toStorageKeyId()
        assertSame(a, listOf(a, b).findByAccountKeyId(keyA))
    }

    @Test
    fun returnsNullWhenNoAccountMatches() {
        val a = account("00112233445566778899aabbccddeeff")
        assertNull(listOf(a).findByAccountKeyId("no-such-key"))
    }
}
```

Replace `TODO_uuid(...)` with the same `AccountUuid` construction used in `MigrationTorPreferenceAccountScopingTest.kt` (via `AccountFixture`/`UUID`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "co.electriccoin.zcash.ui.common.usecase.FindAccountByKeyIdTest"`
Expected: FAIL — `findByAccountKeyId` unresolved.

- [ ] **Step 3: Add the helper + overload**

In `GetOrchardMigrationSdkUseCase.kt`:

```kotlin
// top-level (below the class)
fun List<WalletAccount>.findByAccountKeyId(accountKeyId: String): WalletAccount? =
    firstOrNull { it.sdkAccount.accountUuid.toStorageKeyId() == accountKeyId }
```

Refactor the class to inject `AccountDataSource`, extract a private builder, and add the overload:

```kotlin
class GetOrchardMigrationSdkUseCase(
    private val context: Context,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val accountDataSource: AccountDataSource,
) {
    suspend operator fun invoke(): OrchardMigrationSdk? =
        buildFor(getSelectedWalletAccount())

    suspend operator fun invoke(accountKeyId: String): OrchardMigrationSdk? {
        val account = accountDataSource.getAllAccounts().findByAccountKeyId(accountKeyId) ?: run {
            Twig.warn { "MIGRATION_DIAG GetOrchardMigrationSdk: no account for keyId=$accountKeyId" }
            return null
        }
        return buildFor(account)
    }

    private suspend fun buildFor(account: WalletAccount): OrchardMigrationSdk? {
        val wallet = persistableWalletProvider.getPersistableWallet() ?: return null
        return OrchardMigrationSdk.new(
            appContext = context,
            zcashNetwork = wallet.network,
            lightWalletEndpoint = wallet.endpoint,
            account = account.sdkAccount.accountUuid,
        )
    }
}
```

Add imports for `AccountDataSource`, `WalletAccount`, `toStorageKeyId`, `Twig`.

- [ ] **Step 4: Wire DI and run tests**

Verify `GetOrchardMigrationSdkUseCase`'s registration in `UseCaseModule.kt` still resolves after adding `accountDataSource` (if `factoryOf(::GetOrchardMigrationSdkUseCase)`/`singleOf`, Koin auto-resolves the registered `AccountDataSource` — no change needed; otherwise pass `get()`).
Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "co.electriccoin.zcash.ui.common.usecase.FindAccountByKeyIdTest"`
Expected: PASS.
Run: `./gradlew :ui-lib:compileZcashmainnetStoreDebugKotlin`
Expected: BUILD SUCCESSFUL (existing no-arg `invoke()` callers unchanged).

- [ ] **Step 5: Commit**

```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/GetOrchardMigrationSdkUseCase.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/di/UseCaseModule.kt \
        ui-lib/src/test/java/co/electriccoin/zcash/ui/common/usecase/FindAccountByKeyIdTest.kt
git commit -m "Add explicit-account invoke(accountKeyId) to GetOrchardMigrationSdkUseCase"
```

---

### Task 4: Stamp account into the job and bind the worker to it

**Files:**
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationScheduler.kt`
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationWorker.kt`
- Modify (if it triggers work): `ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationTransferDueReceiver.kt`

**Interfaces:**
- Consumes: `MigrationPlanRepository.load(accountKeyId)` (Task 1), `IsMigrationTorEnabledStorageProvider.get(accountKeyId)` + `PendingMigrationTorFailureStorageProvider.store(accountKeyId, value)` (Task 2), `GetOrchardMigrationSdkUseCase.invoke(accountKeyId)` (Task 3).
- Produces: `MigrationScheduler.KEY_ACCOUNT_KEY_ID` (const String); the worker resolves its account from `inputData[KEY_ACCOUNT_KEY_ID]`.

**Note:** `WorkManager`/`CoroutineWorker.inputData` aren't exercised by this module's JVM unit tests (no Robolectric). Correctness rests on compile + the existing `MigrationWorkerTest` (pure helpers) + the Task 5 manual emulator check. Do not add a JVM test constructing a `CoroutineWorker`.

- [ ] **Step 1: Stamp input data in `MigrationScheduler`**

Add the key constant and pass `accountKeyId` into the request builder. Change `newWorkRequest` to take `accountKeyId`:

```kotlin
import androidx.work.workDataOf
// ...
fun schedule(accountKeyId: String, delay: Duration) {
    Twig.debug { "MIGRATION_DIAG MigrationScheduler: scheduling next migration transfer for $accountKeyId in $delay" }
    WorkManager.getInstance(context).enqueueUniqueWork(
        workId(accountKeyId),
        ExistingWorkPolicy.REPLACE,
        newWorkRequest(accountKeyId, delay)
    )
    migrationDueAlarmScheduler.schedule(accountKeyId, delay)
}

companion object {
    const val WORK_ID_PREFIX = "co.electriccoin.zcash.migration_transfer"
    const val KEY_ACCOUNT_KEY_ID = "co.electriccoin.zcash.migration.work_account_key_id"

    fun workId(accountKeyId: String): String = "${WORK_ID_PREFIX}_$accountKeyId"

    fun newWorkRequest(accountKeyId: String, delay: Duration): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<MigrationWorker>()
            .setConstraints(workConstraints())
            .setInitialDelay(delay.toJavaDuration())
            .setInputData(workDataOf(KEY_ACCOUNT_KEY_ID to accountKeyId))
            .build()
    // workConstraints() unchanged
}
```

- [ ] **Step 2: Bind the worker to the enqueued account**

In `MigrationWorker.doWork()`, replace the selected-account resolution (`getOrchardMigrationSdk()` on line 41 and `getSelectedWalletAccount()...` on line 45) with input-data resolution, then use `accountKeyId` for the SDK, plan, and Tor reads/writes:

```kotlin
override suspend fun doWork(): Result {
    val accountKeyId = inputData.getString(MigrationScheduler.KEY_ACCOUNT_KEY_ID)
        ?: getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId().also {
            Twig.warn { "MIGRATION_DIAG MigrationWorker: no accountKeyId in inputData — falling back to selected account (pre-upgrade job)" }
        }

    val sdk = getOrchardMigrationSdk(accountKeyId) ?: run {
        Twig.debug { "MIGRATION_DIAG MigrationWorker: no SDK for account $accountKeyId — skipping." }
        return Result.success()
    }

    val finalizedCount = sdk.finalizeReadyTransfers()
    if (finalizedCount > 0) {
        Twig.debug { "MIGRATION_DIAG MigrationWorker: finalized $finalizedCount transfer(s) awaiting proof." }
    }

    val plan = migrationPlanRepository.load(accountKeyId)
    val next = plan?.nextPending
    val useTor = isMigrationTorEnabledStorageProvider.get(accountKeyId)
    // ... unchanged executeWithRetries { sdk.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor)) } ...
```

In the same method, update the two reschedule reloads and the Tor-failure write to use `accountKeyId`:
- line ~109: `val updatedPlan = migrationPlanRepository.load(accountKeyId)`
- line ~134: `pendingMigrationTorFailureStorageProvider.store(accountKeyId, true)`

Every `MigrationScheduler(applicationContext).schedule(accountKeyId, delay)` and `migrationNotifier.notify*(accountKeyId, ...)` call already uses `accountKeyId` (unchanged). `getSelectedWalletAccount` stays injected (used only for the fallback).

- [ ] **Step 3: Thread the account through the alarm receiver if it triggers work**

In `MigrationTransferDueReceiver`, if the receiver enqueues/triggers a migration action (not just a notification), pass its already-read `accountKeyId` (from `EXTRA_ACCOUNT_KEY_ID`) into that trigger the same way. If it only posts a notification (current behavior), no change is needed beyond what Task 3/4 of the prior change already did — state which case applies in the report.

- [ ] **Step 4: Compile and run the existing worker test**

Run: `./gradlew :ui-lib:compileZcashmainnetStoreDebugKotlin`
Expected: BUILD SUCCESSFUL.
Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "co.electriccoin.zcash.work.MigrationWorkerTest"`
Expected: PASS (pure-helper tests; update construction only if the test references changed signatures).

- [ ] **Step 5: Commit**

```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationScheduler.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationWorker.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationTransferDueReceiver.kt
git commit -m "Bind MigrationWorker to its enqueued account via WorkManager input data"
```

---

### Task 5: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Full module unit suite**

Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest`
Expected: all new tests pass; migration/account-scoping tests green; the only pre-existing failures are the unrelated `NearSwapQuoteTest` ones and the 3 quarantined `MigrationReviewVMTest` @Ignore tests (documented in the prior change). Confirm no NEW failures.

- [ ] **Step 2: ktlint the new/changed files**

Run: `./gradlew ktlint` then confirm none of THIS task's files appear in the output (the branch has pre-existing ktlint debt elsewhere — ignore that). If any of our files report, run `./gradlew ktlintFormat`, keep only our files' formatting (stage them, `git checkout -- .` the rest), and re-verify.

- [ ] **Step 3: Manual emulator check (per project convention — install+launch testnet/foss/debug)**

Build+install, then with a wallet holding both a Zashi and a Keystone account and a scheduled Zashi migration transfer:
1. Schedule the Zashi transfer, then switch the selected account to Keystone.
2. Force the Zashi job to run (debug hook, or wait for its due time).
3. Confirm it executes **Zashi's** next transfer and reschedules under Zashi's work name / notification, leaving Keystone's plan and schedule untouched.
Document the observed result.

- [ ] **Step 4: Final commit (only if verification fixes were needed)**

```bash
git add -A
git commit -m "Fix issues surfaced during worker account-binding verification"
```
