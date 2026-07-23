# Unify MANUAL/SCHEDULED migration delivery, remove confirm-time immediate broadcast

## Context

While manually testing the Confirm Transfer Plan screen on testnet, we found that clicking
Confirm in the `backgroundAvailable = false` (MANUAL delivery) case immediately broadcasts the
first migration transfer synchronously in the foreground, right after the app just synced to
build the proposed schedule. This creates a sync→send timing correlation an observer could use to
link the transaction to this specific wallet/session — undermining the same privacy goal the
migration's scheduled, randomized-amount, decorrelated-timing design otherwise protects.

Investigating this surfaced a broader inconsistency: `MigrationDeliveryMode.MANUAL` (set when the
user declines the Battery screen's optimization-exemption request) already treats every
transfer *after* the first one completely differently from `SCHEDULED` — it never schedules a
real background send-worker for them, only a notify-only job, on the theory that a real worker
can't be trusted to run reliably without battery exemption, and so a MANUAL plan must never look
like it silently auto-sent something in the background. Only the *first* transfer was special-cased
to send immediately at confirm time instead.

Discussed and resolved: the Battery screen's answer should not be treated as a hard "background
definitely doesn't work" signal — it's just a request for more reliable background execution.
Whether or not it's granted, we still always attempt real background scheduling, and both cases
already need the same fallback path (notification + user reopens the app to find an overdue
transfer). So `MigrationDeliveryMode.MANUAL` and `SCHEDULED` converge to the same mechanism —
always a real `MigrationScheduler.schedule()` job, never `scheduleNotifyOnly()` — differing only in
how reliably the OS actually lets that job run, which the existing overdue-recovery fallback
already covers for both.

Out of scope for this iteration:
- Deleting `MigrationTransferReviewScreen`/`MigrationTransferReviewVM`/`MigrationTransferReviewState`
  — they become unreferenced by any navigation call site after this change, but are left in place
  untouched rather than removed, in case they're wanted again for a different purpose later.
- Removing `MigrationPlan.deliveryMode`/`MigrationDeliveryMode` itself — the field stays
  structurally in place (still set from `backgroundAvailable` at confirm time) even though it no
  longer drives any routing or scheduling decision after this change. It becomes informational
  only. A future cleanup could reconsider whether to remove it entirely; not decided here.
- Any change to `MigrationScheduler`'s actual WorkManager constraints (retry policy, backoff,
  battery/network constraints) — this design only changes *which* scheduling call each delivery
  mode uses (`schedule()` vs `scheduleNotifyOnly()`), not what `schedule()` itself does internally.

## 1. `FinalizeMigrationScheduleUseCase` — remove the immediate-broadcast branch

**Current shape** (`ui-lib/.../common/usecase/FinalizeMigrationScheduleUseCase.kt`):

```kotlin
suspend operator fun invoke(
    sched: MigrationSchedule,
    mode: MigrationMode,
    backgroundAvailable: Boolean,
): TransferResult? {
    val deliveryMode = if (backgroundAvailable) MigrationDeliveryMode.SCHEDULED else MigrationDeliveryMode.MANUAL
    migrationPlanRepository.save(sched.toMigrationPlan(mode, deliveryMode))

    if (deliveryMode == MigrationDeliveryMode.MANUAL) {
        migrationPlanRepository.rescheduleTransfer(0, Clock.System.now().epochSeconds)
        return when (
            val result = getOrchardMigrationSdk()?.executeNextPendingTransfer(NetworkPrivacyOptions(useTor = useTor))
        ) {
            is TransferResult.Success -> { scheduleNextMigrationWindow(); navigationRouter.forward(MigrationScheduledArgs); null }
            null -> { navigationRouter.forward(MigrationScheduledArgs); null }
            else -> result
        }
    } else {
        migrationScheduler.schedule(delayUntilFirstTransfer(sched))
        navigationRouter.forward(MigrationScheduledArgs)
        return null
    }
}
```

