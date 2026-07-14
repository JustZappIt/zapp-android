# Slipstream on Zodl Android — integration guide

**Status: spec-first branch. DO NOT MERGE until `com.zodl.slipstream:slipstream-android` is
published.** Everything here is designed against the published Zcash Android SDK
(`cash.z.ecc.android:zcash-android-sdk`) and the frozen Slipstream host contract, and was
verified line-by-line against this repository at commit `05cb52e8` and the SDK at
`f386369e`. Nothing in this branch has been compiled on an Android toolchain — the binding
artifact does not exist yet. Read this as the plan a reviewer signs off on now, and the
seam an engineer wires up once the artifact ships.

Companion files in this branch:
- `code/` — the actual diffs (flag, gradle, the `ConfigurationRepository` member, the
  `SynchronizerProviderImpl` construction branch, and the cast-site fixes). Each diff header
  carries the DO-NOT-MERGE banner and the `git apply -p1` command.
- The public sync-engine RFC lives in the SDK repo (`zcash-android-wallet-sdk`) under
  `docs/slipstream/` — that document defines the poll/SQL host pattern this integration
  consumes. This file is the app-side of that RFC.

---

## 1. What Slipstream is

Slipstream is a high-performance Zcash **sync engine** (block download, scan, persistence,
transaction enhancement, chain-tip following, mempool watch, reorg recovery, transparent
UTXO refresh). It is the *read side* of a light wallet only: it never sees seeds or spending
keys, it does not build or broadcast transactions, and it does not own any UI. It writes the
**same standard `zcash_client_sqlite` `data.sqlite3`** that the Zcash SDK already uses, plus
two read-only SQL views, and exposes its state as a by-value snapshot you poll on a timer.
Everything about keys, PCZT/proposal creation, broadcasting, exchange rates, fiat, and UI
stays exactly where it is in this app today. Slipstream has shipped **in production in the
Zodl iOS and macOS wallets** since the current release train (it is the default sync engine
there), against a byte-for-byte identical engine core; the Android artifact wraps that same
core behind a hand-written JNI binding.

The engine's one guiding rule, which every parity item below restates: **the numbers the
engine exports are already correct at every phase — render them, never re-derive them.**
Progress, the recovery flag, balances, and tip-freshness each exist because some host once
computed its own version and shipped a bug.

## 2. The dependency

The engine reaches Android **only** as a prebuilt binary AAR — per-ABI `libslipstream.so`
plus the Kotlin host layer, whose sole published entry point is the `SlipstreamSynchronizer`
adapter (a `cash.z.ecc.android.sdk.CloseableSynchronizer`) and its companion
`new(...)` factory. This is the same trust model iOS already accepts (SPM pulls a prebuilt
`libzcashlc.xcframework`); Android pulls a prebuilt AAR.

