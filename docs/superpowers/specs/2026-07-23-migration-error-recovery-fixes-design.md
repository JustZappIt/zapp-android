# Migration error/recovery fixes: Tor failure notification + "Migrate anyway" residual send (2026-07-23)

**Status:** approved design, ready for implementation planning.

**Source:** cross-check of the Figma "Error and Recovery" section (file `1aeq8gleYh9Yr1l33TwELR`, node
`#3491:9504`) against the current app implementation. Six error/recovery flows were audited; five are already
implemented end-to-end (overdue transfer resume, notes-spent re-create, expired-transfer re-create, transfer-ready
review, migration-complete/dust-lock). Two gaps were found and are designed here:

1. The Tor-connectivity-failure flow (`MigrationTorFailureScreen`) exists and works, but only when reached
   interactively (user manually taps Send Now and hits the error in the foreground) — there is no background
   detection or dedicated notification, so a Tor failure that happens while the app is closed looks identical to
   any other stalled transfer.
2. `MigrationCompleteVM.onMigrateAnyway()` is wired to `onDone()` — a no-op that just accepts the privacy risk and
   closes the screen. It never actually sends the leftover Orchard balance, even though the Figma design and the
   product intent (confirmed with the user) is: the residual is deliberately excluded from the normal migration
   schedule because a non-round-number amount is more identifiable on-chain (a privacy trade-off, not a technical
   limit) — but if the user explicitly opts in via "Migrate anyway," it should actually be sent.

Repos: `zashi-android` (app, this repo) and `zcash-android-wallet-sdk` (SDK, local path
`/Users/micutad/Projects/AndroidStudioProjects/zcash-android-wallet-sdk`, composite-build toggle via
`SDK_INCLUDED_BUILD_PATH`, see `[[reference_sdk_local_path]]`). Both fixes below are scoped to be implementable
entirely against the SDK surface already wired into the app today — neither depends on the in-flight
`proposeImmediateMigration()` `Proposal`-return-type redesign (spec item 3 in
`2026-07-23-migration-remaining-fixes-design.md`), which the local SDK clone's interface already declares but
which the app's `MigrationReviewVM.kt`/`OrchardMigrationSdkMock.kt` are not yet updated to match — that redesign
is a separate, already-tracked piece of work and out of scope here.

---

## Fix 1: Background Tor-connectivity-failure detection + dedicated notification

### Problem

`MigrationWorker.doWork()`'s non-retryable `TransferResult.NetworkError` branch
(`ui-lib/src/main/java/co/electriccoin/zcash/work/MigrationWorker.kt:84-93`) does not distinguish a Tor-specific
failure from any other network error — it always calls the generic
`migrationNotifier.notifyManualConfirmationRequired()`. Meanwhile `MigrationSendingVM.sendOnce()`
(`ui-lib/.../screen/migration/sending/MigrationSendingVM.kt:88-97`) already knows this distinction in the
interactive path: "A NetworkError while Tor was in use is presumptively a Tor-connectivity failure — routed to
its own sheet." That interactive logic is correct and stays as-is; the background worker just never tells the
user which kind of failure happened, and there is no persisted state connecting a background Tor failure to the
dedicated recovery screen.

### Design

**New persisted flag** — `PendingMigrationTorFailureStorageProvider` (interface + `Impl`), following the exact
pattern of `HasSeenMigrationCompleteStorageProvider.kt` (`BooleanStorageProvider` /
`BaseBooleanStorageProvider(key = PreferenceKey("pending_migration_tor_failure"))`, backed by
`StandardPreferenceProvider` — regular, non-encrypted, wiped on uninstall like the other migration flags).
Registered in `RepositoryModule.kt`/`di` alongside the other storage providers.

**`MigrationWorker.kt`** — in the non-retryable `NetworkError` branch, split on `useTor`:
```kotlin
is TransferResult.NetworkError -> {
    if (result.retryable) {
        Result.retry()
    } else if (useTor) {
        pendingMigrationTorFailureStorageProvider.store(true)
        migrationNotifier.notifyMigrationFailure()
        Result.failure()
    } else {
        if (next != null) migrationNotifier.notifyManualConfirmationRequired(next.index + 1, plan.totalCount)
        Result.failure()
    }
}
```

**`MigrationNotifier.kt`** — new method:
```kotlin
fun notifyMigrationFailure() {
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_alert_circle)
        .setContentTitle("Migration Failure")
        .setContentText("Open Zodl to review the details.")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(mainActivityIntent())
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_PROGRESS, notification)
}
```
Copy is verbatim from the Figma sticky/notification mock. Uses the same generic `EXTRA_OPEN_MIGRATION` intent
extra as every other migration notification — routing to the correct screen is decided by reconciliation state
(below), not by a notification-specific intent extra, so it works identically whether the app is opened via this
notification tap or via a normal cold launch.