**New shape** — both delivery modes take the same path (save, schedule a real worker, navigate to
Scheduled); the only difference is now purely which `MigrationDeliveryMode` value gets persisted
onto the `MigrationPlan` (informational — see Context's "out of scope" note):

```kotlin
suspend operator fun invoke(
    sched: MigrationSchedule,
    mode: MigrationMode,
    backgroundAvailable: Boolean,
) {
    val deliveryMode = if (backgroundAvailable) MigrationDeliveryMode.SCHEDULED else MigrationDeliveryMode.MANUAL
    migrationPlanRepository.save(sched.toMigrationPlan(mode, deliveryMode))
    migrationScheduler.schedule(delayUntilFirstTransfer(sched))
    navigationRouter.forward(MigrationScheduledArgs)
}
```

- Return type changes from `TransferResult?` to `Unit` — nothing broadcasts synchronously anymore,
  so there's no transfer result to report.
- `isTorEnabledStorageProvider`, `NetworkPrivacyOptions`, `getOrchardMigrationSdk`,
  `scheduleNextMigrationWindow` become unused in this class and their imports/constructor params
  are removed (a following broadcast, whenever the scheduled worker or a later "Send Now" tap
  actually fires, resolves its own Tor setting and re-arms its own next window exactly as
  `MigrationSendingVM`/`MigrationWorker` already do independently).
- `migrationPlanRepository.rescheduleTransfer(0, ...)` (the "reset before sending" comment's target)
  is removed — nothing sends immediately anymore, so there's nothing to reset in advance of.

## 2. `MigrationProgressVM.onReschedule()` — always use the real worker

**Current shape**:

```kotlin
if (plan?.deliveryMode == MigrationDeliveryMode.MANUAL) {
    MigrationScheduler(context).scheduleNotifyOnly(delay)
} else {
    MigrationScheduler(context).schedule(delay)
}
```

**New shape** — the `if`/`else` collapses; every reschedule (regardless of the plan's persisted
`deliveryMode`) uses the real worker:

```kotlin
MigrationScheduler(context).schedule(delay)
```

## 3. `CheckMigrationRecoveryUseCase` + `HomeVM` — unify overdue routing

Both currently branch on `plan?.deliveryMode == MigrationDeliveryMode.MANUAL` to route an overdue
transfer to the lean `MigrationTransferReviewArgs` instead of the fuller `MigrationProgressArgs`
("Resume Migration", with its existing Send Now/Reschedule choice). Since both delivery modes now
share the identical "real worker attempted, might not have fired" mechanism, this branch is
removed in both places — an overdue transfer always routes to `MigrationProgressArgs`, regardless
of `deliveryMode`.

`CheckMigrationRecoveryUseCase` (`ui-lib/.../common/usecase/CheckMigrationRecoveryUseCase.kt`),
current:

```kotlin
} else if (sdk.hasOverdueTransfers()) {
    val plan = migrationPlanRepository.load()
    if (plan?.deliveryMode == MigrationDeliveryMode.MANUAL) {
        Twig.debug { "MigrationRecovery: manual transfer due — redirecting to Review Transfer." }
        navigationRouter.replaceAll(HomeArgs, MigrationTransferReviewArgs)
    } else {
        Twig.debug { "MigrationRecovery: overdue transfer detected — redirecting to Resume Migration." }
        navigationRouter.replaceAll(HomeArgs, MigrationProgressArgs)
    }
}
```

New:

```kotlin
} else if (sdk.hasOverdueTransfers()) {
    Twig.debug { "MigrationRecovery: overdue transfer detected — redirecting to Resume Migration." }
    navigationRouter.replaceAll(HomeArgs, MigrationProgressArgs)
}
```

(`migrationPlanRepository`/`plan` become unused in this branch — check whether
`migrationPlanRepository` is still referenced elsewhere in this class before removing its
constructor param entirely.)

`HomeVM.kt`'s parallel branch (around line 433), current:

```kotlin
plan?.deliveryMode == MigrationDeliveryMode.MANUAL && getOrchardMigrationSdk()?.hasOverdueTransfers() == true -> {
    navigationRouter.forward(MigrationTransferReviewArgs)
}
plan != null -> navigationRouter.forward(MigrationProgressArgs)
```

New — the MANUAL-specific branch is removed; an overdue transfer of any delivery mode falls
through to the existing `plan != null` branch already handling `MigrationProgressArgs`:

```kotlin
plan != null -> navigationRouter.forward(MigrationProgressArgs)
```

(The separate `hasOverdueTransfers()` check that gated the removed branch doesn't need to move
anywhere — `MigrationProgressArgs`'s own `MigrationProgressVM.createState()` already computes
`hasOverdue` itself from the plan's `nextPending`/`scheduledAt`, independent of how the caller got
there.)

## 4. Left unchanged (explicitly, per discussion)

- `MigrationTransferReviewScreen.kt`, `MigrationTransferReviewVM.kt`, `MigrationTransferReviewState.kt`,
  their `WalletNavGraph.kt` registration, and their `MigrationFlowPreviews.kt` preview — all stay in
  place exactly as they are today. After this change, nothing in the app navigates to
  `MigrationTransferReviewArgs` anymore, but the files are not deleted.
- `MigrationPlan.deliveryMode`/`MigrationDeliveryMode` — kept structurally, still set from
  `backgroundAvailable`, no longer read by any routing/scheduling decision.
- `MigrationScheduler.scheduleNotifyOnly()` itself — the method can stay defined even though this
  change removes its only two call sites (`FinalizeMigrationScheduleUseCase`,
  `MigrationProgressVM.onReschedule()`); not required to delete it, since it's a one-line method
  distinct from the mechanism being simplified here, not something this design is scoped to touch.

## 5. Callers of `FinalizeMigrationScheduleUseCase` lose their failure-sheet branch

`FinalizeMigrationScheduleUseCase` never returns a `TransferResult` anymore (see §1), so its two
callers simplify:

**`MigrationReviewVM.confirmAutomatic()`** (`ui-lib/.../screen/migration/review/MigrationReviewVM.kt`),
current:

```kotlin
val result = finalizeMigrationSchedule(sched, args.mode, args.backgroundAvailable)
if (result != null) failure.value = result
```

New:

```kotlin
finalizeMigrationSchedule(sched, args.mode, args.backgroundAvailable)
```

**`MigrationKeystoneScanVM.onScanned()`** (`ui-lib/.../screen/migration/keystonescan/MigrationKeystoneScanVM.kt`),
current:

```kotlin
val failure = finalizeMigrationSchedule(sched, args.mode, args.backgroundAvailable)
isProcessing = false
if (failure != null) {
    failureSheet.update { MigrationTransferFailureState(...) }
} else {
    pendingSchedule.clear()
}
```

New — this call can no longer fail, so it always clears the pending schedule; `isProcessing`
still needs resetting before the (now unconditional) `pendingSchedule.clear()`:

```kotlin
finalizeMigrationSchedule(sched, args.mode, args.backgroundAvailable)
isProcessing = false
pendingSchedule.clear()
```

`failureSheet`'s `MutableStateFlow<MigrationTransferFailureState?>` and its retry/dismiss wiring in
this VM stay in place structurally (the state itself is exposed to the View regardless), they
simply never get set to non-null via this call site anymore. Check whether anything else in this
VM still sets `failureSheet` before deciding whether the field becomes fully dead in this file —
if it does, this is worth flagging to a human rather than silently removing the field, per the
"don't remove without confirming nothing else needs it" caution — this is a plan-writing-time
check, not a design decision.

## Verification

- Compile `:ui-lib` after each file's changes.
- Manual testnet walkthrough: confirm an AUTOMATIC migration with the Battery screen's
  optimization-exemption declined (`backgroundAvailable = false`) — Confirm should now land on the
  Scheduled screen immediately with no broadcast, and the first transfer should only actually send
  once its scheduled window arrives (via the real background worker) or the user reaches it
  through Resume Migration's Send Now.
- Force an overdue transfer under both a MANUAL-flagged and a SCHEDULED-flagged plan (e.g. by
  waiting past the scheduled window, or manipulating the persisted `MigrationPlan`'s
  `scheduledAtEpochSeconds` in a debug build) and confirm both now land on Resume Migration
  (`MigrationProgressArgs`) with the same Send Now/Reschedule choice, never on
  `MigrationTransferReviewArgs`.
