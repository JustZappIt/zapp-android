# Migration Account-Scoping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Isolate the migration feature's remaining global (wallet-wide) state per account, so a Zashi and a Keystone account can migrate independently without clobbering each other's preferences, scheduled jobs, alarms, notifications, or in-memory hand-offs.

**Architecture:** Follow the existing per-account pattern already used by `MigrationPlanRepository` and `HasSeenMigrationCompleteStorageProvider` (key suffixed with `accountUuid.toStorageKeyId()`, `observe()` driven by `selectedAccount.flatMapLatest`). For non-preference identifiers (WorkManager work name, AlarmManager request code, notification IDs) derive a per-account integer offset from the account's storage-key id. Callers already have the selected account and pass it in.

**Tech Stack:** Kotlin, Coroutines/Flow, Koin DI, AndroidX WorkManager + AlarmManager, JUnit + MockK + `MockPreferenceProvider` for tests.

## Global Constraints

- Scope is app-side only (`ui-lib`). Do NOT touch the SDK (`zcash-android-wallet-sdk`).
- Do NOT touch `migration_sync_resume_at` / `MigrationSyncResumeAtStorageProvider` (unwired; sync is inherently whole-wallet). Leave as-is.
- Clean cutover: no data migration / cleanup of old global keys, jobs, or alarms.
- Per-account key format matches the existing convention: `<base>_${accountUuid.toStorageKeyId()}` where `toStorageKeyId()` returns the account UUID hex (`AccountUuidExt.kt:15`).
- Per-account int offset: `accountKeyId.hashCode() and 0xFFFF`, derived once in a shared helper.
- Preserve existing defaults: migration Tor flag defaults `true`; pending Tor-failure flag defaults `false`.
- Unit test command (variant-agnostic; mirror CI's variant): `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "<FQCN>"`.
- Manual verification per project convention: install+launch on testnet/foss/debug emulator.

---

### Task 1: Shared per-account int offset helper

**Files:**
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/common/model/AccountUuidExt.kt`
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/common/model/AccountIdOffsetTest.kt` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `fun accountIdOffset(accountKeyId: String): Int` — returns `accountKeyId.hashCode() and 0xFFFF` (range `0..0xFFFF`). `accountKeyId` is the value from `AccountUuid.toStorageKeyId()`. Used by Tasks 3 and 4.

- [ ] **Step 1: Write the failing test**

```kotlin
package co.electriccoin.zcash.ui.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccountIdOffsetTest {
    @Test
    fun offsetIsWithinSixteenBitRange() {
        val offset = accountIdOffset("a1b2c3d4")
        assertTrue(offset in 0..0xFFFF, "offset $offset out of range")
    }

    @Test
    fun offsetIsDeterministicForSameId() {
        assertEquals(accountIdOffset("deadbeef"), accountIdOffset("deadbeef"))
    }

    @Test
    fun offsetDiffersForTwoRepresentativeAccounts() {
        // Two representative account key ids (Zashi vs Keystone).
        assertNotEquals(accountIdOffset("0011223344556677"), accountIdOffset("8899aabbccddeeff"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "co.electriccoin.zcash.ui.common.model.AccountIdOffsetTest"`
Expected: FAIL — `accountIdOffset` unresolved reference.

- [ ] **Step 3: Add the helper**

Append to `AccountUuidExt.kt`:

```kotlin
/**
 * A stable per-account offset in `0..0xFFFF`, derived from the account's storage-key id
 * ([toStorageKeyId]). Used to make otherwise-global integer identifiers (WorkManager work name
 * suffix, AlarmManager request code, notification ids) distinct per account so a Zashi and a
 * Keystone account's migration never overwrite each other's. Hash collision across two accounts is
 * theoretically possible but negligible; a registry was considered and rejected as unnecessary state.
 */
fun accountIdOffset(accountKeyId: String): Int = accountKeyId.hashCode() and 0xFFFF
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "co.electriccoin.zcash.ui.common.model.AccountIdOffsetTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/common/model/AccountUuidExt.kt \
        ui-lib/src/test/java/co/electriccoin/zcash/ui/common/model/AccountIdOffsetTest.kt
git commit -m "Add per-account int offset helper for migration identifiers"
```

---

### Task 2: Per-account migration Tor preference providers

**Files:**
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/IsMigrationTorEnabledStorageProvider.kt`
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/PendingMigrationTorFailureStorageProvider.kt`
- Modify (DI): `ui-lib/src/main/java/co/electriccoin/zcash/di/ProviderModule.kt:119-121`
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/common/provider/MigrationTorPreferenceAccountScopingTest.kt` (create)

**Interfaces:**
- Consumes: `AccountDataSource` (`selectedAccount: Flow<WalletAccount?>`, `suspend getSelectedAccount(): WalletAccount`), `AccountUuid.toStorageKeyId()`.
- Produces: unchanged public interfaces `IsMigrationTorEnabledStorageProvider` / `PendingMigrationTorFailureStorageProvider` (both `BooleanStorageProvider`). Only the impl constructors gain an `AccountDataSource` param. All existing call sites keep working unchanged.

Reference pattern to copy: `HasSeenMigrationCompleteStorageProvider.kt` (same file already demonstrates the exact dynamic-key + `flatMapLatest` shape, including `default(accountUuid)`, `currentAccountUuid()`).

- [ ] **Step 1: Write the failing test**

```kotlin
package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.test.MockPreferenceProvider
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationTorPreferenceAccountScopingTest {

    private fun account(hexUuid: String): WalletAccount =
        mockk(relaxed = true) {
            every { sdkAccount.accountUuid } returns AccountUuid.new(hexUuid.hexToByteArray())
        }

    @Test
    fun torEnabledFlagIsIsolatedPerAccount() = runTest {
        val accountA = account("00112233")
        val accountB = account("aabbccdd")
        val selected = MutableStateFlow<WalletAccount?>(accountA)
        val accountDataSource = mockk<AccountDataSource> {
            every { this@mockk.selectedAccount } returns selected
            coEvery { getSelectedAccount() } answers { selected.value!! }
        }
        val prefs = MockPreferenceProvider()
        val holder = mockk<StandardPreferenceProvider> { coEvery { this@mockk() } returns prefs }

        val provider = IsMigrationTorEnabledStorageProviderImpl(holder, accountDataSource)

        // default is true for both accounts
        assertTrue(provider.get())

        // write false while account A is selected
        provider.store(false)
        assertEquals(false, provider.get())

        // switch to account B — unaffected, still default true
        selected.value = accountB
        assertTrue(provider.get())

        // switch back to A — still false
        selected.value = accountA
        assertEquals(false, provider.get())
    }

    @Test
    fun pendingTorFailureFlagIsIsolatedPerAccount() = runTest {
        val accountA = account("00112233")
        val accountB = account("aabbccdd")
        val selected = MutableStateFlow<WalletAccount?>(accountA)
        val accountDataSource = mockk<AccountDataSource> {
            every { this@mockk.selectedAccount } returns selected
            coEvery { getSelectedAccount() } answers { selected.value!! }
        }
        val prefs = MockPreferenceProvider()
        val holder = mockk<StandardPreferenceProvider> { coEvery { this@mockk() } returns prefs }

        val provider = PendingMigrationTorFailureStorageProviderImpl(holder, accountDataSource)

        assertEquals(false, provider.get()) // default false
        provider.store(true)                // account A failed
        assertEquals(true, provider.get())

        selected.value = accountB
        assertEquals(false, provider.get())  // account B unaffected
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "co.electriccoin.zcash.ui.common.provider.MigrationTorPreferenceAccountScopingTest"`
Expected: FAIL — the `Impl` constructors don't take `AccountDataSource` yet (compile error / signature mismatch).

- [ ] **Step 3: Rewrite `IsMigrationTorEnabledStorageProvider.kt` to the dynamic per-account pattern**

```kotlin
package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.BooleanPreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Migration-scoped Tor setting, distinct from the app's global [IsTorEnabledStorageProvider].
 * Defaults to `true` (privacy-by-default). Keyed per-account (see [AccountDataSource]) — the app
 * supports a Zodl and a Keystone account migrating independently, so one account's Tor choice must
 * not leak into the other's migration. Only ever written by the migration Tor prompt
 * (`MigrationPrivacyVM`) or "Continue without Tor" (`MigrationTorFailureVM`); read by every
 * migration broadcast site (`MigrationSendingVM`, `MigrationWorker`, `MigrationKeystoneScanVM`).
 * Backed by regular (non-encrypted) app storage, wiped on uninstall.
 */
interface IsMigrationTorEnabledStorageProvider : BooleanStorageProvider

class IsMigrationTorEnabledStorageProviderImpl(
    private val preferenceHolder: StandardPreferenceProvider,
    private val accountDataSource: AccountDataSource,
) : IsMigrationTorEnabledStorageProvider {
    override suspend fun get(): Boolean = default(currentAccountUuid()).getValue(preferenceHolder())

    override suspend fun store(value: Boolean) = default(currentAccountUuid()).putValue(preferenceHolder(), value)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(): Flow<Boolean> =
        accountDataSource.selectedAccount.flatMapLatest { account ->
            if (account == null) {
                flowOf(true)
            } else {
                flow { emitAll(default(account.sdkAccount.accountUuid.toStorageKeyId()).observe(preferenceHolder())) }
            }
        }

    override suspend fun clear() = default(currentAccountUuid()).clear(preferenceHolder())

    override suspend fun flip() = store(!get())

    private suspend fun currentAccountUuid(): String =
        accountDataSource.getSelectedAccount().sdkAccount.accountUuid.toStorageKeyId()

    private fun default(accountUuid: String) =
        BooleanPreferenceDefault(key = PreferenceKey("is_migration_tor_enabled_$accountUuid"), defaultValue = true)
}
```

- [ ] **Step 4: Rewrite `PendingMigrationTorFailureStorageProvider.kt` the same way (default `false`, key `pending_migration_tor_failure_$accountUuid`, `observe` fallback emits `false`)**

```kotlin
package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.BooleanPreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Persisted flag remembering that a background migration send attempt failed specifically because
 * of Tor connectivity and hasn't been resolved yet. Keyed per-account (see [AccountDataSource]) so
 * a Keystone account's background Tor failure never routes the Zashi account into recovery. Set to
 * `true` when `MigrationWorker` hits a non-retryable network error while Tor was in use; cleared on
 * a subsequent successful transfer. Backed by regular (non-encrypted) app storage, wiped on uninstall.
 */
interface PendingMigrationTorFailureStorageProvider : BooleanStorageProvider

class PendingMigrationTorFailureStorageProviderImpl(
    private val preferenceHolder: StandardPreferenceProvider,
    private val accountDataSource: AccountDataSource,
) : PendingMigrationTorFailureStorageProvider {
    override suspend fun get(): Boolean = default(currentAccountUuid()).getValue(preferenceHolder())

    override suspend fun store(value: Boolean) = default(currentAccountUuid()).putValue(preferenceHolder(), value)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(): Flow<Boolean> =
        accountDataSource.selectedAccount.flatMapLatest { account ->
            if (account == null) {
                flowOf(false)
            } else {
                flow { emitAll(default(account.sdkAccount.accountUuid.toStorageKeyId()).observe(preferenceHolder())) }
            }
        }

    override suspend fun clear() = default(currentAccountUuid()).clear(preferenceHolder())

    override suspend fun flip() = store(!get())

    private suspend fun currentAccountUuid(): String =
        accountDataSource.getSelectedAccount().sdkAccount.accountUuid.toStorageKeyId()

    private fun default(accountUuid: String) =
        BooleanPreferenceDefault(key = PreferenceKey("pending_migration_tor_failure_$accountUuid"), defaultValue = false)
}
```

- [ ] **Step 5: Update DI to inject `accountDataSource`**

In `ProviderModule.kt`, change the two bindings (around lines 119-121) so each `Impl` receives `accountDataSource`. If they use `singleOf(::...Impl)`, Koin resolves `AccountDataSource` automatically as long as it's registered; verify by keeping `singleOf` — no change needed if the constructor param type is already a Koin-registered singleton. If `singleOf` cannot resolve, switch to an explicit `single { ...Impl(get(), get()) }`. Confirm `AccountDataSource` is registered (it is — used by `HasSeenMigrationCompleteStorageProviderImpl`).

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "co.electriccoin.zcash.ui.common.provider.MigrationTorPreferenceAccountScopingTest"`
Expected: PASS (2 tests).

- [ ] **Step 7: Compile the module to confirm no call-site breakage**

Run: `./gradlew :ui-lib:compileZcashmainnetStoreDebugKotlin`
Expected: BUILD SUCCESSFUL (public interfaces unchanged, so `MigrationSendingVM`, `MigrationWorker`, `MigrationKeystoneScanVM`, `MigrationPrivacyVM`, `MigrationTorFailureVM`, `CheckMigrationRecoveryUseCase`, `DebugVM` still compile).

- [ ] **Step 8: Commit**

```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/IsMigrationTorEnabledStorageProvider.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/PendingMigrationTorFailureStorageProvider.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/di/ProviderModule.kt \
        ui-lib/src/test/java/co/electriccoin/zcash/ui/common/provider/MigrationTorPreferenceAccountScopingTest.kt
git commit -m "Scope migration Tor preferences per account"
```

---

### Task 3: Per-account WorkManager work name + AlarmManager request code

**Files:**
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationScheduler.kt`
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationDueAlarmScheduler.kt`
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationTransferDueReceiver.kt` (read account extra)
- Modify callers: `ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/ScheduleNextMigrationWindowUseCase.kt`, `FinalizeMigrationScheduleUseCase.kt`, `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/migration/progress/MigrationProgressVM.kt`, `ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationWorker.kt`, `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/advancedsettings/debug/DebugVM.kt`

**Interfaces:**
- Consumes: `accountIdOffset(accountKeyId: String)` (Task 1); `AccountUuid.toStorageKeyId()`.
- Produces:
  - `MigrationScheduler.schedule(accountKeyId: String, delay: Duration)` and `MigrationScheduler.cancel(accountKeyId: String)`.
  - `MigrationScheduler.workId(accountKeyId: String): String = "${WORK_ID_PREFIX}_$accountKeyId"`.
  - `MigrationDueAlarmScheduler.schedule(accountKeyId: String, delay: Duration)` / `cancel(accountKeyId: String)`; request code `ALARM_REQUEST_CODE_BASE + accountIdOffset(accountKeyId)`; intent carries `EXTRA_ACCOUNT_KEY_ID = accountKeyId`.

**Note on tests:** `WorkManager` / `AlarmManager` / `PendingIntent` are Android-framework singletons not exercised by JVM unit tests here (no Robolectric in this module's unit test setup). This task's correctness is covered by (a) Task 1's offset tests, (b) `compileZcashmainnetStoreDebugKotlin`, and (c) the manual emulator verification in Task 6. Do not add a JVM unit test that instantiates these framework classes.

- [ ] **Step 1: Update `MigrationScheduler.kt`**

```kotlin
class MigrationScheduler(private val context: Context) {
    private val migrationDueAlarmScheduler = MigrationDueAlarmScheduler(context)

    fun schedule(accountKeyId: String, delay: Duration) {
        Twig.debug { "MIGRATION_DIAG MigrationScheduler: scheduling next migration transfer for $accountKeyId in $delay" }
        WorkManager.getInstance(context).enqueueUniqueWork(
            workId(accountKeyId),
            ExistingWorkPolicy.REPLACE,
            newWorkRequest(delay)
        )
        migrationDueAlarmScheduler.schedule(accountKeyId, delay)
    }

    fun cancel(accountKeyId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workId(accountKeyId))
        migrationDueAlarmScheduler.cancel(accountKeyId)
    }

    companion object {
        const val WORK_ID_PREFIX = "co.electriccoin.zcash.migration_transfer"

        fun workId(accountKeyId: String): String = "${WORK_ID_PREFIX}_$accountKeyId"

        fun newWorkRequest(delay: Duration): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<MigrationWorker>()
                .setConstraints(workConstraints())
                .setInitialDelay(delay.toJavaDuration())
                .build()

        private fun workConstraints(): Constraints =
            if (BuildConfig.DEBUG) {
                Constraints.NONE
            } else {
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            }
    }
}
```

- [ ] **Step 2: Update `MigrationDueAlarmScheduler.kt` to take `accountKeyId`, derive request code, and stamp the account extra**

```kotlin
import co.electriccoin.zcash.ui.common.model.accountIdOffset
// ...
class MigrationDueAlarmScheduler(private val context: Context) {
    fun schedule(accountKeyId: String, delay: Duration) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Twig.warn { "MIGRATION_DIAG MigrationDueAlarmScheduler: AlarmManager unavailable — skipping." }
            return
        }
        val triggerAtMillis = System.currentTimeMillis() + delay.inWholeMilliseconds
        Twig.debug { "MIGRATION_DIAG MigrationDueAlarmScheduler: arming ready-to-send alarm for $accountKeyId in $delay" }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent(accountKeyId))
    }

    fun cancel(accountKeyId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(accountKeyId))
    }

    private fun pendingIntent(accountKeyId: String): PendingIntent {
        val intent = Intent(context, MigrationTransferDueReceiver::class.java).apply {
            putExtra(EXTRA_ACCOUNT_KEY_ID, accountKeyId)
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE_BASE + accountIdOffset(accountKeyId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val EXTRA_ACCOUNT_KEY_ID = "co.electriccoin.zcash.migration.account_key_id"
        private const val ALARM_REQUEST_CODE_BASE = 91_0000
    }
}
```

- [ ] **Step 3: Update `MigrationTransferDueReceiver.kt` to read `EXTRA_ACCOUNT_KEY_ID`**

Read the account key id from the intent (`intent.getStringExtra(MigrationDueAlarmScheduler.EXTRA_ACCOUNT_KEY_ID)`) and use it when resolving which account's "ready to send" notification to post / route. If the receiver currently resolves the selected account implicitly, prefer the extra when present so the alarm is attributed to the account it was armed for. (Exact wiring depends on the receiver's current body — keep its existing behavior, only threading the account through.)

- [ ] **Step 4: Update all `MigrationScheduler(...).schedule(...)` / `.cancel(...)` call sites to pass the account key id**

Each caller resolves the selected account's key id as `account.sdkAccount.accountUuid.toStorageKeyId()`:
- `ScheduleNextMigrationWindowUseCase.kt:24` → `migrationScheduler.schedule(accountKeyId, delay)` (inject/resolve the selected account via the same `AccountDataSource`/`GetSelectedWalletAccountUseCase` the surrounding use case already uses).
- `FinalizeMigrationScheduleUseCase.kt:47` → this use case already calls `getSelectedWalletAccount()`; reuse it: `migrationScheduler.schedule(account.sdkAccount.accountUuid.toStorageKeyId(), delayUntilFirstTransfer(sched))`.
- `MigrationProgressVM.kt:181` → resolve current account (VM already has account access) and pass its key id.
- `MigrationWorker.kt:93,108` → the worker already resolves the account via `getOrchardMigrationSdk()`; obtain its `accountUuid` (the same one used to build the SDK) and pass `.toStorageKeyId()`.
- `DebugVM.kt:184` → resolve current account key id and pass it.

- [ ] **Step 5: Compile the module**

Run: `./gradlew :ui-lib:compileZcashmainnetStoreDebugKotlin`
Expected: BUILD SUCCESSFUL. If any call site still calls the old 1-arg `schedule(delay)` / no-arg `cancel()`, fix it to pass the account key id.

- [ ] **Step 6: Run the existing worker test (guards against regressions)**

Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "co.electriccoin.zcash.work.MigrationWorkerTest"`
Expected: PASS. If the test constructs/schedules via `MigrationScheduler`, update it to the new signature.

- [ ] **Step 7: Commit**

```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationScheduler.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationDueAlarmScheduler.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationTransferDueReceiver.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/ScheduleNextMigrationWindowUseCase.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/FinalizeMigrationScheduleUseCase.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/migration/progress/MigrationProgressVM.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationWorker.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/advancedsettings/debug/DebugVM.kt
git commit -m "Scope migration WorkManager job and alarm per account"
```

---

### Task 4: Per-account notification IDs

**Files:**
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/MigrationNotifier.kt`
- Modify callers: `MigrationWorker.kt`, `MigrationTransferDueReceiver.kt`, `DebugVM.kt`, and `MainActivity.kt` if it posts/cancels via the notifier.

**Interfaces:**
- Consumes: `accountIdOffset(accountKeyId: String)` (Task 1).
- Produces: every `MigrationNotifier` notify method gains a leading `accountKeyId: String` parameter, e.g. `notifyTransferComplete(accountKeyId: String, completed: Int, total: Int)`, `notifyManualConfirmationRequired(accountKeyId: String, transferIndex: Int, total: Int)`, `notifyMigrationTorFailure(accountKeyId: String)`, `notifyTransferReadyToSend(accountKeyId: String, transferIndex: Int, total: Int)`, `notifyMigrationPlanInvalid(accountKeyId: String)`, `notifyTransferExpired(accountKeyId: String)`, `notifyMigrationComplete(accountKeyId: String)`. `createChannel()` unchanged.

- [ ] **Step 1: Derive per-account ids inside `MigrationNotifier`**

Replace the fixed `NOTIFICATION_ID_PROGRESS` / `REQUEST_CODE_MIGRATION` / `REQUEST_CODE_TRANSFER_READY` usages with per-account derivations. Add helpers and thread `accountKeyId` through the two `PendingIntent` builders and every `notify(...)` call:

```kotlin
import co.electriccoin.zcash.ui.common.model.accountIdOffset
// ...
private fun mainActivityIntent(accountKeyId: String): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(EXTRA_OPEN_MIGRATION, true)
    }
    return PendingIntent.getActivity(
        context,
        REQUEST_CODE_MIGRATION_BASE + accountIdOffset(accountKeyId),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private fun transferReadyToSendIntent(accountKeyId: String): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(EXTRA_OPEN_TRANSFER_READY, true)
    }
    return PendingIntent.getActivity(
        context,
        REQUEST_CODE_TRANSFER_READY_BASE + accountIdOffset(accountKeyId),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private fun progressNotificationId(accountKeyId: String): Int =
    NOTIFICATION_ID_PROGRESS_BASE + accountIdOffset(accountKeyId)
```

Each `notify*` method takes `accountKeyId`, builds its notification with `mainActivityIntent(accountKeyId)` or `transferReadyToSendIntent(accountKeyId)`, and posts with `progressNotificationId(accountKeyId)`.

Companion constants (bases spaced ≥ `0x10000` apart so `accountIdOffset`'s `0..0xFFFF` ranges never overlap):

```kotlin
companion object {
    const val CHANNEL_ID = "migration_channel"
    const val EXTRA_OPEN_MIGRATION = "co.electriccoin.zcash.migration.open_progress"
    const val EXTRA_OPEN_TRANSFER_READY = "co.electriccoin.zcash.migration.open_transfer_ready"
    private const val NOTIFICATION_ID_PROGRESS_BASE = 0x10_0000      // notification-id namespace
    private const val REQUEST_CODE_MIGRATION_BASE = 0x10_0000        // PendingIntent-request-code namespace
    private const val REQUEST_CODE_TRANSFER_READY_BASE = 0x20_0000   // PendingIntent-request-code namespace
}
```

Use **hex** bases (not decimal) so the required spacing is self-evident and can't be misread: `accountIdOffset` returns `0..0xFFFF`, so bases must be ≥ `0x10000` apart. `0x10_0000` and `0x20_0000` are `0x10_0000` apart — far more than the offset range. (Notification-id and PendingIntent-request-code are separate Android namespaces, so `NOTIFICATION_ID_PROGRESS_BASE` may share a value with a request-code base without colliding; within the request-code namespace, `MIGRATION` and `TRANSFER_READY` bases differ by `0x10_0000`.) **Do not use decimal `90_0000`/`91_0000` — those differ by only 10,000, which overlaps.**

- [ ] **Step 2: Update callers to pass the account key id**

For each `migrationNotifier.notify*(...)` call in `MigrationWorker.kt`, `MigrationTransferDueReceiver.kt`, `DebugVM.kt`, `MainActivity.kt`: resolve the relevant account's key id (`accountUuid.toStorageKeyId()`) and pass it as the first argument. The worker and receiver already have the account in context (Task 3).

- [ ] **Step 3: Compile the module**

Run: `./gradlew :ui-lib:compileZcashmainnetStoreDebugKotlin`
Expected: BUILD SUCCESSFUL. Fix any remaining call site still using the old zero-account signatures.

- [ ] **Step 4: Commit**

```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/MigrationNotifier.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationWorker.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationTransferDueReceiver.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/advancedsettings/debug/DebugVM.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/ui/MainActivity.kt
git commit -m "Scope migration notification ids per account"
```

---

### Task 5: In-memory hand-off repos — account-tag guard

**Files:**
- Modify: `ui-lib/src/main/java/co/electriccoin/zcash/ui/common/repository/PendingKeystoneMigrationPcztsRepository.kt`
- Modify: `PendingMigrationScheduleRepository.kt`
- Modify: `RestartMigrationScheduleRepository.kt`
- Modify: `PendingMigrationTorFailureDecisionRepository.kt`
- Modify their VM call sites (the VMs that call `set`/`get`/`consume`/`clear` — found via `grep -rn "PendingKeystoneMigrationPcztsRepository\|PendingMigrationScheduleRepository\|RestartMigrationScheduleRepository\|PendingMigrationTorFailureDecisionRepository"` during implementation).
- Test: `ui-lib/src/test/java/co/electriccoin/zcash/ui/common/repository/MigrationHandoffAccountGuardTest.kt` (create)

**Interfaces:**
- Consumes: nothing new (the caller supplies the account key id string).
- Produces (per repo, mirror this shape):
  - `PendingMigrationScheduleRepository`: `set(accountKeyId: String, schedule: MigrationSchedule)`, `get(accountKeyId: String): MigrationSchedule?` (returns `null` and clears when the stored account key id differs), `clear()`.
  - `RestartMigrationScheduleRepository`: `set(accountKeyId: String, schedule: MigrationSchedule)`, `consume(accountKeyId: String): MigrationSchedule?` (returns-and-clears; also returns `null` when account differs).
  - `PendingKeystoneMigrationPcztsRepository`: `set(accountKeyId: String, pczts: PendingKeystoneMigrationPczts)`, `get(accountKeyId: String): PendingKeystoneMigrationPczts?`, `clear()`.
  - `PendingMigrationTorFailureDecisionRepository`: `set(accountKeyId: String, useTor: Boolean)`, `decision(accountKeyId: String): StateFlow<Boolean?>` OR keep `decision: StateFlow<Boolean?>` but store `Pair(accountKeyId, useTor)` and expose a `decisionFor(accountKeyId): Boolean?` accessor; simplest: store the pair and have `set(accountKeyId, useTor)` + `consume(accountKeyId): Boolean?`. (Pick the consume-style accessor to match how the call site reads it; keep the change minimal.)

Internally each repo holds `MutableStateFlow<Pair<String, T>?>` and compares the stored key id to the caller's before returning the payload.

- [ ] **Step 1: Write the failing test (covers the guard for all four repos)**

```kotlin
package co.electriccoin.zcash.ui.common.repository

import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class MigrationHandoffAccountGuardTest {

    @Test
    fun pendingScheduleReturnsNullForDifferentAccount() {
        val repo = PendingMigrationScheduleRepositoryImpl()
        val schedule = mockk<cash.z.ecc.android.sdk.MigrationSchedule>()
        repo.set("accountA", schedule)
        assertNull(repo.get("accountB"))
    }

    @Test
    fun pendingScheduleReturnsValueForSameAccount() {
        val repo = PendingMigrationScheduleRepositoryImpl()
        val schedule = mockk<cash.z.ecc.android.sdk.MigrationSchedule>()
        repo.set("accountA", schedule)
        assertSame(schedule, repo.get("accountA"))
    }

    @Test
    fun restartScheduleConsumeReturnsNullForDifferentAccount() {
        val repo = RestartMigrationScheduleRepositoryImpl()
        val schedule = mockk<cash.z.ecc.android.sdk.MigrationSchedule>()
        repo.set("accountA", schedule)
        assertNull(repo.consume("accountB"))
        // and the value is still consumable by the right account afterwards is NOT required;
        // mismatched consume clears — assert it's gone for A too
        assertNull(repo.consume("accountA"))
    }

    @Test
    fun keystonePcztsReturnsNullForDifferentAccount() {
        val repo = PendingKeystoneMigrationPcztsRepositoryImpl()
        val pczts = PendingKeystoneMigrationPczts(
            requestId = byteArrayOf(1),
            splitUnsignedPczt = null,
            transferUnsignedPczts = emptyList(),
        )
        repo.set("accountA", pczts)
        assertNull(repo.get("accountB"))
        assertSame(pczts, repo.get("accountA"))
    }
}
```

(If `PendingKeystoneMigrationPcztsRepositoryImpl` / `PendingMigrationScheduleRepositoryImpl` names differ, use the actual impl class names.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "co.electriccoin.zcash.ui.common.repository.MigrationHandoffAccountGuardTest"`
Expected: FAIL — new `set(accountKeyId, …)` / `get(accountKeyId)` signatures don't exist.

- [ ] **Step 3: Implement the guard in each repo**

Example for `PendingMigrationScheduleRepository.kt` (apply the analogous change to the other three):

```kotlin
interface PendingMigrationScheduleRepository {
    fun set(accountKeyId: String, schedule: MigrationSchedule)
    fun get(accountKeyId: String): MigrationSchedule?
    fun clear()
}

class PendingMigrationScheduleRepositoryImpl : PendingMigrationScheduleRepository {
    private val pending = MutableStateFlow<Pair<String, MigrationSchedule>?>(null)

    override fun set(accountKeyId: String, schedule: MigrationSchedule) {
        pending.value = accountKeyId to schedule
    }

    override fun get(accountKeyId: String): MigrationSchedule? {
        val current = pending.value ?: return null
        return if (current.first == accountKeyId) {
            current.second
        } else {
            pending.value = null
            null
        }
    }

    override fun clear() {
        pending.value = null
    }
}
```

For `RestartMigrationScheduleRepository`, `consume(accountKeyId)` reads-and-clears and returns `null` on mismatch (also clearing). For `PendingKeystoneMigrationPcztsRepository`, same `get(accountKeyId)` guard. For `PendingMigrationTorFailureDecisionRepository`, store `Pair(accountKeyId, Boolean)`; `consume(accountKeyId): Boolean?` returns `null` on mismatch.

- [ ] **Step 4: Update VM call sites to pass the account key id**

Thread `account.sdkAccount.accountUuid.toStorageKeyId()` into every `set`/`get`/`consume` call in the consuming VMs (e.g. `MigrationReviewVM`, `MigrationKeystoneSignVM`, `MigrationKeystoneScanVM`, `MigrationTransferInvalidVM`, `MigrationSendingVM`, and any Tor-failure-sheet caller). Each VM already resolves its selected account.

- [ ] **Step 5: Run test + compile**

Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest --tests "co.electriccoin.zcash.ui.common.repository.MigrationHandoffAccountGuardTest"`
Expected: PASS.
Run: `./gradlew :ui-lib:compileZcashmainnetStoreDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add ui-lib/src/main/java/co/electriccoin/zcash/ui/common/repository/PendingKeystoneMigrationPcztsRepository.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/ui/common/repository/PendingMigrationScheduleRepository.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/ui/common/repository/RestartMigrationScheduleRepository.kt \
        ui-lib/src/main/java/co/electriccoin/zcash/ui/common/repository/PendingMigrationTorFailureDecisionRepository.kt \
        ui-lib/src/test/java/co/electriccoin/zcash/ui/common/repository/MigrationHandoffAccountGuardTest.kt
# plus the modified VM files
git commit -m "Guard in-memory migration hand-off repos by account"
```

---

### Task 6: Full-module verification + manual emulator check

**Files:** none (verification only).

- [ ] **Step 1: Full unit test run for the module**

Run: `./gradlew :ui-lib:testZcashmainnetStoreDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures. Investigate and fix any regression before proceeding.

- [ ] **Step 2: Lint/detekt if part of the standard gate**

Run: `./gradlew :ui-lib:detektZcashmainnetStoreDebug` (or the repo's configured static-analysis task).
Expected: no new violations. Fix formatting/style issues introduced by the change.

- [ ] **Step 3: Build the FOSS debug APK for the emulator**

Run: `./gradlew :app:assembleZcashtestnetFossDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual cross-account verification (per project convention — install+launch testnet/foss/debug)**

Install and launch, then with a wallet that has both a Zashi and a Keystone account:
1. Schedule/start a migration transfer on the Zashi account.
2. Switch to the Keystone account; schedule/act on its migration.
3. Confirm the Zashi account's scheduled WorkManager job and alarm are NOT cancelled, its home banner still reflects Zashi's plan, and any posted notification is attributed to the correct account (not overwritten).
4. Confirm switching accounts flips the migration UI to the correct per-account state (banner %, Keystone round).

Document the observed result. If any cross-account clobber remains, file a follow-up rather than silently closing.

- [ ] **Step 5: Final commit (if any verification fixes were needed) and summary**

```bash
git add -A
git commit -m "Fix issues surfaced during migration account-scoping verification"
```