**`CheckMigrationRecoveryUseCase.kt`** — new highest-priority branch, checked before `hasInvalidTransfers()`:
```kotlin
suspend operator fun invoke() {
    val sdk = getOrchardMigrationSdk() ?: return
    if (pendingMigrationTorFailureStorageProvider.get()) {
        navigationRouter.replaceAll(HomeArgs, MigrationSendingArgs)
    } else if (sdk.hasInvalidTransfers()) {
        ...
```
Routes to `MigrationSendingArgs`, **not** directly to `MigrationTorFailureArgs`. Reason: `MigrationSendingScreen`
has `LaunchedEffect(Unit) { vm.send() }`, so entering it always attempts the send immediately using the current
`isTorEnabledStorageProvider` setting — reproducing the exact condition that failed in the background. If it
fails again with Tor still unreachable, the existing `MigrationSendingVM.sendOnce()` logic (unchanged) forwards to
`MigrationTorFailureArgs` exactly as it does for the interactive Send-Now path. This reuses 100% of the existing
send/failure-routing logic and avoids a subtler alternative bug: navigating straight to a *fresh*
`MigrationTorFailureArgs` instance and then having its "Continue without Tor"/"Try again" actions call
`navigationRouter.back()` would pop to whatever was *last* on the stack — which, if reached via reconciliation
rather than the interactive path, is not a live `MigrationSendingVM` listening for the decision. Routing through
`MigrationSendingArgs` first sidesteps this entirely: by the time `MigrationTorFailureArgs` is ever pushed, it is
always pushed by an already-live `MigrationSendingVM`, so `back()` always returns to the correct listener,
regardless of entry point. No changes needed to `MigrationTorFailureVM` or `MigrationSendingScreen`.

**Clearing the flag** — in `MigrationSendingVM.sendOnce()`'s `TransferResult.Success` branch (the one
unambiguous "problem resolved" signal): `pendingMigrationTorFailureStorageProvider.store(false)`. Left `true` in
every other outcome (including a renewed Tor failure) so reconciliation keeps re-surfacing the recovery path on
next launch, consistent with how unresolved invalid/overdue state already persists until explicitly acted on.

### Error handling

