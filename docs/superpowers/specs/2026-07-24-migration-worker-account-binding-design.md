# Migration worker account-binding — design

**Date:** 2026-07-24
**Status:** Approved, ready for implementation plan
**Scope:** App-side only (`ui-lib`), background migration worker execution path only.
**Follow-up to:** `2026-07-24-migration-account-scoping-design.md` (that change made scheduling/persistence per-account; this one binds the *running* worker to the account it was enqueued for).

## Problem

The migration account-scoping change made each account's WorkManager job a distinct
unique-work entry (`migration_transfer_<accountKeyId>`), so scheduling one account's
migration no longer REPLACE-clobbers the other's job at enqueue time. But when a job
*runs*, `MigrationWorker.doWork()` still resolves its account from the **currently
selected** account (`getSelectedWalletAccount()`, `MigrationWorker.kt:45`), not from the
account the job was enqueued for.

**Failure scenario:** the user schedules a Zashi migration transfer, then switches the
selected account to Keystone before it fires. When Zashi's job runs, it resolves
"selected" = Keystone, builds the Keystone SDK, loads Keystone's plan, and executes a
Keystone step — while Zashi's transfer never runs. The per-account work name prevents the
enqueue-time clobber, but the run-time account binding remains global.

## Key finding (bounds the scope)

The background worker does **not** broadcast through the foreground send pipeline
(`SubmitProposalUseCase` / `ZashiProposalRepository` / `KeystoneProposalRepository`).
It calls `sdk.executeNextPendingTransfer(...)` directly (`MigrationWorker.kt:67`) on an
`OrchardMigrationSdk` instance that is built **for a specific account**. Therefore, once
the SDK (and the plan, and the Tor flag) are resolved for the correct account, the
broadcast is inherently account-correct — there is no hidden "selected account" read in
the worker's broadcast path.

The `getSelectedAccount()` reads in `SubmitProposalUseCase` and the proposal repositories
are on the **foreground** paths only (IMMEDIATE "send now" and the Keystone sign flow),
where the selected account is by definition the account the user is acting on. They are
**out of scope**.

Because the broadcast is account-agnostic given the right SDK instance, the worker can
safely **execute** its enqueued account's transfer even while a different account is
selected — no reschedule-on-mismatch dance is needed.

## Approach

Parameterize only the **background worker execution path** by an explicit account carried
in the WorkManager job's input data (and the alarm intent, which already carries it).

### Data flow
1. `MigrationScheduler.newWorkRequest(...)` sets the account into the request:
   `.setInputData(workDataOf(KEY_ACCOUNT_KEY_ID to accountKeyId))`. The scheduler already
   receives `accountKeyId`.
2. `MigrationWorker.doWork()` reads `inputData.getString(KEY_ACCOUNT_KEY_ID)` and uses that
   throughout, instead of `getSelectedWalletAccount()`.

### API parameterization
Add explicit-account variants; keep the existing selected-account variants for UI callers
(no UI/foreground behavior changes):

3. `GetOrchardMigrationSdkUseCase` — an `invoke(accountKeyId: String)` overload that
   resolves the `WalletAccount` via `accountDataSource.getAllAccounts()` (matching
   `sdkAccount.accountUuid.toStorageKeyId() == accountKeyId`) and builds the SDK for it.
   Returns `null` when no account matches (deleted account) — same null contract the
   caller already handles.
4. `MigrationPlanRepository` — `load(accountKeyId: String)` and `save(accountKeyId, plan)`
   overloads that key by the passed account instead of the selected one. The existing
   no-arg `load()`/`save()` and `observe()` (selected-account, used by UI) stay.
5. `IsMigrationTorEnabledStorageProvider` — a `get(accountKeyId: String)` overload that
   reads the passed account's key. The existing no-arg `get()`/`store()`/`observe()` stay.
6. `PendingMigrationTorFailureStorageProvider` — a `store(accountKeyId: String, value: Boolean)`
   overload. The worker sets this flag on a background Tor failure (`MigrationWorker.kt:134`),
   which must target the enqueued account. Existing no-arg accessors stay.
7. `MigrationTransferDueReceiver` — already reads `EXTRA_ACCOUNT_KEY_ID`; ensure any action
   it triggers targets that account (it currently only routes a notification).

### Error handling
- **Missing `inputData`** (a job enqueued before this change): fall back to
  `getSelectedWalletAccount()` with a `Twig.warn`. Transitional only; consistent with the
  clean-cutover stance and the alarm receiver's existing null-account fallback.
- **Account not found** in `getAllAccounts()` (deleted): the SDK use case returns `null`;
  the worker treats it as "nothing to do" and returns `Result.success()` with a log, the
  same as its existing null-SDK branch.

## Out of scope
- Foreground send pipeline: `SubmitProposalUseCase`, `ZashiProposalRepository`,
  `KeystoneProposalRepository` (selected account is correct there).
- UI ViewModels and the Keystone sign/scan hand-off (foreground).
- The SDK (`zcash-android-wallet-sdk`).
- `MigrationScheduler.schedule()/cancel()` signatures (already take `accountKeyId`).

## Testing
- **Worker binds to enqueued account, not selected:** with a fake `AccountDataSource`
  whose selected account is B, drive the worker (or the parameterized use cases) with
  account A's key id and assert the SDK build / plan load / Tor read all resolve **A**.
- **Account lookup:** `GetOrchardMigrationSdkUseCase.invoke(accountKeyId)` finds the right
  `WalletAccount` by `toStorageKeyId()`, and returns `null` for an unknown key id.
- **Plan repo explicit key:** `save("A", plan)` then `load("A")` returns it; `load("B")`
  returns null; the selected-account `load()` still works unchanged.
- **Tor provider explicit key:** `get("A")` reads A's stored value independent of selected.
- **Fallback:** missing input data → worker falls back to selected account (warn logged).
- Existing `MigrationWorkerTest` continues to pass (update its worker construction / input
  data as needed).
- Manual (emulator, per convention): schedule a Zashi transfer, switch selected account to
  Keystone, let the Zashi job fire, and confirm it executes **Zashi's** next transfer and
  reschedules under Zashi — leaving Keystone untouched.
