# Keep the Synchronizer alive (paused) during a migration sync-block — design

Date: 2026-07-24
Status: Approved (design), pending implementation plan

## Problem

While a migration transfer is pending/broadcasting, the app deliberately pauses wallet sync for
privacy decorrelation (`OrchardMigrationSdk.isSyncBlocked()` — height-based `overdue` plus a
10-minute post-broadcast buffer). Today that pause is implemented by **tearing the Synchronizer
down**:

- `WalletCoordinator` (sdk-incubator-lib) folds `isSyncBlocked` into the `combine → flatMapLatest`
  that owns the Synchronizer. When it flips true, the branch switches to
  `InternalSynchronizerStatus.Blocked`, the owning `callbackFlow` is cancelled, and
  `awaitClose { closeableSynchronizer.close() }` runs. The public
  `WalletCoordinator.synchronizer: StateFlow<Synchronizer?>` then maps `Blocked → null`.
- With the Synchronizer null, the app's data sources that `flatMapLatest` over it collapse to null:
  `WalletSnapshotDataSource` emits `null` (`if (synchronizer == null) flowOf(null)`), and
  `AccountDataSource.allAccounts` emits `null` (`?: flowOf(null)`). Balance and status go null.

Result: during a migration send the whole app shows a generic, stuck-looking **loading** state,
and — because the home-message pipeline needs a non-null `WalletSnapshot` — the **migration banner
that should explain what's happening never renders**. Confirmed live: after a transfer broadcast
(`transfer sent — txId=…`) the app sat in loading for the 10-minute privacy buffer.

## Goal

During a migration sync-block, keep the wallet looking **normal** — last-known balance and state
visible, no stuck spinner — and let the existing **migration banner/status** convey what's
happening (including that a transfer was sent). Preserve the privacy decorrelation (no wallet sync
network activity during the block).

## Non-goals

- Changing the `isSyncBlocked` decision itself (height-based overdue + 10-min buffer) — unchanged.
- Changing the migration worker / broadcast flow.
- App-side caching of last-known balance (the rejected "Approach A'"): unnecessary once the
  Synchronizer stays alive and its StateFlows keep serving last-known values.
- Rust / `slipstream_core` changes: the pause primitive already exists in Kotlin (see below).

## Key finding: the engine already separates *pause* from *teardown*

`SlipstreamEngine` (com.zodl.slipstream.internal) exposes, distinct from destruction:

- `startPolling()` — launches the poll loop (`tick()` every `POLL_INTERVAL_MS`) that drives sync.
- `stopPolling()` — cancels the poll loop only (`pollJob?.cancel()`). **Native handle and all
  StateFlows (`networkHeight`, status, DB-backed balances) stay alive and readable.**
- `stop()` — native quiescence (bounded abort/drain), keeps the handle.
- `free()` / `shutdown()` — actual teardown (handle → 0, scope cancel). Only these destroy state.

`SlipstreamSynchronizer.close()` currently runs `stopPolling → stop → free → shutdown` (full
teardown). So a "pause scanning but stay alive & readable" is achievable with `stopPolling()` alone
— pure Kotlin, no Rust. This is why tearing down was never necessary for the pause; it was just the
only path wired up.

## Design

### 1. `CloseableSynchronizer` / `SlipstreamSynchronizer` — add `pause()` / `resume()`

- `pause()` → `engine.stopPolling()`. Halts the sync poll loop (no new network fetches →
  decorrelation preserved); does **not** call `stop`/`free`/`shutdown`, so the handle, DB access,
  and StateFlows remain live. Idempotent.
- `resume()` → `engine.startPolling()`. Idempotent; safe to call when already polling.
- Status while paused: report a **settled** value so downstream UI stays normal. Wrap the exposed
  status as `combine(engine.status, pausedState) { s, paused -> if (paused) Status.SYNCED else s }`
  (paused-state held in a `MutableStateFlow<Boolean>` toggled by `pause`/`resume`). This keeps the
  app "looking normal" with zero app-layer change; the migration banner (now able to render again)
  carries the real "migration in progress / transfer sent" message.
- Add `pause()` / `resume()` to the `CloseableSynchronizer` interface. Legacy `SdkSynchronizer`
  (non-slipstream path) implements them as a best-effort/no-op — the app runs
  `isSlipstreamEnabled = true`, so slipstream is the only live path, but the interface must stay
  total.

