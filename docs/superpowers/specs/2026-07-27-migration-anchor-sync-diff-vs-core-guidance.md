# Migration background anchor/sync — diff: our understanding vs. core-team guidance

Date: 2026-07-27
Status: Research synthesis. Diffs the baseline model in
`2026-07-27-migration-background-anchor-sync-understanding.md` against (1) Kris's Slack answers
2026-07-24→27, (2) ZIP 318 normative text (https://zips.z.cash/zip-0318), (3)
zcash-swift-wallet-sdk PR [#1853](https://github.com/zcash/zcash-swift-wallet-sdk/pull/1853) +
[#1813](https://github.com/zcash/zcash-swift-wallet-sdk/pull/1813) review threads + upstream
librustzcash, and (4) the actual Android code on `android-slipstream-ironwood-chp`.

Sources are cited inline as: `[Slack ts]` = #orchard-ironwood-migration unless noted;
`[r…]` = swift-sdk GitHub review comment; `[ZIP 318]` = live spec text.

---

## TL;DR verdict table

| Baseline claim (§ in baseline spec) | Verdict | What the evidence says |
|---|---|---|
| §1 Plan → confirm → note split + N future txs → pre-sign → schedule worker | CONFIRMED | Matches design and code. |
| §1.4 Pre-signed "without anchor or with current fake?" | RESOLVED: **anchor-less** | ZIP 374 bare notes; real anchor+witness installed at prove time (PCZT Updater). `migration.rs` `try_prove`. |
| §1.6 Worker at scheduled time: swap anchor + prove + **send in one step** | **WRONG (per design intent)** | Proving is meant to be opportunistic, in the sync path, long before broadcast. Kris: "Proving should be done opportunistically, not at broadcast time" [r3651582760]. The fetch→prove→broadcast weld: "This is a bug!" [Slack 1784977852]. |
| §2 Background sync only daily/3am → anchor never available | CONFIRMED | Kris confirms the constraint is "the crux" [1784977604]; Vizor "absolutely need sync in the background - they just set a support floor to get it" [1784977678]. |
| §3 Our fix: sync burst instead of send, reschedule +10 min | **PARTIALLY ALIGNED — letter yes, spirit no; stopgap on the wrong axis** | See §D3. Never explicitly approved nor rejected. |
| §4 Open question unanswered | **PARTIALLY WRONG — Kris answered the principle on 07-25** | Answer exists [1784932214, 1784977527…]; the concrete "is 10 min enough" and the background sync-cadence spec remain open. |

---

## A. What the core team actually said (the answer we thought we didn't have)

Kris replied in our own thread (#orchard-ironwood-migration, parent ts 1784910426.257129):

> "The way that this is intended to work is that as frequently as the app can (say once an
> hour) it wake up and do a little bit of sync. When the proof is updated then can either be at
> the scheduled send time, or any time the app can do a little bit of processing, we should
> take advantage of that window for those transactions that are candidates (their pre-chosen
> proof bucket block has been scanned)." [1784932214, 2026-07-25]

> "The broadcast step should not sync. But what we need background processing to do is sync to
> update the proofs *independent* of broadcast." [1784977527]

> "There's no way around updating the proofs if privacy is required." [1784977557]

> "We absolutely should be separating the session." [1784977810]

On the welded `executeNextPendingMigrationTransfer` (fetch → prove → extract → broadcast →
record in one atomic call): "**This is a bug!**" [1784977852]

And on GitHub, the same position normatively:

> "At the time of broadcast, the wallet should not do any syncing **or proving** - it should
> *exclusively* broadcast the transaction." [Kris, r3651582760, 2026-07-26]

PR #1853 is the core team's implementation of this for the Swift SDK ("the next PR towards the
full private migration support" [#core-wallet 1785036718]): `prove_pending` sweeps everything
provable during sync; `next_due_transfer` never proves and reports `AwaitingProof` distinctly.
Wiring the sweep into the sync pipeline is explicitly left to the Synchronizer-level API
(#1812): "#1812's Synchronizer surface is the natural caller" [#1853 body].

## B. What ZIP 318 normatively requires

- "The wallet MUST NOT perform a wallet synchronization in the same background session as a
  migration broadcast … Where a synchronization is needed, it MUST be separated in time from
  any broadcast." [ZIP 318]
- "A single background processing window MUST be used either to synchronize (**updating the
  anchors and proofs** of upcoming transactions) or to broadcast, never both." — note: ZIP 318
  groups **proving with the sync window**, not with the broadcast window. Android-specific
  restatement: "A single Worker execution MUST either synchronize (updating anchors and
  proofs) or broadcast, never both." [ZIP 318, Platform considerations]
- "Whenever the application is granted background processing time, the wallet SHOULD
  synchronize and construct the proofs of upcoming scheduled transactions … but MUST NOT
  broadcast … within the same background processing window." [ZIP 318]
- **No numeric quiet-gap is specified anywhere.** "Separated in time" is
  implementation-defined. The only 10-minute constant in the ecosystem is the Swift SDK's own
  `MigrationSyncGate` in #1812 — and its direction is **broadcast→sync**, not sync→broadcast.
- Rationale (what leaks): "Linking synchronization and broadcast in the same background
  session would let an observer correlate the two events and thereby link the broadcast to the
  wallet." Sync is wallet-identifying (scan-range requests, IP/session); the transfer's anchor
  is a cohort-shared boundary — pairing them de-anonymizes the pool-crossing tx and defeats
  both the anchor cohort and the Poisson schedule. Proving late additionally sharpens the
  anchor-age posterior ("a recent anchor implies proving shortly after it") — i.e.
  prove-at-broadcast erodes privacy even beyond the traffic-correlation risk.
- Related SHOULD we already track as an app gap: "sending [overdue transfers] at
  application-open time correlates the broadcasts with the wallet's sync activity" → user
  disclosure on the Send Now path. [ZIP 318, Fallback on application open]

## C. Facts that repair baseline uncertainties

1. **Pre-sign is anchor-less** (ZIP 374): PCZTs built with bare notes, no anchor/witness;
   `try_prove` installs the real anchor + witness at prove time. Baseline §1.4 resolved.
2. **Anchor availability is NOT the risk.** The 07-22 "boundary pruned before broadcast
   height" failure (boundary ~144+ blocks old vs ~100-block pruning) was fixed upstream by
   (a) prove-early (`0ab556bf07`, PR #2710: prove as soon as the boundary settles below the
   tip) and (b) durable anchor-checkpoint retention (`ANCHOR_RETENTION_INTERVAL = 144`,
   every boundary checkpoint retained indefinitely; wired in `put_blocks`). Kris:
   "anchor availability was never the risk here … proving late never loses a transfer's
   anchor — this was latency and failure-reporting, not correctness" [r3651686661]. Our
   Android layer mirrors the retention wiring (`min_pending_anchor_boundary` →
   `anchor_retention_height`, `migration.rs:132-141`). What late proving *does* cost:
   broadcast-path latency (mobile Orchard+Ironwood proving against a ZIP 203 expiry) and the
   anchor-age privacy blunting above.
3. **The 10-minute buffer is our (and #1812's) invention**, not core guidance. No required
   duration exists; the normative unit is the *session/Worker execution*.
4. **Bucketing parameters are now configurable** upstream (librustzcash #2759; testnet
   shortening = swift #1836 / android #2042) — the "background sync cadence for Andrea's
   build" blocker interacts with this, not with retention.

## D. The diff — where our shipped design stands

### D1. CONFORMANT: burst run vs broadcast run split

`MigrationWorker`'s `WAIT_AND_RETRY` branch syncs and deliberately does **not** broadcast in
the same run; broadcast happens in a later Worker execution. That satisfies the ZIP 318 MUST
by the letter (separate Worker executions), and the ≥10-min gaps on both sides exceed anything
normatively required (nothing numeric is required).

### D2. VIOLATION: proving still runs in the broadcast Worker execution

`MigrationWorker.doWork()` calls `sdk.finalizeReadyTransfers()` (the prove sweep) at the start
of **every** run — including the run that broadcasts. Same in the foreground
(`MigrationSendingVM` line ~112: finalize → execute). ZIP 318 groups "updating anchors and
proofs" into the sync window ("either … or broadcast, never both"), and Kris is explicit:
"at the time of broadcast … no syncing **or proving** … *exclusively* broadcast"
[r3651582760]. In the current sequencing, the proof for the due transfer is typically computed
in the broadcast run (in the burst run, finalize executes *before* the burst, when the
boundary isn't scanned yet — so nothing proves; after the burst nothing re-proves). This is
the same weld Kris labeled "This is a bug!" — ours is split across the fetch/prove vs
broadcast boundary differently than iOS's, but the prove+broadcast half of the weld remains.

### D3. SPIRIT GAP: the sync trigger is causally tied to a specific broadcast

Our burst fires *because* a specific transfer is nearly due, and the broadcast follows
deterministically ~10 min later from the same wake chain. ZIP 318's rationale assumes the
opposite: "Provided synchronization and broadcast never share a background processing window,
the server learns nothing that correlates a specific synchronizing wallet with a specific
migration transaction." A deterministic sync→(+10 min)→broadcast pattern hands the server
exactly that correlation candidate. (Inference — no explicit ruling exists; nobody has
reviewed the Android pattern, and #1812 has zero review comments.) Kris's model removes the
coupling at the root: sync+prove on an **independent, roughly periodic cadence** ("say once an
hour"), broadcast as a standalone sync-free action when the schedule fires — by then the proof
already exists, so the broadcast-time wake needs no network state at all beyond the tip check.

### D4. STATUS of the open question

Answered in principle (§A); **still open in practice**:
- No explicit yes/no on "sync burst + broadcast 10 min later" as an interim mechanic.
- No specified background sync cadence (Dominik's 07-25 "we still need to figure out this
  part" got no cadence answer; Michal 07-26: "background sync won't work until we resolve
  this somehow" [#wallet-team 1785051597]).
- danny's #1853 review approval explicitly notes the app-side wiring "is the missing context"
  — core has not yet seen or ruled on any app's actual background orchestration.

## E. Recommended Android changes (proposed, not yet agreed)

Ordered by how directly they follow from the evidence:

1. **Move proving out of the broadcast run.** In `MigrationWorker`: call
   `finalizeReadyTransfers()` only in sync-type runs — i.e. *after* a successful
   `syncBurst()` in the `WAIT_AND_RETRY` branch (proof is local computation; ZIP 318 groups
   it with the sync window) — and *skip* it in the run that broadcasts. The broadcast run then
   only serves the stored proof (`nextDueTransfer` already never proves). Mirror in
   `MigrationSendingVM` (foreground sync is live there, so finalize can ride the
   synchronizer instead of prefixing the send).
2. **Wire the prove sweep into the sync path** (the #1853 "Synchronizer surface" analog):
   call `finalizeReadyTransfers()` as blocks are scanned / at sync-session end, so proofs are
   produced hours before their broadcast windows whenever the app is open or any background
   sync runs. Our FFI already supports this shape (idempotent, skip-not-fatal).
3. **Decouple the sync trigger from the broadcast schedule.** Replace (or supplement) the
   due-transfer-triggered burst with a periodic background sync+prove cadence during an
   active migration (Kris's "say once an hour" — exact cadence needs core-team confirmation;
   this is the still-unanswered ask). The burst can remain as a fallback, but ideally the tip
   is already ahead when a transfer comes due, and the burst never fires.
4. **Keep asking, with a sharper question.** The open ask for the core team is no longer "is
   sync+10min OK?" but: (a) confirm the interim burst mechanic is acceptable until periodic
   cadence lands, and (b) specify the background sync cadence + jitter for migration mode
   (WorkManager periodic minimum is 15 min; "once an hour" fits).
5. Optional parity: tri-state `AwaitingProof` return (as #1853). Behaviorally our
   `null` + `hasOverdueTransfers` (`next_broadcastable` requires `Proved`) already routes
   due-but-unproven into `WAIT_AND_RETRY` correctly; the tri-state would only improve
   diagnostics.
6. Track separately (already-known gap, reconfirmed by ZIP 318 SHOULD): sync-correlation
   disclosure copy on the app-open **Send Now** fallback.

## F. Coverage caveats

- Slack: `from:` Kris exhaustive 07-22→07-25 13:12 and 07-26 20:16→07-27 06:17; the 07-25
  afternoon→07-26 evening window covered by targeted keyword search only. str4d/daira: no
  relevant messages found. John: pinged in our thread, never replied there.
- GitHub: #1812 has zero review comments; librustzcash PR #2700 body not fetchable; no
  post-07-22 thread literally frames "288/144" (the recorded numbers are "~144+ boundary age
  vs ~100-block pruning", resolved as §C2).
