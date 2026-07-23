# Tor for migration: flow-scoped flag, default true, skip when global already on

## Context

Earlier today (2026-07-16) this repo shipped Tor as a genuinely global app-wide setting for
migration broadcasts (`IsTorEnabledStorageProvider`, read/written directly by the migration Tor
bottom sheet), squashed into one commit (`de4201239`) specifically so it could be cleanly reverted.

A same-day Slack thread (`#core-wallet`/`#wallet-team`, Josh/Andrea Kobrlova/Kris Nuttycombe/
Milan) established this was the wrong shape: Tor for migration should not flip the user's global
app-wide Tor setting — it should be scoped to migration broadcasts only, default-on, with an
explicit warning if the user opts out (IP→balance linkage risk). Separately, Andrea updated the
actual Figma designs (`#wallet-team`, 2026-07-16 19:10) to remove the Tor sheet entirely from the
AUTOMATIC ("Migrate with privacy") flow, keeping it only in the IMMEDIATE flow — **this was
subsequently revised again**: both flows show the sheet, per this spec's uniform mechanic below.
See [[project_tor_migration_design_conflict]] for the full history.

This spec documents a further adjustment discussed today: a simpler, uniform flag-based mechanic
that applies identically to both AUTOMATIC and IMMEDIATE — both flows use the same
skip-if-global-on / show-with-default-true sheet described below (confirmed 2026-07-17, resolving
the AUTOMATIC-vs-IMMEDIATE discrepancy this spec originally flagged as open).

## Design

### 1. The flag is migration-flow-scoped, not global

A `useTor: Boolean` is threaded through the migration flow's own navigation `Args`/persisted
`MigrationPlan` again (reversing today's removal), rather than read fresh from
`IsTorEnabledStorageProvider` at broadcast time. It ends up passed into the SDK's existing
`NetworkPrivacyOptions.useTor` parameter (`MigrationSdk.kt` — unchanged, already migration-scoped
at the SDK layer; only the app-side source of the value changes).

### 2. Default value is `true`

Unlike the original pre-2026-07-16 per-migration flag (which defaulted to `false`), this flag
defaults to **`true`**. Migration broadcasts use Tor unless the user actively opts out.

### 3. Skip decision reuses today's existing mechanism, unchanged

`GetMigrationPrivacyOrReviewDestinationUseCase` (built today, `ui-lib/.../common/usecase/
GetMigrationPrivacyOrReviewDestinationUseCase.kt`) already checks
`isTorEnabledStorageProvider.get()` before either entry point (Setup's IMMEDIATE path,
Notification's AUTOMATIC path) navigates to the Tor sheet. This logic is kept as-is:

- **Global Tor setting already `true`** → skip the sheet entirely, navigate straight to
  `MigrationReviewArgs`, constructed with `useTor = true` (global being on is itself the signal —
  no need to ask again).
- **Global Tor setting `false`/unset** → show the sheet, exactly as it does today.

### 4. The sheet itself: default-true toggle, no longer writes the global setting

When the sheet IS shown (global was off):

- `MigrationPrivacyState`'s toggle initial value is **`true`** (not read from the global setting —
  we already know global is off if we got here; the flow's own default is independently true).
- Toggling the switch updates only local `MigrationPrivacyVM` state — it no longer calls
  `isTorEnabledStorageProvider.store()`. The global app-wide Tor setting is never touched by this
  screen.
- "Got it" carries the chosen boolean (true by default, or false if the user explicitly toggled
  it off) forward via navigation into `MigrationReviewArgs.useTor`, not a re-read of the global
  setting.

### 5. Re-thread `useTor` through the migration flow (reverses today's removal)

Everywhere today's `de4201239` commit removed the per-migration `useTor` field needs it back,
sourced from the flow-local value (either the sheet's chosen value, or `true` when the sheet was
skipped because global was already on) instead of a fresh global read:

- `MigrationReviewArgs` — gains back `useTor: Boolean`.
- `MigrationPlan` — gains back `useTor: Boolean` (persisted with the plan, as before).
- `MigrationSendingArgs` — reverts from a zero-field `data object` back to
  `data class MigrationSendingArgs(val useTor: Boolean)`.
- `MigrationKeystoneSignArgs` / `MigrationKeystoneScanArgs` — gain back `useTor: Boolean`.
- `FinalizeMigrationScheduleUseCase.invoke(...)` — gains back a `useTor: Boolean` parameter; drops
  its `isTorEnabledStorageProvider` fresh-read (added today) in favor of the threaded value.
- `MigrationSendingVM.send()` / `MigrationWorker.doWork()` — revert to reading
  `args.useTor` / `plan.useTor` instead of `isTorEnabledStorageProvider.get()`.
- `MigrationReviewVM`, `MigrationKeystoneScanVM`, `MigrationKeystoneSignVM` — thread the value
  through their navigation calls again, exactly as they did before today's removal.

Net effect: this is largely a revert of `de4201239`'s `useTor`-threading removal, kept alongside
the still-valid skip-if-global-already-on check and the new default-`true` behavior (both the
sheet's own default and the skip-branch's implicit `useTor = true`).

## Open questions

1. **IP-correlation disclaimer copy** (ZIP 318 app-gap #2, [[project_zip318_app_gaps]]) — still not
   implemented anywhere. Does it belong in this sheet, in "How This Works," or both?
2. Does this affect the sync→send correlation disclosure gap (ZIP 318 app-gap #1) already queued
   for `2026-07-16-migration-manual-scheduling-unification-design.md`? Likely independent (that gap
   is about the *background/overdue-transfer* Send Now flow, not the initial Tor choice), but worth
   a final check once both specs move to implementation.