Decision — `stopPolling()` (light) vs `stop()` (native quiescence): use **`stopPolling()`**. It
stops the loop, keeps the engine warm (instant resume, StateFlows intact), and stops issuing new
network ticks. An in-flight tick may complete once after pause; that is acceptable for
decorrelation (no *new* sync activity, and the broadcast is separated by the ≥10-minute buffer on
both sides). `stop()` is available if stricter quiescence is ever required, at the cost of a heavier
`start(ufvk, birthday)` resume.

### 2. `WalletCoordinator` — stop tearing the Synchronizer down on block

- Remove `isSyncBlocked` from the `combine → distinctUntilChanged → flatMapLatest` key that builds
  and (on change) closes the Synchronizer. The Synchronizer's lifecycle keys on wallet + lockout
  only, so toggling `isSyncBlocked` no longer cancels the owning `callbackFlow` (no `close()`).
- Delete the `else if (isSyncBlocked) → Blocked` branch and the `Blocked → null` mapping. The public
  `synchronizer` stays non-null across a migration block.
- Observe `isSyncBlocked` **separately** and drive `synchronizer.pause()` / `resume()` on the live
  `Available` instance (e.g. a small coroutine that collects `isSyncBlocked` and the current
  synchronizer, calling pause/resume on transitions). Re-pause a freshly (re)built Synchronizer if
  the block is still active when it appears.

### 3. App layer — no changes required

With the Synchronizer non-null and reporting a settled status during the block:
- `WalletSnapshotDataSource` / `AccountDataSource` keep emitting live (last-known) data — balance and
  state stay visible.
- The home-message pipeline runs again → the migration banner renders and conveys the state.

The only app-side task is a **verification pass**: audit consumers of `Synchronizer.Status` for
assumptions that a settled/`SYNCED` status implies a fully-synced chain tip during this window
(e.g. spend-readiness gating). The window is short and migration-controlled, but any consumer that
would act incorrectly on a paused-but-"SYNCED" status must be identified and, if needed, gated on
the `isSyncBlocked` signal the app already computes.

## Components touched

- `SlipstreamSynchronizer` — `pause()`/`resume()`, paused-state flow, status wrapping.
- `CloseableSynchronizer` interface — `pause()`/`resume()` declarations.
- `SdkSynchronizer` (legacy) — no-op/best-effort `pause()`/`resume()`.
- `WalletCoordinator` — lifecycle restructure (drop `isSyncBlocked` from the rebuild key; add
  separate pause/resume driver; remove `Blocked`).
- App: verification only (no functional change expected).

## Risks

- **`WalletCoordinator` restructure is the riskiest part.** Ordering, `distinctUntilChanged`, and
  the documented cold-start subtlety (`isSyncBlocked`'s first value arriving after the initial
  combine) must be preserved so a cold start doesn't cancel an in-flight `Synchronizer.new()`.
- **Settled status semantics.** Reporting `SYNCED` while paused could mislead a status consumer
  (see the app verification pass). Mitigation: audit; fall back to gating those consumers on
  `isSyncBlocked` if any misbehave.
- **In-flight tick after pause.** `stopPolling()` lets one in-flight tick finish. Acceptable given
  the buffer; note it rather than adding `stop()` quiescence pre-emptively.
- **Cross-account block.** `isSyncBlocked` with a null account checks every account; pause/resume
  must key on the same aggregate signal so a block from any account pauses the single Synchronizer.

## Testing

- `SlipstreamSynchronizer`: `pause()` calls `engine.stopPolling()` and not `free`/`shutdown`;
  `status` emits `SYNCED` while paused and reverts to `engine.status` after `resume()`; `resume()`
  calls `engine.startPolling()`; both idempotent.
- `WalletCoordinator`: the public `synchronizer` stays the **same non-null instance** across an
  `isSyncBlocked` false→true→false toggle; `pause()`/`resume()` are invoked on the transitions; a
  Synchronizer built while already blocked is paused.
- Manual emulator verification: trigger a migration transfer broadcast; confirm the app shows
  last-known balance (not a stuck loader), the migration banner is visible during the 10-minute
  buffer, and normal sync resumes after the block clears.
