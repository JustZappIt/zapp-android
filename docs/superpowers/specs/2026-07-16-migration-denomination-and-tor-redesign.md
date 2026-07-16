# Migration denomination redesign, 0.01 ZEC threshold, and Tor-as-global-setting

## Context

After a call with @nuttycom and @danny, the core team finalized several changes to the
Orchard→Ironwood migration design that supersede what's currently implemented:

1. **Denomination granularity**: migration transfers split the Orchard balance into notes valued
   at powers of 10 multiplied by 1, 2, and/or 5 (i.e. 1, 2, 5, 10, 20, 50, 100, 200, 500, 1000
   ZEC, ...) instead of pure powers of 10 (1, 10, 100, ...). Finer granularity, closer to the
   actual balance.
2. **0.01 ZEC threshold, migration considered complete at that point**: balance below 0.01 ZEC is
   never migrated. The full "migrate the rest / lock the rest" choice discussed on the call is
   **not** part of this iteration — the lock capability depends on a separate, not-yet-finalized
   librustzcash PR from @nuttycom. For now: migrate down to the 0.01 ZEC threshold, and consider
   the migration run complete once every generated transfer has sent — the remainder stays in
   Orchard indefinitely.
3. **Tor is no longer a per-migration choice**: the dedicated "Network Privacy" step becomes a
   bottom sheet that reads/writes the app's *existing* global Tor setting directly, rather than
   collecting and persisting a separate per-migration preference.

The core team is redoing the librustzcash PR (#2572) that this branch was based on from scratch
with the corrected design — **our local `librustzcash` checkout is not pushed anywhere and won't
be**, so it's safe to edit locally to unblock testing against the agreed algorithm now, ahead of
the real upstream PR landing.

Out of scope for this iteration (explicitly deferred):
- The Send screen Orchard-spend warning mentioned in the same notes — separate piece of work, not
  covered here.
- "Lock remaining balance" — no Rust support exists yet and the design isn't finalized.

## 1. Denomination algorithm (`librustzcash/zcash_pool_migration/src/denominations.rs`, local-only)

`plan_denominations` currently tries only `10^n` candidates for `n >= 0` (1, 10, 100, ... ZEC) and
treats anything under `RESIDUAL_MIGRATION_MIN_ZATOSHI` (100,000 zatoshi) as an opt-in "residual"
band above a pure-dust floor. Both go away:

- Candidate denominations become `{5, 2, 1} × 10^n` (checked largest-first, same greedy shape as
  today) for `n >= -2` — i.e. 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100, ... ZEC.
  **0.01 ZEC is the smallest possible denomination**, not 1 ZEC — this is what makes "we will not
  migrate sub-0.01 amounts" true: for a given budget, find the largest `d` in that ordered set
  such that `d + TRANSFER_FEE_BUFFER_ZATOSHI <= budget`.
- The self-funding fee buffer (`TRANSFER_FEE_BUFFER_ZATOSHI = 20_000` zatoshi) and per-run note
  cap (`MIGRATION_MAX_PREPARED_NOTES_PER_RUN = 64`) are unchanged.
- Replace `RESIDUAL_MIGRATION_MIN_ZATOSHI` with a single **migration threshold**,
  `MIGRATION_THRESHOLD_ZATOSHI = 1_000_000` (0.01 ZEC): decomposition stops once the remaining
  budget can't fund the smallest self-funding note (`0.01 ZEC + buffer` = 1,020,000 zatoshi). The
  "residual" concept — an opt-in extra transfer for a leftover between the old dust floor and
  1 ZEC — is removed entirely, since there's no longer a UI path offering it (see §2). Whatever's
  left below that cutoff is simply `orchard_change`, unconditionally.
- `MigrationContext::propose_migration_transfers`'s `include_residual` parameter and
  `residual_after_migration()` become dead in practice (no caller will ever pass `true` or call
  the latter) but are left in place structurally rather than removed, since this crate is being
  redone upstream anyway — not worth reshaping its public API on a branch that won't be pushed.
- Existing unit tests in `denominations.rs` get updated numbers reflecting the new candidate set
  (including the new sub-1-ZEC denominations) and the new 0.01 ZEC threshold; the "sub-1-ZEC" test
  in particular needs rethinking, since balances between 0.01 and 1 ZEC now *do* migrate (as one
  or more sub-1-ZEC notes) where they previously wouldn't have.

## 2. Migration completion semantics (app side)

- `includeResidual` stays `false` everywhere it's already hardcoded to `false` (per the existing
  `OrchardMigrationSdk` doc comments) — no behavior change needed there, since the Rust side no
  longer offers anything different for `true` either.
