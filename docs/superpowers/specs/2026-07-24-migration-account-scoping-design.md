# Migration account-scoping — design

**Date:** 2026-07-24
**Status:** Approved, ready for implementation plan
**Scope:** App-side only (`ui-lib`). SDK-side deliberately excluded.

## Problem

The orchard→ironwood migration feature supports two wallet accounts migrating
independently (a Zashi/Zodl seed account and a Keystone hardware account). The
migration *plan* is already correctly keyed per-account
(`MigrationPlanRepository`, key `migration_plan_${accountUuid}`), as is
`HasSeenMigrationCompleteStorageProvider` (key `has_seen_migration_complete_$uuid`).

However, several other persistent and in-memory migration states are **global**
(not keyed by account). When a user has both a Zashi and a Keystone account, one
account's migration can clobber, block, or misattribute the other's:

- Persistent preference flags shared across accounts.
- A single WorkManager unique-work name + `ExistingWorkPolicy.REPLACE` — a second
  account's scheduling cancels the first account's already-scheduled transfer job.
- A single AlarmManager request code — accounts overwrite each other's alarm.
- Fixed notification IDs — one account's progress notification overwrites the
  other's.
- In-memory hand-off repositories — an account switch mid-flow could feed one
  account's data (notably unsigned Keystone PCZTs) into the other's flow.

## Corrected findings (verified during design)

Two items flagged by the initial audit were verified and **excluded**:

- **`migration_sync_resume_at`** (`MigrationSyncResumeAtStorageProvider`): the
  provider type is not injected or read anywhere in the app. The real sync-block
  is `OrchardMigrationSdk.isSyncBlocked(account = null)` evaluated in
  `WalletCoordinatorFactory`, which intentionally checks the *whole wallet* (there
  is a single sync loop, not one per account). Account-scoping the app-side pref
  is meaningless. Left unchanged.
- **SDK-side globals** (`MIGRATION_DB_ACCESS_MUTEX`, `tor_migration/` subdir): a
  different repository and partly core-team territory. Out of scope; tracked
  separately.

## Approach

Bring the remaining global migration state up to the same per-account isolation
the plan and "seen complete" flag already use.

### A) Persistent preference stores → per-account key

Convert from the fixed-key `Base*StorageProvider(key = …)` form to the dynamic
per-account pattern already used by `HasSeenMigrationCompleteStorageProvider`
(inject `AccountDataSource`, build the key from the selected account's
`accountUuid.toStorageKeyId()`, and drive `observe()` via
`selectedAccount.flatMapLatest`):

| Provider | Old key | New key |
|----------|---------|---------|
| `IsMigrationTorEnabledStorageProvider` | `is_migration_tor_enabled` | `is_migration_tor_enabled_$uuid` |
| `PendingMigrationTorFailureStorageProvider` | `pending_migration_tor_failure` | `pending_migration_tor_failure_$uuid` |

Defaults are preserved (migration Tor flag defaults `true`; failure flag defaults
`false`). All existing call sites (`MigrationSendingVM`, `MigrationWorker`,
`MigrationKeystoneScanVM`, `MigrationPrivacyVM`, `MigrationTorFailureVM`,
`CheckMigrationRecoveryUseCase`, `DebugVM`) keep the same interface — the
per-account resolution happens inside the provider, transparently.

### B) WorkManager + AlarmManager → per-account identity

`MigrationScheduler.schedule()/cancel()` and `MigrationDueAlarmScheduler` take an
`accountUuid: String` parameter. Every caller already has (or can resolve) the
selected account: `ScheduleNextMigrationWindowUseCase`,
`FinalizeMigrationScheduleUseCase`, `MigrationProgressVM`, `MigrationWorker`,
`DebugVM`.

- **Work name:** `WORK_ID` → `"co.electriccoin.zcash.migration_transfer_$uuid"`.
  `ExistingWorkPolicy.REPLACE` then only replaces the *same* account's job.
- **Alarm request code:** `9101 + acctOffset`, where
  `acctOffset = accountUuid.hashCode() and 0xFFFF`.
- **PendingIntent extra:** the alarm's `Intent` carries the `accountUuid` as an
  extra so `MigrationTransferDueReceiver` knows which account the "ready to send"
  event belongs to and can resolve/route accordingly.

### C) Notifications → per-account IDs

`MigrationNotifier`'s notify methods take an `accountUuid: String` and derive IDs
per account so accounts no longer overwrite each other's notifications:

- `NOTIFICATION_ID_PROGRESS` → `9001 + acctOffset`
- PendingIntent request codes (migration / transfer-ready) → distinct bases spaced
  ≥ `0x10000` apart, each `+ acctOffset`, so the `acctOffset` ranges never overlap.

`acctOffset` uses the same `accountUuid.hashCode() and 0xFFFF` helper as (B) — a
single shared helper (e.g. `fun accountIdOffset(uuid): Int`) so the derivation is
defined once. Callers (`MigrationWorker`, `MigrationTransferDueReceiver`,
`DebugVM`, `MainActivity` routing if applicable) pass the account.

### D) In-memory hand-off repos → account-tag guard

`PendingKeystoneMigrationPcztsRepository`, `PendingMigrationScheduleRepository`,
`RestartMigrationScheduleRepository`, `PendingMigrationTorFailureDecisionRepository`
store the owning `accountUuid` alongside the value. On `get()`/`consume()`, if the
stored `accountUuid` does not match the currently selected account, return `null`
(and clear) — the value belongs to a different account and must not be consumed.

Because `get()`/`consume()` are synchronous and reading the selected account is a
suspend call, the account identity is supplied by the caller: `set(accountUuid, …)`
records it, `get(accountUuid)` / `consume(accountUuid)` compares against it. The
repositories stay dependency-free; the calling VMs already know their account.
This is defensive — switching accounts mid-flow is unlikely in the UI — but it
prevents the worst case (signing another account's Keystone PCZTs).

## Out of scope

- SDK-side globals (separate repo).
- `migration_sync_resume_at` (unwired; sync is inherently whole-wallet).
- Data migration / cleanup of old global keys, jobs, and alarms. **Clean cutover:**
  the feature is pre-release; old orphaned WorkManager jobs / alarms / preference
  keys are left as-is and cleared by reinstall.

## Shared helper

A single `accountIdOffset(accountUuid: String): Int = accountUuid.hashCode() and 0xFFFF`
helper, used by (B) and (C). Collision across two accounts is theoretically
possible but negligible; documented as an accepted trade-off (registry-based
allocation was considered and rejected as unnecessary state).

## Testing

**Unit:**
- Preference providers (A): with two fake accounts, writing under account A does
  not change what account B reads; `observe()` switches value on selected-account
  change.
- `accountIdOffset` (B/C): deterministic for a given UUID; distinct for two
  representative UUIDs; work name / alarm code / notification ID differ per account.
- In-memory guard (D): `get()/consume()` returns `null` when the stored account
  UUID differs from the caller's; returns the value when it matches.

**Manual (emulator, per project convention — install+launch on testnet/foss/debug):**
- Schedule a migration transfer on the Zashi account, switch to the Keystone
  account and schedule/act there, and confirm the Zashi account's WorkManager job,
  alarm, home banner, and any posted notification remain intact and correctly
  attributed.
