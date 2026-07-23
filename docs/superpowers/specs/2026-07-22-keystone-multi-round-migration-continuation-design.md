# Keystone Multi-Round Migration Auto-Continuation — Design

**Status:** design, written 2026-07-22. Builds on `2026-07-22-migration-flow-full-spec.md` (current-state
reference) and directly fixes a gap identified while cross-checking that spec against an internal rules
cheat sheet.

## 1. Problem

The Orchard→Ironwood migration engine caps a single planning/build pass ("run") at
`MIGRATION_MAX_PREPARED_NOTES_PER_RUN = 50` notes. A wallet whose Orchard balance needs more preparation
notes than that must migrate over **several successive runs** — this is a real engine constraint, not a
UI nicety: a later run's transactions spend notes an earlier run must first get mined (confirmed directly
against `librustzcash`'s new `RunEstimate`/`MigrationRunEstimate` API, PR zcash/librustzcash#2714, merged
to `origin/main`).

Today's app has **no continuation mechanism** for this, and a confirmed bug actively blocks one:

- `GetHomeMessageUseCase.kt:108-116` already contains the right condition to re-offer migration:
  `getOrchardBalance().value > 0L && plan == null` → shows the `REQUIRED` banner.
- But `MigrationPlanRepository`'s persisted plan is **never cleared** after a migration completes, and
  `hasSeenMigrationCompleteStorageProvider` is a **single global flag**, not per-round. So after the first
  run finishes, `plan` stays non-null and the "seen complete" flag latches permanently — the home banner
  goes silent for good, even if the user's residual Orchard balance is still well above the migration
  threshold and genuinely needs more runs.

Separately, commit `fca2c1196` ("Add UI for Keystone multi-round migration info") already wired up UI
groundwork for a "Round X of Y" indicator (`MigrationPlan.keystoneRound` / `MigrationReviewState`), but the
field is always `null` today because nothing computes or supplies it.

## 2. Scope

- **Keystone (hardware-signing) accounts only.** Hot-wallet accounts can already build/sign an entire run
  in one `commit_preparation` call with no hardware constraint; multi-run continuation for hot wallets is
  deferred to a later pass.
- Within one run, the existing Keystone QR-signing chunking (`KeystoneBatchChunking`,
  `KEYSTONE_BATCH_MAX_ITEMS = 35`, sign-everything-then-broadcast-once-per-run) is **unchanged** — this
  design is only about what happens *between* runs, not within one.
- Round N+1 is entered by **replaying the entire flow from Setup** (Setup → How It Works → Privacy/Tor
  sheet → battery-exemption ask → notification ask → Review → Keystone Sign/Scan) — no "skip intro"
  branch. This is a deliberate simplification: it avoids a new code path and its own edge cases, at the
  cost of the user re-confirming already-decided settings (mode, Tor, battery) each round.

## 3. Fix 1 — Auto-continuation (root cause, must-fix regardless of round-count display)

When a round's SDK `MigrationState` reaches a terminal `Complete` state:

1. Check current Orchard balance against the existing "worth migrating" threshold
   (`RESIDUAL_MIGRATION_MIN`, the same constant that already governs whether *any* migration is offered).
2. **If residual balance is still above threshold** (more runs are needed):
   - Clear `MigrationPlanRepository`'s stored plan (set to `null`).
   - Do **not** set `hasSeenMigrationCompleteStorageProvider`, and do not show the one-time Complete
     screen for this round.
   - The existing `GetHomeMessageUseCase` condition (`balance > 0 && plan == null`) now naturally
     re-evaluates to `REQUIRED` — no new banner phase, no new routing logic. Tapping it replays the full
     flow from Setup for the next run, per §2.
3. **If residual balance is below threshold** (or a fresh `plan_migration` reports nothing left to
   migrate): behave exactly as today — show Complete, set the seen flag.

This is the only change strictly required to make multi-run migrations work end-to-end for Keystone
accounts. It has no dependency on the round-count feature below.

## 4. Fix 2 — "Round X of Y" display

Depends on a currently **uncommitted** local change already sitting in the `zcash-android-wallet-sdk`
working tree (`backend-lib/Cargo.toml`), which bumps the `librustzcash` path-dependency pin from the
previously-tracked `origin/feat/pool-migration-sqlite @ 083bb6131805` to `main-latest`
(tracking `origin/main` directly). This has already absorbed the whole PR stack (#2669, #2712-#2715)
including `RunEstimate`/`MigrationRunEstimate`. Verified: `cargo check --lib` in `backend-lib` compiles
clean against this change today (only pre-existing deprecation warnings, no errors) — the compile break
previously logged in `2026-07-22-migration-flow-full-spec.md` §5.2 item 3 no longer reproduces.

Steps:

1. **Commit the pending Cargo.toml bump** (and its associated already-uncommitted fixes in
   `migration.rs`/`migration_engine.rs`/`tor.rs`) as its own commit, separate from this feature's work —
   it's unrelated in content (it's mostly the live-wallet Rust test suite from
   `2026-07-22-migration-flow-full-spec.md` §6.1) but is a hard prerequisite for the new engine API.
2. Add a JNI + Kotlin SDK method wrapping `estimate_migration_runs(...)` +
   `MigrationRunEstimate::run_count()`, exposed on `OrchardMigrationSdk`.
3. Call it **fresh, every time Setup is entered** (both for the very first run and every subsequent
   round), over whatever the *current* residual Orchard balance is. Display "Round 1 of N" where N is
   that fresh result.
4. **No round index or total is persisted anywhere.** `estimate_migration_runs` is a stateless preview —
   it has no memory of prior rounds, so recomputing it fresh each time is both simpler and always
   accurate. This deliberately means the displayed count can appear to "reset" if the user's balance
   grows mid-campaign (e.g., "Round 1 of 3" the first time, "Round 1 of 2" on the next round if less
   remains than expected, or a higher number if new funds arrived) — accepted as correct, not a bug: the
   copy always answers "how many more rounds from here," not "which fixed step in an original plan."

## 5. Explicitly out of scope / deferred

- Hot-wallet multi-run continuation.
- Any change to within-run Keystone QR-batch chunking or its in-memory-until-last-round signature
  accumulation (tracked separately, not part of this design).
- Any upstream `librustzcash` change — this design consumes `estimate_migration_runs` read-only; no core
  engine change is needed.
- Skipping Setup/intro screens for round 2+ (explicitly rejected in §2 for simplicity).

## 6. Testing notes

- Root-cause fix (§3) is a Kotlin-only change (`GetHomeMessageUseCase`/whatever use case owns
  finalization) — should get a unit test simulating: round completes with residual balance above
  threshold → plan cleared, seen-flag untouched, banner condition re-evaluates to `REQUIRED`; and the
  complementary case (residual below threshold → today's Complete behavior unchanged).
- Round-count feature (§4) needs the Cargo.toml bump commit to land first; then a small Rust-side test
  or existing coverage confirming the new JNI method returns a sane `run_count()` for a multi-run (whale)
  synthetic balance, plus a Kotlin test that Setup/Review display the fresh count.