| | |
|---|---|
| Group | `com.zodl.slipstream` |
| Artifact | `slipstream-android` |
| Kotlin package | `com.zodl.slipstream` |
| Target channel | Maven Central (`com.zodl`) |
| Interim channel | public `zodl-inc/slipstream-android-releases` maven-layout repo |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` (this app's four) |
| minSdk / targetSdk / NDK | 27 / 36 / r27 — identical to this app (`gradle.properties`) |

Version naming ties the AAR to the engine tag it was built from:
`slipstream-android 0.6.x-androidN` is built from engine contract `v0.6.x`. v1 targets the
published **v0.6.0** contract; `set_alternate_servers` (server-selection consent UX, for iOS
parity) is the first fast-follow.

Interim Maven block (mirror of the iOS "binary off a releases channel" model), added to
`dependencyResolutionManagement { repositories { … } }` in `settings.gradle.kts`. The
`includeGroup(...)` filter is guarded with `isRepoRestrictionEnabled` exactly like the
adjacent Sonatype blocks (`settings.gradle.kts:112-131`), so it can only ever serve
`com.zodl.slipstream`:

```kotlin
maven("https://raw.githubusercontent.com/zodl-inc/slipstream-android-releases/main/maven") {
    if (isRepoRestrictionEnabled) {
        content {
            includeGroup("com.zodl.slipstream")
        }
    }
}
```

See `code/02-gradle.patch` for the exact repository + version-catalog + `ui-lib` dependency
wiring. The final URL is confirmed at first publish; until then the whole branch stays
DO-NOT-MERGE and this repo 404s (which is why the flag defaults off).

## 3. The availability flag

The engine is selected by a runtime boolean that defaults **off**, following this app's exact
`ConfigurationEntries` pattern (`ui-lib/.../ui/configuration/ConfigurationEntries.kt`). Add:

```kotlin
val IS_SLIPSTREAM_AVAILABLE = BooleanConfigurationEntry(ConfigKey("is_slipstream_available"), false)
```

It reads through the same `MergingConfigurationProvider` the app already builds in
`AndroidConfigurationFactory.new()`, so:
- **Default false** — a fresh build, and any device that has not received remote config,
  runs today's SDK engine. This is the kill switch's safe state.
- **Debug intent override** — in `BuildConfig.DEBUG` builds the `IntentConfigurationProvider`
  is first in the merge, so dogfooders flip it with an intent extra (the same mechanism the
  team already uses for config flags) with no rebuild.
- **Remote config** — a cloud provider (when added) can canary it to a fraction of users and
  pull it instantly.

`ConfigurationRepository` then derives an `isSlipstreamAvailable: StateFlow<Boolean?>` from that
flow, mirroring its existing `isFlexaAvailable` member (`ConfigurationRepository.kt:47-59`) —
`null` while config loads, then the flag value. `SynchronizerProviderImpl` consumes it as one input
of the `combine` that selects the engine (see §4). Because the flag is a **live flow input**, the
teardown-rebuild machinery below *can* switch engines mid-session, but that live swap is deferred
as a product decision (support surface + analytics); **v1 selects at launch** — a stable remote
value simply never changes mid-session, so a flip takes effect on the next wallet load.

## 4. The construction seam (`SynchronizerProviderImpl` branch)

### 4.1 How the Synchronizer is built today

This app never constructs a `Synchronizer` directly. The SDK's `WalletCoordinator` owns the
whole lifecycle and exposes it as a `StateFlow<Synchronizer?>`. The app builds the coordinator
with one factory and registers it as a Koin singleton, then wraps that flow in its own
`SynchronizerProviderImpl` — the single seam every consumer reads through.

`ui-lib/src/main/java/co/electriccoin/zcash/global/WalletCoordinatorFactory.kt` (verbatim,
lines 10-23):

```kotlin
internal operator fun WalletCoordinator.Companion.invoke(
    context: Context,
    persistableWalletProvider: PersistableWalletProvider,
    isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
    isExchangeRateEnabledStorageProvider: IsExchangeRateEnabledStorageProvider
): WalletCoordinator =
    WalletCoordinator(
        context = context,
        persistableWallet = persistableWalletProvider.persistableWallet,
        accountName = context.getString(R.string.accounts_zashi),
        keySource = "zashi",
        isTorEnabled = isTorEnabledStorageProvider.observe(),
        isExchangeRateEnabled = isExchangeRateEnabledStorageProvider.observe()
    )
```

`ui-lib/src/main/java/co/electriccoin/zcash/di/CoreModule.kt` (verbatim, lines 15-23) — **left
untouched by this branch**; `WalletCoordinator` stays the SDK-coordinator singleton:

```kotlin
val coreModule =
    module {
        singleOf(WalletCoordinator::invoke)
        singleOf(::StandardPreferenceProvider)
        singleOf(::EncryptedPreferenceProvider)
        single { BiometricManager.from(get()) }
        factory { AndroidConfigurationFactory.new() }
        singleOf(::NavigationRouterImpl) bind NavigationRouter::class
    }