No new failure modes introduced — this fix only adds a persisted boolean and a notification method,  reusing all
existing send/failure/reconciliation machinery. If `pendingMigrationTorFailureStorageProvider` itself is
unavailable (shouldn't happen, but as a boundary case), `CheckMigrationRecoveryUseCase`'s existing
`getOrchardMigrationSdk() ?: return` guard already covers "no wallet" early-return; the new flag check is a plain
synchronous preference read with no failure mode of its own.

### Testing

- Unit test `MigrationWorker`: non-retryable `NetworkError` with `useTor = true` → asserts
  `pendingMigrationTorFailureStorageProvider.store(true)` and `notifyMigrationFailure()` called, not
  `notifyManualConfirmationRequired()`.
- Unit test `CheckMigrationRecoveryUseCase`: flag `true` → asserts `replaceAll(HomeArgs, MigrationSendingArgs)`
  and that this branch pre-empts the invalid/overdue/complete checks.
- Unit test `MigrationSendingVM.sendOnce()`: `TransferResult.Success` → asserts flag cleared.
- Manual/on-device per `[[feedback_verify_via_emulator]]`: force a Tor-unreachable condition during a background
  `MigrationWorker` run (debug tooling can simulate this), confirm the "Migration Failure" notification appears,
  force-close the app, tap the notification, confirm it lands on the Sending spinner then the
  "Couldn't Connect to Tor" screen without ever showing the generic Resume Migration sheet first.

---

## Fix 2: "Migrate anyway" — actually send the residual

### Problem

`MigrationCompleteVM.kt:74` — `onMigrateAnyway = ::onDone`. Tapping "Migrate anyway" on the dust/residual card
just accepts the privacy risk and exits; it never proposes or sends a transaction for the leftover balance.

### Design

The residual is, by construction, whatever the current spendable Orchard balance still is once the normal
AUTOMATIC schedule has finished — there is no "current migration plan" left to extend (the plan is already
`isComplete`). The natural SDK call for "sweep whatever's left, right now, no schedule/cadence wait" is
`OrchardMigrationSdk.proposeImmediateMigration()` (today's signature: returns `MigrationSchedule` with one
transfer for the full current Orchard balance, `nextExecutableAfterHeight` = now) — this is exactly the same call
`MigrationReviewVM.onConfirm()`'s `MigrationMode.IMMEDIATE` branch
(`ui-lib/.../screen/migration/review/MigrationReviewVM.kt:187-195`) already uses, just invoked without ever
showing the Review screen (per the user's explicit choice: match the Figma row exactly — Migrate anyway →
Sending → Success, no intermediate confirmation screen).

`MigrationCompleteVM.kt`:
```kotlin
class MigrationCompleteVM(
    private val migrationPlanRepository: MigrationPlanRepository,
    private val getOrchardBalance: GetOrchardBalanceUseCase,
    private val hasSeenMigrationCompleteStorageProvider: HasSeenMigrationCompleteStorageProvider,
    private val hasLockedOrchardDustStorageProvider: HasLockedOrchardDustStorageProvider,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
    // New for this fix:
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource,
    private val biometricRepository: BiometricRepository,
) : ViewModel() {

    private val migrateLce = mutableLce<Unit>()

    val state: StateFlow<LceState<MigrationCompleteState>> =
        combine(loadLce.state, hasLockedOrchardDustStorageProvider.observe()) { lce, isLocked -> ... }
            .withLce(groupLce(loadLce, migrateLce), errorStateMapper::mapToState).stateIn(this)

    private fun createState(...) = MigrationCompleteState(
        ...,
        onMigrateAnyway = ::onMigrateAnyway,
    )

    private fun onMigrateAnyway() = migrateLce.execute {
        try {
            biometricRepository.requestBiometrics(
                request = BiometricRequest(
                    message = stringRes(
                        R.string.authentication_system_ui_subtitle,
                        stringRes(R.string.authentication_use_case_send_funds)
                    )
                )
            )
        } catch (_: BiometricsFailureException) {
            return@execute
        } catch (_: BiometricsCancelledException) {
            return@execute
        }
        val sdk = getOrchardMigrationSdk() ?: error("MigrationCompleteVM: no wallet available to migrate residual")
        val schedule = sdk.proposeImmediateMigration()
        sdk.signAndStoreMigrationSchedule(schedule, zashiSpendingKeyDataSource.getZashiSpendingKey())
        migrationPlanRepository.save(schedule.toMigrationPlan(MigrationMode.IMMEDIATE))
        navigationRouter.forward(MigrationSendingArgs)
    }
}
```
All three new constructor params must also be wired in `ViewModelModule.kt`'s `MigrationCompleteVM` factory —
`ZashiSpendingKeyDataSource` and `BiometricRepository` are both already bound elsewhere in the DI graph (used by
`MigrationReviewVM`), so this is a same-instance reuse, not a new binding.

`MigrationSendingScreen`/`MigrationSendingVM` need no changes: on `TransferResult.Success`,
`plan?.mode == MigrationMode.AUTOMATIC && plan.isComplete` is false for an `IMMEDIATE`-mode plan, so it falls
through to `navigationRouter.forward(MigrationSuccessArgs(result.txId))` — landing on a plain Success screen,
matching the Figma row's final step exactly (no return to a re-shown Migration Complete screen).

### Known, accepted limitation (not a new regression)

IMMEDIATE mode has no Keystone signing branch today — `MigrationReviewVM.kt:184-186`'s own comment documents this
as a pre-existing gap, also tracked in `2026-07-23-ios-findings-cross-check.md` (§2) and slated to be closed
together with the IMMEDIATE-mode `Proposal`/send-max redesign (`2026-07-23-migration-remaining-fixes-design.md`
item 3). Reusing `proposeImmediateMigration()` here inherits that same limitation: a Keystone-only account
tapping "Migrate anyway" would hit `zashiSpendingKeyDataSource.getZashiSpendingKey()` with no software key to
sign with. Decision (per conversation): accept this for now rather than block this smaller fix on the larger
IMMEDIATE/Keystone redesign landing first; revisit once item 3 ships.

### Error handling

Propose/sign failure surfaces through the existing `mutableLce`/`errorStateMapper` pipeline (via `groupLce`,
same pattern `MigrationTransferInvalidVM` uses for its `restartLce`) rather than a bespoke try/catch — consistent
with `[[feedback_lce_error_handling]]`. Biometric cancellation/failure silently returns to the Complete screen
(same pattern as `MigrationReviewVM.onConfirm()`), not treated as an error.

### Testing

- Unit test `MigrationCompleteVM.onMigrateAnyway()`: asserts `proposeImmediateMigration()` →
  `signAndStoreMigrationSchedule()` → `migrationPlanRepository.save()` with `MigrationMode.IMMEDIATE` →
  `navigationRouter.forward(MigrationSendingArgs)`, in that order.
- Unit test: biometric cancellation/failure short-circuits before any SDK call.
- Manual/on-device per `[[feedback_verify_via_emulator]]`: run a migration to completion with a synthetic
  below-threshold residual left in Orchard, confirm the dust card's "Migrate anyway" button sends the residual
  and lands on the Success screen with a zero (or near-zero) Orchard balance afterward.

---

## Sequencing

Independent fixes, different files, can land as two small separate PRs or one combined PR — no ordering
dependency between them. Both are scoped to be low-risk, additive changes to existing, already-tested pipelines
(background worker branching, VM wiring) rather than new architecture.