- `MigrationCompleteScreen`'s existing `remainingDust` field keeps working as-is (still shows
  whatever's left in Orchard, now always < 0.01 ZEC by construction) — no code change needed
  there, only the copy on the **"How This Works" screen** changes (see below), since that's where
  the user is told up front what to expect.
- `MigrationHowItWorksScreen`'s existing disclaimer footer already has a placeholder for this
  exact copy (`ic_info` row at the bottom, currently reading "...0.0005 ZEC or less..."). Replace
  its text with Neal's finalized copy:
  > "Choosing this option may require a small amount (less than 0.01 ZEC) to be left in the
  > Orchard pool, and which won't be transferred."

## 3. Tor becomes a global setting

**Current shape**: `MigrationPrivacyScreen`/`VM` is a full screen (`composable<MigrationPrivacyArgs>`
in `WalletNavGraph`) with its own local `useTor` toggle (default `false`), reached from
`MigrationSetupVM` (IMMEDIATE mode, "Move to Ironwood" button) and `MigrationNotificationVM`
(AUTOMATIC mode, after notification permission). Its choice is threaded forward through
`MigrationReviewArgs.useTor` → `MigrationPlan.useTor` → every `NetworkPrivacyOptions(useTor = ...)`
construction site (`FinalizeMigrationScheduleUseCase`, `MigrationSendingVM`, `MigrationWorker`,
`MigrationProgressVM`, `MigrationTransferReviewVM`, `MigrationKeystoneScanVM`,
`MigrationKeystoneSignVM`).

**New shape**, per the Figma "iOS 26 Bottom Sheet" design (node 3508:11348):

- `MigrationPrivacyScreen` keeps its existing `Args`/nav position (both call sites — Setup and
  Notification — need no changes, confirmed: they just navigate to `MigrationPrivacyArgs` as
  today) but renders as a **bottom sheet overlay**, not a full screen: swap its `WalletNavGraph`
  registration from `composable<MigrationPrivacyArgs>` to `dialogComposable<MigrationPrivacyArgs>`,
  and rebuild its View using `ZashiScreenModalBottomSheet` + `rememberScreenModalBottomSheetState()`
  (the exact template `KeystoneExplainerView.kt` already uses for a comparable info+CTA sheet).
- Content per Figma: title "Enable Tor Protection", intro body text, one card with a toggle
  ("Enable Tor Protection" / "Routes your connection through the Tor network for enhanced
  anonymity and privacy protection."), single button "Got it".
- The toggle reads and writes `IsTorEnabledStorageProvider` (the app's existing **global** Tor
  setting) directly — initial toggle state reflects whatever the user's global setting currently
  is, and toggling it in the sheet changes that same global setting immediately (not a
  local/pending value committed on "Got it"). "Got it" simply dismisses the sheet and continues
  the existing forward navigation to `MigrationReviewArgs` — same as today's "Next", just renamed
  and with no separate "Skip".
- `MigrationPlan.useTor` and every `useTor: Boolean` field threaded through migration `Args`
  classes are removed. Every site that currently reads `plan.useTor`/`args.useTor` to build
  `NetworkPrivacyOptions(useTor = ...)` instead reads `IsTorEnabledStorageProvider` fresh at
  broadcast time — so a user who changes their global Tor setting mid-migration (e.g. from
  Settings) automatically affects the next scheduled transfer, rather than being locked to
  whatever was true when the plan was first confirmed.
- No change to `OrchardMigrationSdk`/`OrchardMigrationSdkImpl`'s own Tor mechanism (the dedicated
  lazily-built `TorClient` in its own on-disk directory, independent of the main `Synchronizer`'s):
  `NetworkPrivacyOptions.useTor` keeps existing as a parameter into the SDK, since sdk-lib has no
  access to the app's own preference storage — only the *value* the app supplies now comes from
  the global setting instead of a per-plan choice.

## Verification

- Rust: `cargo test -p zcash_pool_migration` (updated unit tests) plus `cargo check` in
  `backend-lib` to confirm nothing downstream references the old constant name.
- App: compile `:ui-lib`/`:app`; manually run through both IMMEDIATE and AUTOMATIC setup flows on
  a synced testnet wallet with real Orchard funds, confirming:
  - Split amounts follow the 1/2/5×10^n pattern.
  - The Tor sheet appears as a bottom sheet (not full screen) at both entry points, its toggle
    reflects and changes the same setting visible in the app's Tor settings screen.
  - Migration Complete shows a remainder consistently under 0.01 ZEC with the correct copy shown
    earlier on "How This Works".
