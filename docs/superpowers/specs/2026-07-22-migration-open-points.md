# Orchard→Ironwood migration: open points

## Context

Punch-list of open items for the migration work, found during live-testnet verification of the
rewired engine (`zcash-android-wallet-sdk/backend-lib/src/main/rust/migration.rs`'s
`live_wallet_*_tests`), against Danny/core's `zcash_pool_migration_backend`/`_sqlite` engine (see
`project_core_migration_swap` memory and
`docs/superpowers/specs/2026-07-21-current-migration-implementation-spec.md`).

## Open items

### 1. Cross-account migration-state collision (SINGLETON_ID) — confirmed, upstream fix in flight, not yet adopted

Confirmed live via a new test, `live_wallet_edge_case_tests::singleton_id_collision_between_accounts`:
`migration_engine.rs`'s `Backend` impl of `PoolMigrationRead`/`PoolMigrationWrite` ignores
`self.account` entirely and passes straight through to the store's single `SINGLETON_ID`-keyed
row. Concretely: committing a migration for account A, then reading `Backend::get_migration()`
scoped to account B (a second, unrelated account in the same wallet DB), returns account A's
committed transactions. Directly affects the real "zodl software account + Keystone-linked
account in one wallet" case product wants supported.

Upstream fix already exists: **`zcash/librustzcash#2712`** (Michal Fousek, OPEN as of
2026-07-21/22, not merged) — replaces `PoolMigrations::new(conn)` with
`PoolMigrations::for_account(conn, account: Uuid)`, keys both migration tables by `account_uuid`.
The engine crate (`zcash_pool_migration_backend`) needs zero changes. PR branch is NOT built on
the commit we currently track (`origin/feat/pool-migration-sqlite` @ `083bb6131805`) — diverges
by ~20 commits from their merge-base — so deliberately not rebased onto it yet; wait for it to
land upstream, then adopt via the normal branch-tracking update. Once available, our own fix is
small: `migration_engine.rs::Backend::new` passes `account` into `PoolMigrations::for_account`
instead of `PoolMigrations::new`.