```

Downstream, `SynchronizerProviderImpl` (`ui-lib/.../common/provider/SynchronizerProvider.kt`,
line 55) sources its `synchronizer: StateFlow<Synchronizer?>` from
`walletCoordinator.synchronizer`, and the whole app consumes that flow. So **the one place the
sync engine is chosen is the app-owned provider that wraps the coordinator's flow.**

### 4.2 The branch belongs in the provider, not a new coordinator

`cash.z.ecc.android.sdk.WalletCoordinator` is a `final` class whose `synchronizer` flow
hard-calls the SDK's `Synchronizer.new(...)` (SDK `sdk-incubator-lib/.../WalletCoordinator.kt:104`),
so there is no override point on the coordinator itself. But the app never has to touch the
coordinator: `SynchronizerProviderImpl` already `flatMapLatest`s `walletCoordinator.synchronizer`
and re-publishes it, and it is the single seam every call site reads through. We put the engine
choice **there** — one app-owned class, no new interface, `CoreModule` unchanged. When the flag is
OFF the provider delegates to `walletCoordinator.synchronizer` verbatim; when ON it builds
`SlipstreamSynchronizer.new(...)` itself and never collects `walletCoordinator.synchronizer`, so
the SDK's `CompactBlockProcessor` is never constructed.

### 4.3 The construction branch (`SynchronizerProviderImpl`)

The provider gains a `Context`, the Tor/exchange-rate storage providers, and
`ConfigurationRepository`, then selects the engine with the **same
`combine`/`flatMapLatest`/`channelFlow`/`awaitClose` teardown-rebuild shape** the SDK coordinator
uses (`WalletCoordinator.kt:96-124`). The engine call is `WalletCoordinator`'s `Synchronizer.new(...)`
with a single token changed — `Synchronizer.new` → `SlipstreamSynchronizer.new` (the artifact's
companion factory mirrors the SDK's parameter-for-parameter):

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
override val synchronizer: StateFlow<Synchronizer?> =
    combine(
        configurationRepository.isSlipstreamAvailable, // Boolean?  (null while config loads)
        persistableWalletProvider.persistableWallet    // PersistableWallet?
    ) { slipstreamOn, wallet -> slipstreamOn to wallet }
        .flatMapLatest { (slipstreamOn, wallet) ->
            if (slipstreamOn == true && wallet != null) {
                // ENGINE branch: build our adapter; DO NOT collect walletCoordinator.synchronizer.
                channelFlow<Synchronizer?> {
                    val closeable: CloseableSynchronizer =
                        SlipstreamSynchronizer.new(
                            context = context,
                            zcashNetwork = wallet.network,
                            lightWalletEndpoint = wallet.endpoint,
                            birthday = wallet.birthday,
                            setup =
                                AccountCreateSetup(
                                    accountName = context.getString(R.string.accounts_zashi),
                                    keySource = "zashi",
                                    seed = FirstClassByteArray(wallet.seedPhrase.toByteArray())
                                ),
                            walletInitMode = wallet.walletInitMode,
                            isTorEnabled = isTorEnabledStorageProvider.observe().first() == true,
                            isExchangeRateEnabled = isExchangeRateEnabledStorageProvider.observe().first() == true
                        )
                    val pipeline = initializeErrorHandling(closeable)
                    launch { pipeline.collect { new -> error.update { new } } }
                    send(closeable)
                    awaitClose {
                        closeable.onProcessorErrorHandler = null
                        closeable.onProcessorErrorResolved = null
                        closeable.onSetupErrorHandler = null
                        closeable.onChainErrorHandler = null
                        closeable.close() // symmetric with WalletCoordinator.awaitClose
                    }
                }
            } else {
                // SDK branch: verbatim delegation (Tor, lockout, exchange-rate all preserved).
                walletCoordinator.synchronizer.flatMapLatest { sdk -> /* existing wrapper */ }
            }
        }.stateIn(scope, SharingStarted.Lazily, initialValue = null)
```

