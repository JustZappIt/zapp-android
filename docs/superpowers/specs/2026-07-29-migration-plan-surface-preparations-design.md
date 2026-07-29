# Surface note-split (preparation) transactions in the migration plan

**Goal:** Carry the note-split (preparation) transactions through the propose-time migration
schedule — data librustzcash already exposes but our `encode_migration_schedule` drops — so the UI
shows every scheduled step (`Split balance 1..N` above `Transfer 1..M`), the Lane B initial arm
targets the earliest not-yet-broadcast step (killing the ~34-min prep-broadcast delay), and the
app-open overdue auto-jump to Progress is removed.

**Architecture:** One additive `preparations` channel from Rust → JNI → Kotlin SDK → app model →
UI. **Runtime execution (Lane A prove / Lane B broadcast) is NOT touched** — it already reads the
engine's kind-agnostic runtime next-due (`getMigrationTransferStates` / `executeNextPendingTransfer`),
which already serves preparations and transfers identically. Only the three propose-time consumers
change: UI display, the Lane B/due-alarm initial arm, and (implicitly) any pre-scheduling that read
the crossings-only schedule.

**Tech stack:** Rust JNI (backend-lib), Kotlin SDK (sdk-lib), Kotlin/Compose app (ui-lib), Koin.

## Global Constraints

- **Additive only.** `MigrationSchedule.transfers` stays crossings-only (11 in the reference wallet);
  preparations go in a NEW parallel `preparations` list. No existing consumer of `transfers` (counts,
  copy) changes semantics.
- **Headline/count copy = crossings**, never crossings+preparations. "N transfers" stays the
  crossing count.
- **No execution-path changes.** Lane A/B runtime code is out of scope; it is already correct and
  kind-agnostic.
- **Anchor boundaries are NOT in the propose schedule** (drawn at commit; preparations use a natural
  anchor / `boundary=None`). The propose model carries broadcast heights + dependencies only.
- Build/verify: `-Pcoverage=false --max-workers=1 -PSDK_INCLUDED_BUILD_PATH=../zcash-android-wallet-sdk`,
  variant `Zcashtestnet…`, emulator-5556 only. SDK build needs `~/.cargo/bin` on PATH.
- Reference wallet (for test expectations): 4 preparations across 3 layers (L0: id0,id1 no-dep;
  L1: id2 deps id0,id1; L2: id3 deps id2) + 11 crossings.

---

## Reference data (librustzcash API already present)

`zcash_pool_migration::MigrationPlan` exposes everything needed:
- `planned_transactions() -> Vec<PlannedTx>` — every tx with `id` + `kind` (`Preparation{layer,index}`
  / `Transfer{crossing}`).
- `preparation() -> &PreparationPlan` → `.layers() -> &[Vec<PrepTransaction>]` — prep txs + their
  cross-layer dependency structure.
- `prep_schedule() -> &[Vec<BlockHeight>]` — per-layer preparation broadcast heights.
- `schedule() -> &[Schedule]` — crossing broadcast + expiry heights (already consumed).

No upstream change. Our `encode_migration_schedule` currently iterates only `schedule()`.

---

## Component 1 — SDK: surface preparations in the schedule

**Rust — `backend-lib/src/main/rust/migration.rs` (`encode_migration_schedule`)**
Alongside the existing per-crossing `schedule()` loop, emit a second array of preparation entries:
for each `planned_transactions()` entry with `kind = Preparation{layer,index}`, read its broadcast
height from `prep_schedule()[layer][index]` and its dependency ids from the plan, and encode
`(id, layer, index, broadcast_height, depends_on[])` into a new JNI field on the schedule object.

**JNI model — `backend-lib/.../internal/model/migration/JniMigrationModels.kt`**
Add `class JniPreparationStep(id: Long, layer: Int, index: Int, broadcastHeight: Long,
dependsOn: LongArray)` and a `preparations: List<JniPreparationStep>` field on `JniMigrationSchedule`.
Mirror the encoding on the Rust ↔ JNI boundary (hand-written JNI, same pattern as the existing
transfer array).

**Kotlin SDK — `sdk-lib/.../MigrationSdk.kt` + `OrchardMigrationSdkImpl.kt`**
- Add `data class PreparationStep(id: Long, layer: Int, index: Int, broadcastHeight: Long,
  dependsOn: List<Long>)`.
- Add `val preparations: List<PreparationStep>` to `data class MigrationSchedule`.
- Map `JniPreparationStep.toPublic()` and include it in `JniMigrationSchedule.toPublic()`.

## Component 2 — App: carry preparations in the plan model

