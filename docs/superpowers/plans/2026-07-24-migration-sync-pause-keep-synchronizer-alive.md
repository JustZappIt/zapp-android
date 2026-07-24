# Keep Synchronizer alive (paused) during migration sync-block — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** During a migration sync-block, keep the wallet Synchronizer alive-but-paused (instead of tearing it down to null) so the app shows last-known balance/state and the migration banner, rather than a stuck loading screen.

**Architecture:** Add `pause()`/`resume()` to the SDK's `CloseableSynchronizer`; `SlipstreamSynchronizer` implements them via the engine's existing `stopPolling()`/`startPolling()` (pause without teardown) and reports a settled `SYNCED` status while paused. `WalletCoordinator` stops folding `isSyncBlocked` into the Synchronizer's rebuild key and instead drives `pause()`/`resume()` on the live instance, so the public `synchronizer` StateFlow stays non-null across a block.

**Tech Stack:** Kotlin, kotlinx.coroutines Flow, JUnit + plain Mockito (`mockito-inline`) — all changes in the local included-build SDK at `../zcash-android-wallet-sdk` (sibling of `zashi-android`).

## Global Constraints

- SDK lives at `/Users/micutad/Projects/AndroidStudioProjects/zcash-android-wallet-sdk` (sibling dir), wired as an included build via `SDK_INCLUDED_BUILD_PATH=../zcash-android-wallet-sdk`.
- The app runs `isSlipstreamEnabled = true` — `SlipstreamSynchronizer` is the only live sync path; `SdkSynchronizer` must stay compilable but its pause/resume are best-effort.
- SDK unit tests use plain Mockito (`mock(...)`, `verify(...)`, `` `when` ``), NOT mockk. Follow `SlipstreamSynchronizerLifecycleTest.kt`.
- Do NOT change the `isSyncBlocked` decision (height-based overdue + 10-min buffer) or any Rust.
- Pause = `engine.stopPolling()` only (light; keep engine warm & readable). Never `stop`/`free`/`shutdown` on pause.
- Status while paused = `Synchronizer.Status.SYNCED` (settled) so app UI stays normal.
- SDK build/tests run from the SDK repo: `./gradlew :sdk-lib:testDebugUnitTest --tests "..."`. App build/install uses `:app:installZcashtestnetInternalDebug` from the app repo.

---

### Task 1: `pause()`/`resume()` on the Synchronizer

Adds the pause capability to the interface and both implementations. All three files must land together to compile (adding to the interface forces both implementers).

**Files:**
- Modify: `sdk-lib/src/main/java/cash/z/ecc/android/sdk/Synchronizer.kt` (`CloseableSynchronizer` interface, ~line 1231)
- Modify: `sdk-lib/src/main/java/com/zodl/slipstream/SlipstreamSynchronizer.kt` (status field ~176, `onForeground` ~761, add pause/resume)
- Modify: `sdk-lib/src/main/java/cash/z/ecc/android/sdk/SdkSynchronizer.kt` (implements `CloseableSynchronizer`, ~line 167)
- Test: `sdk-lib/src/test/java/com/zodl/slipstream/SlipstreamSynchronizerLifecycleTest.kt` (reuse its `buildSynchronizer` helper)

**Interfaces:**
- Produces: `CloseableSynchronizer.pause()`, `CloseableSynchronizer.resume()` (both `fun ...(): Unit`, non-suspend). `SlipstreamSynchronizer.status` emits `Synchronizer.Status.SYNCED` while paused.
- Consumes: `SlipstreamEngine.stopPolling()`, `SlipstreamEngine.startPolling()`, `SlipstreamEngine.isRunning` (existing).

- [ ] **Step 1: Write the failing tests** (append to `SlipstreamSynchronizerLifecycleTest.kt`)