The full diff (including the unchanged SDK-branch wrapper) is `code/04-SynchronizerProvider.kt.patch`.
The marked `import com.zodl.slipstream.SlipstreamSynchronizer` is the only line that needs the
unpublished artifact.

`SlipstreamSynchronizer.new(...)` is the artifact's sole published factory; it and the fact that it
returns a `cash.z.ecc.android.sdk.CloseableSynchronizer` implementing the full `Synchronizer`
surface are specified in `SDK_ADAPTER_PLAN.md` / `KOTLIN_ROSETTA.md`. The app depends only on the
published SDK types (`Synchronizer`, `CloseableSynchronizer`, `PersistableWallet`,
`AccountCreateSetup`, `FirstClassByteArray`) plus that one factory — no SDK fork.

### 4.4 The flag member + DI delta

Two supporting edits, both minimal:
- `ConfigurationRepository` gains `isSlipstreamAvailable: StateFlow<Boolean?>`, derived from the
  config flow exactly like `isFlexaAvailable` (`code/03-ConfigurationRepository.kt.patch`). No DI
  change — the member ships on the existing `ConfigurationRepositoryImpl` binding.
- `SynchronizerProviderImpl`'s constructor gains four args (`Context`, the two storage providers,
  `ConfigurationRepository`). It is registered with `singleOf(::SynchronizerProviderImpl)`
  (`ProviderModule.kt:85`), and every one of those types is already in the Koin graph, so
  `singleOf` auto-wires them — **no module edit, and `CoreModule`'s `singleOf(WalletCoordinator::invoke)`
  is untouched.**

### 4.5 Teardown-rebuild semantics

The SDK's `WalletCoordinator` already proves runtime engine swap. Its `synchronizer` flow is
`combine(persistableWallet, lockoutId, isTorEnabled, isExchangeRateEnabled)` → `flatMapLatest`
→ a `callbackFlow` that builds the synchronizer and, in `awaitClose`, **closes** it (SDK
`WalletCoordinator.kt:96-124`). Any input change cancels the inner flow, closes the old
synchronizer, and builds a fresh one. Flipping Tor already tears the whole synchronizer down
and rebuilds it today.

The provider's engine `combine` is that same shape with the flag as one more input.
Consequences you can rely on:
- The outer flow emits `null` until a wallet secret is persisted (so downstream behaves
  identically to today).
- A flag flip (or wallet/restore change) cancels the current branch, runs its `awaitClose`
  (closing whichever engine ran), and builds the other against the same `data.sqlite3`.
- Restore/rescan continues to run through `WalletCoordinator`'s wallet-secret-preserving erase
  (still the owner of the `data.sqlite3` erase path); the engine picks the reset up on its next
  teardown-rebuild.
- Because both engines pin the **same `zcash_client_sqlite` schema generation**, a live flag
  flip (if enabled later, D12.5) is a plain teardown-rebuild with no migration — the same
  one-decision property the iOS wallet ships.

## 5. The three `as SdkSynchronizer` casts

`SynchronizerProvider.getSynchronizer()` returns the public `Synchronizer` interface. Three
call sites downcast it to the concrete `SdkSynchronizer`. Under Slipstream the running
instance is a `SlipstreamSynchronizer`, so an unguarded `as SdkSynchronizer` would throw
`ClassCastException`. Each is fixed below. (Companion-object calls such as
`SdkSynchronizer.estimateBirthdayHeight(...)` in `RestoreDateVM`, `ResyncDateVM`,
`KeystoneDateVM`, `GetResyncDataFromHeightUseCase` are **not** instance casts — they are
static and engine-agnostic. No change needed there.)

### Site 1 — `ProposalDataSource.kt:281` (submit path) → drop the cast, engine-aware refresh