**Decision still open**: whether to proactively share our repro test/evidence with Michal/Danny
(Slack or a PR comment on #2712) to corroborate urgency, or just wait passively for the PR to
merge. Not done yet — left to product/Dominik's call.

**Files:** `zcash-android-wallet-sdk/backend-lib/src/main/rust/{migration.rs,migration_engine.rs}`;
upstream `zcash_pool_migration_sqlite/src/{lib.rs,store.rs,orchard_ironwood.rs}`.

### 2. `live_wallet_*_tests` in `migration.rs` need `--test-threads=1`

Cosmetic/test-infra only, not a product bug: the `#[ignore]`d live tests at the bottom of
`migration.rs` each copy the (~8.5MB) `MIGRATION_TEST_WALLET_DB` fixture via `fresh_test_db_copy`
before running; cargo's default parallel test execution has been observed to occasionally trip a
spurious `DatabaseCorrupt "database disk image is malformed"` on that copy under concurrent load.
Documented in a doc-comment on `live_wallet_edge_case_tests`; always run this suite with
`cargo test --lib migration:: -- --ignored --nocapture --test-threads=1`. Not worth a real fix
(these are opt-in local-only tests, not CI), but easy to trip over if forgotten.

### 3. `backend-lib` compile break against the tracked librustzcash commit — Rust API mismatch

Running `./gradlew :ui-lib:compileZcashtestnetFossDebugKotlin` from `zashi-android` on 2026-07-21
failed at the `backend-lib:cargoBuildArm` step (blocks the whole app's Kotlin compile — the native
build is a hard Gradle dependency) with three errors, all API-mismatch shaped rather than logic
bugs:

- `E0599`: no method `ufvk` on `<W as WalletRead>::Account` at `migration_engine.rs:148` —
  `zcash_client_backend::data_api::Account` trait (which provides `ufvk`) is implemented but not
  imported into scope.
- `E0282`: type annotation needed on `.and_then(|ufvk| ufvk.orchard())` at `migration_engine.rs:149`
  — follows directly from the above (closure param type can't be inferred once `ufvk()`'s return
  type is unresolved).
- `E0061`: `create_proposed_transactions::<_, _, Infallible, _, Infallible, _>(...)` at `lib.rs:2372`
  called with 7 args; the upstream `zcash_client_backend::data_api::wallet` signature now takes 8
  (a trailing `Option<BlockHeight>`).

librustzcash was on `083bb6131805` (matches item 1's tracked commit) with a clean `git status` at
the time — this isn't a local uncommitted change on our side, the checked-out commit itself no
longer matches what `backend-lib` expects. Not something to fix blind from the UI side; flagging
for whoever owns `migration_engine.rs`/`lib.rs` to reconcile against whatever commit is actually
checked out when picked up (may have moved again since).

**Files:** `zcash-android-wallet-sdk/backend-lib/src/main/rust/{lib.rs,migration_engine.rs}`.

---

The items below come from cross-checking Andrea's Figma-changes Slack summary (2026-07-21,
`#design`/zodl workspace) against the current `zashi-android` implementation — UI-side gaps, not
backend/Rust ones.

### 4. Send screen: no NU6.3/Orchard-spend warning

Design (Figma node `3921-11788`, "Send_Privacy Disclaimer") adds a disclaimer card on the Send
form when the transaction would spend Orchard-pool funds after NU6.3 activates: title *"This send
requires spending Orchard funds"*, body *"We recommend migrating your funds first to avoid leaking
the transaction amount on-chain."* (same visual style as `PrivacyDisclaimerCard`), sitting above the
existing "Review" button. Nothing in `ui-lib/.../screen/send/` currently checks for or renders
this — no NU6.3-activation check, no Orchard-note-spend detection, no card. Not started.

**Files:** likely `ui-lib/.../screen/send/SendViewModel.kt`, `.../screen/send/view/SendView.kt`
(exact insertion point not yet scoped).

### 5. Tor-connection-failure: local notification not wired

The in-app side of this is done — `MigrationTorFailureScreen` (a standalone bottom-sheet nav route,
this session) shows "Couldn't Connect to Tor" with Continue-without-Tor/Try-again, and
`MigrationSendingVM.send()` routes to it when a `TransferResult.NetworkError` occurs while Tor was
in use. What's still missing is the other half of Andrea's spec (Figma node `4207-8768`, iOS
notification mockup: title *"Migration Failure"*, body *"Open Zodl to review the details."*):
a real Android system notification posted when this failure happens in the background, that on tap
routes into the app and opens that same sheet. `MigrationNotifier.kt`
(`ui-lib/.../common/provider/`) already exists with real `NotificationManagerCompat` posting code
(`notifyTransferComplete`, `notifyManualConfirmationRequired`, `notifyMigrationPlanInvalid`,
`notifyMigrationComplete`) — none of these is Tor-specific. Needs: a new
`notifyTorConnectionFailed()`-shaped method, a deep-link/pending-intent target that opens
`MigrationTorFailureArgs`, and a real trigger point (this ties into the "background job detects a
Tor failure" case we deliberately scoped out earlier as hard-to-detect/do-later — see prior
`MigrationSendingVM` work).

**Files:** `ui-lib/.../common/provider/MigrationNotifier.kt`,
`ui-lib/.../screen/migration/torfailure/`, `ui-lib/.../screen/migration/sending/MigrationSendingVM.kt`.

### 6. Migration Complete "Migrate anyway": doesn't actually send

Design (Figma nodes `4002-32566` "Sending..." and `3843-15252` "Sent!") expects "Migrate anyway" on
Migration Complete to sign and broadcast the remaining Orchard dust as one immediate transaction,
with real progress/success screens (reusing or mirroring the existing
`MigrationSendingScreen`/`MigrationSuccessScreen` pattern). Current implementation
(`MigrationCompleteVM.kt`): `onMigrateAnyway = ::onDone`, and `onDone()` just stores the
seen-migration-complete flag and calls `navigationRouter.backToRoot()` — no transaction is
constructed or sent, it's a pure dismiss. Not started.

**Files:** `ui-lib/.../screen/migration/complete/MigrationCompleteVM.kt`; likely needs a new use
case analogous to the existing send/sign flow (`GetOrchardMigrationSdkUseCase` +
`executeNextPendingTransfer`-shaped call for the dust amount specifically).

### 7. Migration Complete "Lock balance": no loading state

Design (Figma node `3836-8488`, "Migration Complete -> Locking Balance") shows the primary button
in a disabled state with a loading spinner ("Locking balance...") directly on the Migration Complete
screen while the lock call is in flight, before transitioning to the locked-success state. Current
implementation (`MigrationLockExplainerVM.kt`'s `onGotIt()`) calls `lockOrchardBalance()` and
`navigationRouter.back()` with no loading/disabled state shown in between — currently invisible
in practice since the SDK-side `lockRemainingOrchardBalance()` is a no-op stub (see
`OrchardMigrationSdk.lockRemainingOrchardBalance` kdoc), but the UI structure for it (an `isLocking`
flag on `MigrationCompleteState`, surfaced as a disabled/spinner "Lock balance" button) doesn't
exist yet and will be needed once that stub becomes a real call.

**Files:** `ui-lib/.../screen/migration/complete/{MigrationCompleteState.kt,MigrationCompleteVM.kt,MigrationCompleteScreen.kt}`,
`ui-lib/.../screen/migration/lockexplainer/MigrationLockExplainerVM.kt`.