```kotlin
    @Test
    fun pause_stops_polling_without_tearing_the_engine_down() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            synchronizer.pause()

            runBlocking {
                verify(engine, timeout(TIMEOUT_MS)).stopPolling()
                verify(engine, after(SETTLE_MS).never()).stop()
                verify(engine, after(SETTLE_MS).never()).free()
                verify(engine, after(SETTLE_MS).never()).shutdown()
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun resume_restarts_polling() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            synchronizer.pause()
            clearInvocations(engine)

            synchronizer.resume()

            runBlocking { verify(engine, timeout(TIMEOUT_MS)).startPolling() }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun status_reports_synced_while_paused_then_reverts_after_resume() {
        val engine = mock(SlipstreamEngine::class.java)
        val engineStatus = MutableStateFlow(Synchronizer.Status.SYNCING)
        val key = newKey()
        // Override the default SYNCED stub so we can prove the wrap, not the stub.
        val synchronizer = buildSynchronizer(engine = engine, key = key, engineStatusOverride = engineStatus)
        try {
            runBlocking {
                assertEquals(Synchronizer.Status.SYNCING, synchronizer.status.first())
                synchronizer.pause()
                assertEquals(Synchronizer.Status.SYNCED, synchronizer.status.first())
                synchronizer.resume()
                assertEquals(Synchronizer.Status.SYNCING, synchronizer.status.first())
            }
        } finally {
            InstanceGuard.release(key)
        }
    }

    @Test
    fun on_foreground_while_paused_does_not_restart_polling() {
        val engine = mock(SlipstreamEngine::class.java)
        val key = newKey()
        val synchronizer = buildSynchronizer(engine = engine, key = key)
        try {
            `when`(engine.isRunning).thenReturn(true)
            synchronizer.pause()
            clearInvocations(engine)

            synchronizer.onForeground()

            runBlocking { verify(engine, after(SETTLE_MS).never()).startPolling() }
        } finally {
            InstanceGuard.release(key)
        }
    }
```

Add these imports at the top of the test file if missing: `import kotlinx.coroutines.flow.MutableStateFlow` (already present), `import kotlin.test.assertEquals`.

Extend the `buildSynchronizer` helper signature to accept an optional status override — change its `engine.status` stub line to use it:

```kotlin
    private fun buildSynchronizer(
        engine: SlipstreamEngine = mock(SlipstreamEngine::class.java),
        backend: Backend = mock(Backend::class.java),
        walletClient: CombinedWalletClient = mock(CombinedWalletClient::class.java),
        transactionsController: TransactionsController = mock(TransactionsController::class.java),
        key: SlipstreamKey = newKey(),
        startBirthday: BlockHeight = BlockHeight.new(STARTING_BIRTHDAY_VALUE),
        engineStatusOverride: MutableStateFlow<Synchronizer.Status>? = null
    ): SlipstreamSynchronizer {
        `when`(engine.status).thenReturn(engineStatusOverride ?: MutableStateFlow(Synchronizer.Status.SYNCED))
        // ... rest unchanged ...
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from the SDK repo):
```bash
cd /Users/micutad/Projects/AndroidStudioProjects/zcash-android-wallet-sdk
./gradlew :sdk-lib:testDebugUnitTest --tests "com.zodl.slipstream.SlipstreamSynchronizerLifecycleTest" 2>&1 | tail -20
```
Expected: FAIL — `pause`/`resume` unresolved (compile error).

- [ ] **Step 3: Add `pause()`/`resume()` to the `CloseableSynchronizer` interface**

In `Synchronizer.kt`, change the interface (~1231):
```kotlin
interface CloseableSynchronizer :
    Synchronizer,
    Closeable {
    /**
     * Pauses block-scanning without tearing the synchronizer down: the engine's poll loop stops
     * (no new network sync — used to decorrelate wallet sync from a migration broadcast) but the
     * instance and all its StateFlows (status, balances, heights) stay alive and readable. While
     * paused, [status] reports [Synchronizer.Status.SYNCED]. Idempotent.
     */
    fun pause()

    /** Resumes block-scanning after [pause]. Idempotent. */
    fun resume()
}
```

- [ ] **Step 4: Implement pause/resume + status wrap + onForeground gating in `SlipstreamSynchronizer`**

Add a paused-state flow (near the other private fields, after `closed`):
```kotlin
    /** True while a migration sync-block is pausing this synchronizer (see [pause]/[resume]). */
    private val migrationPaused = MutableStateFlow(false)
```

Change the `status` field (~176) from `= engine.status` to wrap it:
```kotlin
    override val status: Flow<Synchronizer.Status> =
        combine(engine.status, migrationPaused) { status, paused ->
            if (paused) Synchronizer.Status.SYNCED else status
        }
```

Add the methods (place near `onForeground`/`onBackground`):
```kotlin
    override fun pause() {
        if (closed.get()) return
        migrationPaused.value = true
        launchGuarded("pause") { engine.stopPolling() }
    }

    override fun resume() {
        if (closed.get()) return
        migrationPaused.value = false
        launchGuarded("resume") { engine.startPolling() }
    }