**`ui-lib/.../common/model/migration/MigrationPlan.kt`**
Add a `preparations: List<MigrationPreparation>` alongside `transfers`, where
`MigrationPreparation(id, layer, index, scheduledAtEpochSeconds, dependsOn, status)`. Populate it when
the plan is built from `MigrationSchedule` (broadcast height → epoch seconds via the same
height→time conversion the transfers use). `withLiveState(...)` correlates each preparation to its
live `MigrationTransferState` by stable id (the live states already carry preparations with
`isTransfer = false`, `isProved`, `isSent`) to fill `status` + the sent/synced times.

## Component 3 — App: UI shows `Split balance 1..N`

**`MigrationProgressState.kt` / `MigrationProgressVM.kt` / `MigrationProgressScreen.kt`**
- Replace the single collapsed "Split Balance" row with **one row per preparation**, labelled
  `Split balance 1..N`, rendered with the SAME transaction-style status the transfer rows use
  (`Sent {relative}` when sent, `Sending…` in flight, `~{relative}` when scheduled, natural
  dependency wait implied by its scheduled time). Rows sit above `Transfer 1..M`; existing transfer
  rows and ordering are unchanged.
- **Debug-only `sync {relative}` field** on every row (split AND transfer): the prove/anchor time
  (`isProved` → its synced time), formatted with the SAME relative formatter as the sent time
  ("o X min" / "pred X min"). Gated behind the existing debug flag; absent in release UI.
- Headline count stays the crossing count (`transfers.size`), not `transfers + preparations`.

## Component 4 — Lane B / due-alarm initial arm

**`ui-lib/.../common/usecase/FinalizeMigrationScheduleUseCase.kt` (`delayUntilFirstTransfer`)**
Currently `sched.transfers.minByOrNull { nextExecutableAfterHeight }` → first crossing → ~34 min.
Change to the **minimum broadcast height across `sched.transfers` AND `sched.preparations`** — i.e.
the first not-yet-broadcast step (the first preparation in practice). Both arms that consume this
delay must use it: `migrationScheduler.schedule(...)` (Lane B WorkManager) and the ready-to-send
due alarm (`MigrationDueAlarmScheduler`) — verify the alarm shares the same delay source and fix
both. Lane A (`migrationSyncScheduler`, flat 60 s) is already correct and unchanged.

## Component 5 — Remove app-open auto-navigation (except Tor)

**`ui-lib/.../common/usecase/CheckMigrationRecoveryUseCase.kt`**
- **Keep:** `pendingMigrationTorFailure → replaceAll(Home, MigrationSending)` (background Tor failure;
  has its own home representation).
- **Keep:** the Lane A/B revival block at the top (worker self-heal — not navigation).
- **Remove:** the `hasOverdueTransfers() → Progress`, `requiresAttention → Transfer Invalid`,
  `isTransferReadyToSendWithoutBackground → Transfer Review`, and `Complete → celebration`
  auto-routes. All of these states remain reachable manually: the home banner's
  `onMigrationMessageClick` (HomeVM) already routes `hasAttention → Invalid`,
  `isComplete → Complete`, `isReadyToSend → Review`, `plan != null → Progress`.
- Verify (implementation step) that GetHomeMessageUseCase surfaces each of these on the home banner
  so nothing becomes unreachable, and that the Tor-failure state has a home representation.

## Testing

- **SDK Rust:** unit test on `encode_migration_schedule` (or the migration integration test) asserting
  the encoded schedule carries the preparation entries with correct `(layer, index, broadcast_height,
  depends_on)` for a multi-layer plan — reference: 4 preparations / 3 layers.
- **SDK Kotlin:** `JniMigrationSchedule.toPublic()` maps `preparations`; `MigrationSchedule.preparations`
  populated.
- **App model:** `MigrationPlan` built from a schedule with preparations exposes them; `withLiveState`
  correlates preparation status by id.
- **`delayUntilFirstTransfer`:** with preparations present, returns the delay to the earliest step
  (a preparation), not the first crossing.
- **`MigrationProgressVM`:** state renders N `Split balance` rows with per-row status; debug sync
  field present only under the debug flag; headline count = crossings.
- **`CheckMigrationRecoveryUseCase`:** only the Tor-failure route navigates on app open; overdue /
  attention / ready-to-send / complete no longer auto-navigate; Lane A/B revival still fires.

## Out of scope

- iOS: mechanically Android-only (our backend-lib encode). The UX pattern (per-split rows) should be
  reported to iOS/Kris for alignment, but no iOS code here.
- Runtime execution (Lane A/B loops), anchor-bucket logic, denomination planner — unchanged.
- Cross-platform notification model redesign.