```kotlin
val synchronizer = synchronizerProvider.getSynchronizer() as SdkSynchronizer
// … synchronizer.broadcaster.submit(...) …
// … synchronizer.refreshTransactions(); synchronizer.refreshAllBalances()
```

`broadcaster` is on the **public `Synchronizer` interface** (SDK `Synchronizer.kt:163`), so it
never needed the cast. What forces the cast is `refreshTransactions()`
(→ `storage.invalidate()`) and `refreshAllBalances()` (→ `processor.refreshWalletSummary()`),
which exist only on `SdkSynchronizer` (SDK `SdkSynchronizer.kt:651`, `:658`) — the SDK engine's
explicit post-broadcast UI-freshness pokes. **The Slipstream adapter needs no host poke:** its
`broadcaster.submit(...)` bumps `tx_set_version` internally, and its 2 s poll loop re-queries
transactions and re-reads the wallet summary on its own, so the host must not poke it (doing so
would double-work its poll loop). Remediation — drop the cast, widen to the interface, and branch
the refresh so only the SDK engine gets the explicit pokes:

```kotlin
val synchronizer = synchronizerProvider.getSynchronizer()
val transactions = block(synchronizer)   // block: suspend (Synchronizer) -> List<CreatedTransaction>
// … endpoints, submit via synchronizer.broadcaster.submit(...) as today …
when (synchronizer) {
    is SdkSynchronizer -> {
        synchronizer.refreshTransactions()
        synchronizer.refreshAllBalances()
    }
    // Slipstream re-queries internally after its own submit; no host poke.
    else -> Unit
}
```

Note the helper `submitTransactionInternal`'s `block` parameter and `submitCreatedTransactions`
take `SdkSynchronizer` today; widen them to `Synchronizer` (both `broadcaster` and
`validateAddress` used there are on the interface). No app-side import of the artifact type is
needed here — the `else` branch covers Slipstream. Longer-term this whole `when` disappears if
`refreshTransactions`/`refreshAllBalances` are lifted onto the `Synchronizer` interface upstream
with a default that no-ops for engines whose poll loop already refreshes — recorded as an
upstream follow-up, off this branch's critical path.

### Site 2 — `ResetZashiUseCase.kt:62` (close) → cast to `CloseableSynchronizer`

```kotlin
(synchronizerProvider.getSynchronizer() as SdkSynchronizer).closeQuietly()
```

`closeQuietly` is `okhttp3.internal.closeQuietly`, an extension on `java.io.Closeable`.
`Synchronizer` is not `Closeable`, but `CloseableSynchronizer` is (SDK `Synchronizer.kt:1109`)
and **both** `SdkSynchronizer` and `SlipstreamSynchronizer` implement it. Widen the cast:

```kotlin
(synchronizerProvider.getSynchronizer() as CloseableSynchronizer).closeQuietly()
```

(Import `cash.z.ecc.android.sdk.CloseableSynchronizer`; drop the `SdkSynchronizer` import if
now unused.) Pure interface-level fix, no branch.

### Site 3 — `AndroidExportPrivateData.kt:97` (data.db path) → drop the cast

```kotlin
(synchronizer as SdkSynchronizer).getExistingDataDbFilePath(context, network)
```

`getExistingDataDbFilePath` is declared on the **public `Synchronizer` interface** (SDK
`Synchronizer.kt:631`) — the cast is redundant. The adapter implements it (the `data.sqlite3`
path is deterministic from context + network + alias, and Slipstream uses that same file).
Remove the cast:

```kotlin
synchronizer.getExistingDataDbFilePath(context, network)
```

All three fixes are in `code/05-cast-sites.patch`.

## 6. UX parity checklist

Every item is a shipped iOS bug class. The adapter feeds the existing `Synchronizer` flows
(`status`, `progress`, `areFundsSpendable`, `networkHeight`, `fullyScannedHeight`,
`walletBalances`, `allTransactions`, `accountsFlow`) so `WalletSnapshotDataSource` (`ui-lib/
.../common/datasource/WalletSnapshotDataSource.kt:48-64`) assembles the same `WalletSnapshot`
with no change. Verify each on device:

1. **Progress is rendered, never derived.** `synchronizer.progress: Flow<PercentDecimal>` maps
   the engine's `progress_permille / 1000` verbatim. The app already renders it verbatim
   (`GetHomeMessageUseCase.kt:255` `progress.decimal * 100f`; `WalletSyncingMessage`,
   `WalletRestoringMessage`). Do not smooth, clamp, or reconstruct it from heights. The
   `blocksRemaining` readout (`WalletSnapshotDataSource.kt:42-47`, `networkHeight −
   fullyScannedHeight`) is a secondary display only — it must never drive the progress bar. A
   synced wallet's catch-up must start near 100%, not flash 0%.

2. **The restoring phase is a projection of the engine, not a persisted truth.** This app
   persists its own `WalletRestoringState` enum (`NONE/INITIATING/RESTORING/RESYNCING/SYNCING`)
   via `WalletRestoringStateProvider` and transitions RESTORING→SYNCING when
   `Synchronizer.Status.SYNCED` is reached (`WalletSnapshotRepository.kt:43-44`). The contract
   **forbids a host-persisted restoring flag** — `is_recovering` (derived from the database,
   survives process death, correct from the first poll) is the truth. For v1, keep the enum
   **only as a cosmetic phase label** (shimmer, message copy, keep-screen-on) and:
   - drive its RESTORING→SYNCING transition off the engine — the adapter reports
     `Status.SYNCED` only when the engine is done *and* has verified the tip, so the existing
     transition point stays correct;
   - never let the persisted enum decide balance or transaction correctness (that is
     `is_recovering`'s job, applied inside the adapter — see items 4 and 5).
   Do not add any new persisted "is restoring" state.

3. **Spendability is masked until the tip is verified.** `synchronizer.areFundsSpendable:
   Flow<Boolean>` gates the spend UI. The adapter reports spendable only when the engine's
   `tip_fresh == 1` (this run has verified the chain tip) — with the spend-before-sync
   `spendable_hint` allowing early spend once the recent range has scanned — and shifts
   spendable → pending while `tip_fresh == 0`. Combined with the interface's own rule
   (`Synchronizer.kt:90-92`: prevent outbound tx until progress is 100%), the app must never
   offer a spend against a tip this session has not confirmed.

4. **Balances never over-show.** During recovery, naive balance math over-counts (a received
   note is counted before the spend that consumed it is scanned). The adapter surfaces
   phase-correct balances through `walletBalances` (recovery-safe while `is_recovering`, the
   upstream summary otherwise). The app must not cache a headline balance across the restore
   phase or sum notes itself.

5. **Transaction visibility is reconciled during restore.** The rule `visible = reconciled OR
   NOT is_recovering` (via the `slipstream_v_tx_reconciled` view) is applied **inside** the
   adapter's `getTransactions(...)`/`allTransactions`, so the app keeps calling those unchanged.
   Verify a self-send's change does not flash as a phantom "+received" mid-restore, and that
   genuine receives still appear immediately.

6. **Restore-complete UX fires at the right moment.** `IsRestoreSuccessDialogVisibleUseCase`
   and the keep-screen-on use case (`IsScreenTimeoutDisabledDuringRestoreUseCase`, states
   `RESTORING`/`SYNCING`) hang off the reconciled phase. Confirm the success dialog appears
   when `is_recovering` releases, not before.

7. **Status → color mapping.** `Synchronizer.Status` is `INITIALIZING/STOPPED/DISCONNECTED/
   SYNCING/SYNCED`. The adapter maps engine `state`: 0 idle → INITIALIZING (or STOPPED after an
   explicit stop), 1 → SYNCING, 2 error → DISCONNECTED (the app then offers retry via its
   existing error UI), 3 done/following → SYNCED. Report SYNCED only when truly caught up.

8. **Error surfaces still light up.** `SynchronizerProviderImpl.initializeErrorHandling`
   (`SynchronizerProvider.kt:106-134`) wires `onProcessorErrorHandler`/`onChainErrorHandler`/
   `onSetupErrorHandler`. The adapter maps engine `state == 2` and tag-4 error events into
   these callbacks so `SynchronizerError` still reaches `ErrorVM` and the existing UI.

## 7. `WalletInitMode` — unchanged for the app

The app passes `persistableWallet.walletInitMode` into synchronizer construction (SDK
`WalletCoordinator.kt:115`). Slipstream **derives the init flow from the wallet itself** (is
there a seed? a birthday? a `recover_until` height?) and treats an init-mode enum as advisory
only: it maps to provisioning *intent* (restore vs. new → the engine's `restore_anchor`
policy) and is otherwise ignored. So the app keeps setting `WalletInitMode` exactly as today —
no app change — and the adapter reads it purely as restore/new intent. (For context: the iOS
SDK removed `WalletInitMode` entirely once the engine began deriving the flow; Android keeps
the type but it becomes advisory under Slipstream.)

## 8. Rollout stages and kill switch

- **Stage 0 — merged, dark.** Branch lands with `IS_SLIPSTREAM_AVAILABLE` default false. No
  runtime change; every device runs the SDK engine. (Blocked on the artifact publishing.)
- **Stage 1 — dogfood.** Debug builds flip via the `IntentConfigurationProvider` intent
  override. Real-JNI smoke and the parity checklist run on device.
- **Stage 2 — canary.** Remote config sets `is_slipstream_available` true for a small
  percentage. Watch crash-free rate, restore-success, sync-time A/B.
- **Stage 3 — default-on build.** Ship with the flag defaulting true; the SDK engine remains
  one flag flip away.
- **Kill switch.** Set `is_slipstream_available=false` remotely (or ship a default-false
  build). The flag is a flow input to the provider's engine `combine`, so a flip-back returns
  `getSynchronizer()` to the SDK engine on the next teardown-rebuild against the same
  `data.sqlite3`; because both engines pin the **same schema generation** (contract §D5), the
  flip needs no migration and no data loss. For v1 that revert lands on the next wallet load
  (launch-time selection); the live in-session revert is the same machinery, deferred as a
  product decision (D12.5).

## 9. QA

- **Public host pattern + conformance:** the SDK-repo RFC under `zcash-android-wallet-sdk`
  `docs/slipstream/` defines the poll/SQL contract and the darkside-suite decoupling that lets
  the team's own conformance tests run against **any** `Synchronizer` — Slipstream then proves
  itself against their existing suite.
- **Scenario matrix:** the iOS release-gate scenario matrix (lifecycle × interruption grid)
  is ported and Android-ized; run it against both engines behind the flag.
- **Benchmarks:** the app's macrobenchmark module A/Bs the two engines on a pinned block
  window; the headline restore A/B uses fixture wallets from the iOS fleet protocol.

## 10. What is unproven (read before relying on any of this)

- **Nothing here has been compiled on an Android toolchain.** The `slipstream-android`
  artifact does not exist yet; `SlipstreamSynchronizer.new(...)`'s companion signature is the
  proposed shape (mirroring the SDK's `Synchronizer.new`), not a shipped one. Treat every code
  block as reviewed-but-uncompiled.
- The three cast fixes and the `SynchronizerProviderImpl` construction branch are verified
  against the SDK type hierarchy at `f386369e`, not against a build.
- `armeabi-v7a` engine performance on 32-bit is unbenchmarked (the engine targets 64-bit
  first; v7a ships but has no numbers).
- Live (no-restart) flag flip is deferred; v1 is launch-time selection.
- Voting flows (`getWalletDbPathForVoting`, tree-state fetches) are served verbatim by the
  adapter's delegation and are flagged for a post-v1 review with the voting owners.