```

Gate `onForeground` so it does not resume polling while paused (~768):
```kotlin
    override fun onForeground() {
        if (closed.get()) return
        launchGuarded("onForeground") {
            lazyTorClient?.ifCreated { it.setDormant(TorDormantMode.NORMAL) }
            if (!engine.isRunning) {
                engine.start(ufvk = null, birthday = startBirthday.value)
            }
            if (!migrationPaused.value) {
                engine.startPolling()
            }
        }
    }
```

Ensure `combine` is imported: `import kotlinx.coroutines.flow.combine` (add if missing).

- [ ] **Step 5: Add no-op `pause()`/`resume()` to `SdkSynchronizer`**

In `SdkSynchronizer.kt`, add (best-effort no-op — the legacy path is never live in this app):
```kotlin
    // Migration sync-pause is only wired for the Slipstream engine; the legacy processor path
    // is never active in this app (isSlipstreamEnabled = true). Kept as a no-op so the
    // CloseableSynchronizer contract stays total.
    override fun pause() = Unit

    override fun resume() = Unit
```

- [ ] **Step 6: Run the tests to verify they pass**

Run:
```bash
cd /Users/micutad/Projects/AndroidStudioProjects/zcash-android-wallet-sdk
./gradlew :sdk-lib:testDebugUnitTest --tests "com.zodl.slipstream.SlipstreamSynchronizerLifecycleTest" 2>&1 | tail -20
```
Expected: PASS (all lifecycle tests including the 4 new ones).

- [ ] **Step 7: Commit** (in the SDK repo)

```bash
cd /Users/micutad/Projects/AndroidStudioProjects/zcash-android-wallet-sdk
git add sdk-lib/src/main/java/cash/z/ecc/android/sdk/Synchronizer.kt \
        sdk-lib/src/main/java/com/zodl/slipstream/SlipstreamSynchronizer.kt \
        sdk-lib/src/main/java/cash/z/ecc/android/sdk/SdkSynchronizer.kt \
        sdk-lib/src/test/java/com/zodl/slipstream/SlipstreamSynchronizerLifecycleTest.kt
git commit -m "Add pause/resume to CloseableSynchronizer (Slipstream: stopPolling, SYNCED-while-paused)"
```

---

### Task 2: `WalletCoordinator` drives pause/resume instead of tearing down

Stop letting `isSyncBlocked` rebuild/close the Synchronizer; keep it alive and pause/resume it.

**Files:**
- Modify: `sdk-incubator-lib/src/main/java/cash/z/ecc/android/sdk/WalletCoordinator.kt` (flow ~92–190)

**Interfaces:**
- Consumes: `CloseableSynchronizer.pause()`/`resume()` from Task 1.

- [ ] **Step 1: Drop `isSyncBlocked` from the Synchronizer rebuild key**

Remove `isSyncBlocked` from the `combine(...)` at ~95–108 and from `SynchronizerLockoutInternalState` (so toggling it no longer re-fires `flatMapLatest` and closes the synchronizer). The combine becomes the 4-arg form over `persistableWallet, synchronizerLockoutId, isTorEnabled, isExchangeRateEnabled`. Remove `isSyncBlocked` from the destructuring at ~114.

- [ ] **Step 2: Remove the `Blocked` state**

Delete `object Blocked : InternalSynchronizerStatus()` (~89), the `else if (isSyncBlocked) → flowOf(InternalSynchronizerStatus.Blocked)` branch (~117–118), and the `InternalSynchronizerStatus.Blocked -> null` arm in the `synchronizer` map (~181). The public `synchronizer` no longer goes null for a migration block.

- [ ] **Step 3: Drive pause/resume from inside the live-synchronizer callbackFlow**

Inside the `callbackFlow` that owns `closeableSynchronizer` (after `trySend(InternalSynchronizerStatus.Available(closeableSynchronizer))`, before `awaitClose`), collect `isSyncBlocked` and pause/resume the instance. `closeableSynchronizer` is a `CloseableSynchronizer`, so `pause()`/`resume()` are available:

```kotlin
                    trySend(InternalSynchronizerStatus.Available(closeableSynchronizer))

                    // Keep this Synchronizer alive across migration sync-blocks: instead of tearing
                    // it down (which nulled the app's balance/snapshot into a stuck loading state),
                    // pause its polling for decorrelation and resume when the block clears. Scoped to
                    // this callbackFlow so it is torn down with the synchronizer (wallet change/lockout).
                    val pauseJob = launch {
                        isSyncBlocked.distinctUntilChanged().collect { blocked ->
                            if (blocked) closeableSynchronizer.pause() else closeableSynchronizer.resume()
                        }
                    }

                    awaitClose {
                        Twig.info { "Closing flow and stopping synchronizer" }
                        pauseJob.cancel()
                        closeableSynchronizer.close()
                    }
```

Ensure `launch` and `distinctUntilChanged` are imported (`kotlinx.coroutines.launch`, `kotlinx.coroutines.flow.distinctUntilChanged`). The `callbackFlow` block is a `ProducerScope`, so `launch` runs in its scope.

- [ ] **Step 4: Build the SDK to verify it compiles**

Run:
```bash
cd /Users/micutad/Projects/AndroidStudioProjects/zcash-android-wallet-sdk
./gradlew :sdk-incubator-lib:compileDebugKotlin :sdk-lib:compileDebugKotlin 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** (SDK repo)

```bash
cd /Users/micutad/Projects/AndroidStudioProjects/zcash-android-wallet-sdk
git add sdk-incubator-lib/src/main/java/cash/z/ecc/android/sdk/WalletCoordinator.kt
git commit -m "WalletCoordinator: pause/resume the live Synchronizer on sync-block instead of closing it"
```

---

### Task 3: App-side verification + manual emulator check

No app code change is expected. Verify the fix end-to-end and audit status consumers.

**Files:**
- Review only: `ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/GetHomeMessageUseCase.kt`, `ui-lib/.../datasource/WalletSnapshotDataSource.kt`, `ui-lib/.../datasource/AccountDataSource.kt`, and any `Synchronizer.Status.SYNCED`/`SYNCING` consumers.

- [ ] **Step 1: Audit status consumers for paused-SYNCED assumptions**

Run:
```bash
cd /Users/micutad/Projects/AndroidStudioProjects/zashi-android
grep -rn "Status.SYNCED\|Status.SYNCING\|isSyncing\|status ==" ui-lib/src/main/java | grep -i "sync\|status" | head -40
```
For each consumer, confirm that treating a paused synchronizer as `SYNCED` for the short migration-block window is harmless (it shows last-known data and the migration banner). Note any that gate spend/send readiness on `SYNCED` — during a migration block the migration flow already governs spending, so this is expected safe, but record findings.

- [ ] **Step 2: Build and install the app**

Run:
```bash
cd /Users/micutad/Projects/AndroidStudioProjects/zashi-android
./gradlew :app:installZcashtestnetInternalDebug -q 2>&1 | tail -6
```
Expected: `Installed on ... devices.`

- [ ] **Step 3: Manual emulator verification**

Trigger a migration transfer broadcast (fast-debug schedule → wait for a transfer to become due and broadcast). During the ~10-minute post-broadcast privacy buffer, confirm on the home screen:
- the balance shows the last-known amount (NOT a stuck loading spinner),
- the migration banner/status is visible (conveys migration in progress / transfer sent),
- after the block clears, normal sync resumes (status returns to real SYNCING→SYNCED).

Capture the relevant `MIGRATION_DIAG` / status logs as evidence:
```bash
adb -s emulator-5554 logcat -d 2>/dev/null | grep -iE "transfer sent|isSyncBlock|SYNCED|SYNCING|drove sync burst" | tail -20
```

- [ ] **Step 4: Commit any audit notes** (only if code changes were needed)

If the audit found a consumer needing a guard, implement it TDD-first and commit separately. Otherwise no commit for this task.

---

## Self-Review notes

- **Spec coverage:** SlipstreamSynchronizer pause/resume + SYNCED-while-paused (Task 1), CloseableSynchronizer interface + SdkSynchronizer no-op (Task 1), WalletCoordinator restructure removing Blocked/null (Task 2), app verification of status consumers + manual check (Task 3). All spec sections covered.
- **onForeground/onBackground interaction:** handled — `onForeground` skips `startPolling()` while `migrationPaused` (Task 1 Step 4), so an app foreground during a block does not defeat the pause.
- **Cold-start subtlety:** the spec's warning about `isSyncBlocked`'s first async value is preserved — it is no longer part of the rebuild key, so it cannot cancel an in-flight `Synchronizer.new()`.
