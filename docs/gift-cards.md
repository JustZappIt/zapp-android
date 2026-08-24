# Gift cards — design and custody contract

Tracked companion to the gift-card code. Production comments cite this file by section number, so
**the numbering is an API**: renumber a section and you break every reference to it. Sections carry
the numbers the code already cites; the gaps are deliberate.

`docs/GIFT_CARDS_PLAN.md` is the working build plan and is gitignored. It does not travel with the
branch and goes stale. Anything a reader of the code needs — the threat model, the wire contract,
what recovery is guaranteed, and what the SDK will not do — lives here instead.

## 1. What a gift card is

A gift card is a **bearer instrument**. Creating one mints a throwaway 24-word seed, derives a
unified address from it, and sends the gift amount there from the sender's wallet. The link carries
that seed. Whoever holds the link can sweep the card into their own wallet, and nobody else can stop
them.

Three properties follow, and everything else in this document is a consequence of one of them:

- **The seed is random, not derived from the wallet seed.** The recovery phrase cannot rebuild a
  card. (The alternative — a dedicated ZIP 32 path — stays open; ZIP 326 confirms Ironwood needs no
  new derivation path. Out of scope for v1.)
- **There is no reclaim.** A sender cannot pull the funds back. Money on a card is gone from the
  sender's balance the moment the funding mines.
- **There is no revocation and no binding to a recipient.** A link that leaks is money that leaks.

## 2. Link format (normative — shared with iOS)

A card minted on Android must claim on iOS and vice versa, so this section is a contract, not a set
of local implementation choices. Implemented in `screen/gift/model/GiftLinkCodec.kt`.

```
https://gift.justzappit.xyz/c/v1#k=<base64url(payload)>
```

Base64url, URL-safe alphabet, **unpadded** on encode, padding accepted on decode. The payload is
base64url'd UTF-8 JSON:

| Field | Type | Notes |
|---|---|---|
| `v` | int | `1` |
| `network` | string | `main`/`test`. Reject on mismatch |
| `amountZatoshi` | string | Decimal **string**, not a number — JSON numbers decode to doubles in too many parsers, which would silently round a large card |
| `mnemonic` | string | **The bearer secret.** 24 words |
| `birthdayHeight` | int | Chain tip at creation |
| `createdAt` | string | ISO-8601 UTC |
| `expiresAt` | string? | Advisory only. Nothing on chain enforces it |
| `message` | string? | ≤128 grapheme clusters, ≤512 UTF-8 bytes |

`Json { explicitNulls = false }`, so absent optionals are omitted. **`ignoreUnknownKeys` is not set
on the codec**: an unknown field means the link came from something we do not understand, and this
one carries spendable money. (The stored-record store is strict for a different reason — see §7.2.)

### 2.1 Evolving the wire format

Strictness and "normative, shared with iOS" pull against each other, and the resolution is a rule
rather than a loosened decode. **Any field added to this table is a `v` bump.** A build that meets a
field it does not know cannot reason about what the field changes — whether it narrows who may
claim, or caps the amount — so acting on the rest of the payload is a guess about money.

What the strictness must not do is *lie*. A refusal that reads as "this link is broken" sends the
recipient back to a sender who cannot re-mint the card: there is no reclaim. So `decode` reads the
key set before it reads the values (`KNOWN_FIELDS`), and reports an unrecognised one as
`GiftLinkError.NEWER_FORMAT` — told apart from `MALFORMED_PAYLOAD`, and rendered as "update Zapp",
which is both true and actionable. An unknown `v` reports `UNSUPPORTED_VERSION` and reaches the same
copy. The key set is checked rather than the decoder's own error because kotlinx names the offending
field only inside a message that quotes the input, and the input is the bearer mnemonic.

**The secret rides in the fragment, not the query.** Everything after `#` is never sent in an HTTP
request, so the seed never reaches a server, a proxy, a `Referer` header, or a link-preview crawler.
This costs nothing on Android: intent filters have no `fragment` attribute and never match on it, so
it arrives intact via `intent.data?.fragment`.

**The card's address is deliberately not carried.** It is derived from `mnemonic`, which sits beside
it in the same link, so sending it spent ~40% of a 754-character link restating what the link already
said. Both platforms derive it identically — Android's `deriveUnifiedAddressFromSeed` and iOS's
`zcashlc_derive_address_from_ufvk` both reach `find_address(DiversifierIndex::new(),
UnifiedAddressRequest::AllAvailableKeys)` on the same UFVK — so there was never a second opinion to
check against. **That parity is the load-bearing assumption**: if either platform narrows the
receiver set or moves off account index 0, the two disagree and nothing in the link would notice.
Lock it with shared test vectors. Note that a claim would still *work* through such a divergence,
because the note is found by the viewing key rather than by the address.

Every rejection is a distinct `GiftLinkError` and happens before any network call: URI over 16 KiB
(checked as both `String.length` and UTF-8 byte size); unknown or missing `v`; unknown fields; wrong
or unrecognised `network`; `amountZatoshi` ≤ 0, over `Zatoshi.MAX_INCLUSIVE`, or
unparseable; a `mnemonic` that is not 24 valid BIP-39 words; `birthdayHeight` ≤ 0 or below the named
network's Sapling activation; unparseable `createdAt`; an over-long `message`.

**`expiresAt` is deliberately never validated.** Expiry is advisory, and a peer's clock or date
formatting must never be the reason a funded card cannot be claimed.

> **Open cross-platform divergence.** JDK 17's `BreakIterator.getCharacterInstance()` does not join
> ZWJ emoji sequences — a family emoji measures **7** clusters where Swift's `String.count` says 1.
> Android is therefore stricter than iOS on the 128-cluster bound. The 512-byte bound dominates for
> ZWJ-heavy text, so the disagreement window is narrow, but this section is normative: settle it with
> iOS before publishing vector fixtures.

## 3. Flows

### 3.5 Claim — an isolated synchronizer

The main synchronizer is owned by `WalletCoordinator`. **Do not extend it and do not route a claim
through it.** A claim constructs its own `CloseableSynchronizer` on the card's seed and closes it in
a `finally`, on every path — an engine left running holds its database files open and leaks a bearer
seed into a background scan.

The alias is **deterministic and derived from the card's address**, so an interrupted claim resumes
against the same database instead of rescanning from the card's birthday. Address, not mnemonic: the
address already identifies the card, and the alias becomes a filesystem path component.

The sequence: derive the address from the link's mnemonic (§2); verify the network; resolve the
claim birthday (§3.6); open the synchronizer, letting
`InitializeException.SeedNotRelevant` propagate (§7.1); sync to `SYNCED`; check spendability (§4);
`proposeTransfer` to the recipient's active account; classify the broadcast and only then decide
whether to delete the card's wallet (§5).

**A card's wallet is deleted by file, never through `Synchronizer.erase`.** `erase` takes an alias,
but only its database deletion honours it — it first calls `StandardPreferenceProvider.clear()` and
`EncryptedPreferenceProvider.clear()`, both SDK-wide. The main wallet's `PendingSubmitPlanStore`
lives in that same encrypted file, namespaced inside the blob rather than by file, so erasing a card
would drop resubmission metadata for unrelated transactions. Verified in the 3.0.1-SNAPSHOT AAR.

Reaching the server is bounded on **both** the claim and the check, and only that: the scan itself
runs unbounded (§11.1) and the screen offers a stop, but a server that cannot be reached at all must
say so rather than leave a bar that will never move. It surfaces as `GiftClaimError.UNREACHABLE`,
separate from `FAILED`, because "you are offline" and "something went wrong" need different copy and
neither says anything about the card.

**If the app locks mid-claim, the sync stops** and resumes on unlock. A background scan against a
bearer seed continuing past the lock screen is a battery and a privacy problem. Starting one is
refused by `GiftClaimVM.onClaim` against the same foreground signal, rather than by the lock overlay
sitting above the nav host: the overlay does make the button untappable, but that is a fact about
the view tree, and this rule is about a bearer seed, so it is held as an invariant that survives a
re-layered UI.

`importAccount` is not an alternative: it takes a UFVK, so it can import a card view-only but cannot
grant spend authority over a foreign seed.

### 3.6 Birthday — a consent gate, not a clamp

A malicious payload can set `birthdayHeight` to Sapling activation and force a multi-million-block
foreground scan. Bounding that is necessary, but **an unconditional clamp loses money**: a note is
only found by trial-decrypting the block containing it, so a birthday clamped past the funding height
finds nothing and the claim fails on a perfectly valid card. At 75 s/block, 100,000 blocks is ~87
days — a clamp would silently kill every card older than about three months, burning funds that have
no reclaim and quietly reinstating the hard expiry this design rejects.

So `GiftLinkCodec.evaluateBirthday` gates instead of clamping: above the tip is an error, below the
network's Sapling activation is an error, within 100,000 blocks of the tip proceeds, and anything
older returns `NeedsConsent(blocksToScan)` — the recipient is shown the block count and a rough time
and must agree before anything scans.

### 3.7 Deeplink intake

Gift URIs are recognised in `MainActivity` **before** the blanket `if (intent.data != null)` forward
to `ThirdPartyScan`, in both `onCreate` and `onNewIntent`. Every check in the intake is there because
of a real failure mode: non-`ACTION_VIEW` intents are ignored; `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`
is ignored, because Recents re-delivers the original intent and would re-enqueue an already-handled
claim; the host must match; the raw URI is bounded by length *and* UTF-8 byte size; duplicates are
coalesced and the pending queue is bounded.

Deeplinks forward regardless of lock state, which is safe — but **the claim VM must not start a sync
until unlocked**, for the same reason as §3.5.

## 4. Confirmations — a card is not claimable for ~12.5 minutes after funding

A freshly mined note is not immediately spendable, and the delay is a fixed property of the design:
the Kotlin SDK exposes no `ConfirmationsPolicy` type at all.

Verified from source rather than inferred. `ConfirmationsPolicy::default()` is `trusted: 3`,
`untrusted: 10` (`zcash_client_backend-0.23.0`, `src/data_api/wallet.rs:408`).
`confirmations_until_spendable` branches for `PoolType::Shielded` on a `tx_trusted` flag, on
`receiving_key_scope == Some(Scope::Internal)` for change and shielding output, and otherwise on
`confs_for_untrusted`. A gift note is shielded, arrives on the **external** scope because a third
party sent it to the card's address, and no Android API exposes `tx_trusted` — so it takes the third
branch. **10 confirmations, ≈12.5 minutes**, on top of the sender's own wait for the funding to mine.

There is no seam to widen this without forking the Rust layer (§11.1). `REQUIRED_CONFIRMATIONS` in
`GiftClaimDataSource` mirrors the Rust default; it is not read from the SDK, because nothing exposes
it.

### 4.1 "Not yet spendable" is not "empty"

If the card's `available` balance is below the card amount but its `total` is above zero, the funds
are there and simply not yet confirmed. Reporting that as an empty card tells the recipient a
perfectly good card is fake, so it is its own outcome (`GiftClaimOutcome.NotYetSpendable`), carrying
the funding transaction's confirmation count so the wait renders as progress rather than a dead end.

Every balance read sums **all** shielded pools. A card is funded to a unified address and the
*sender's* wallet picks the pool; reading one pool would report a good card as empty the moment that
changed.

## 5. Broadcast classification and cleanup

Ordinary and claim sends still fold the legacy `Flow<TransactionSubmitResult>` into `SubmitResult`.
Gift funding uses the SDK broadcaster's split API instead: it creates and stores the transaction
locally, persists that txid, then marks submission as started immediately before contacting
lightwalletd. Local proving, key, proposal, and cancellation failures therefore stay retryable and
cannot create a valid-looking empty card.

| `SubmitResult` | Meaning | Recovery state |
|---|---|---|
| `Success` | every transaction reached the mempool | **retain until finality** |
| `Partial` | some reached the mempool, some did not | **retain** |
| `GrpcFailure` | never reached lightwalletd; may or may not be on the network | **retain** |
| `Failure` | reached the server and was rejected | **retain** |
| `Error` | threw before or during submit | **retain** |

The bearer payload and destination are persisted before submission. A clean success attaches txids
but does not delete the isolated database: the SDK needs it to recover unmined transactions. Cleanup
runs only after the destination transactions reach the SDK's confirmation threshold.

Sender-side collection uses a final outgoing spend of at least the advertised amount, never the
wallet's aggregate balance. This remains correct when change, dust, or a top-up is left behind and
avoids a terminal decision from separately sampled balance/history reads.

`proposeTransfer` is wrapped for the same class of reason. A card that holds its amount but cannot
also cover the fee to move it has no proposal — `TransactionEncoderException` — and letting that
propagate raw reports a short card as an unexplained failure. It is `GiftClaimOutcome.Underfunded`,
which is deliberately *not* `NotYetSpendable`: waiting does not fix it, so it must not schedule the
45-second re-check. Reaching it at all means the card came from something that does not prepay
`CLAIM_FEE_RESERVE`, or that ZIP 317 now asks for more than it covers. The insufficient-funds
classification is shared with `ProposalDataSource` rather than copied, for the reason in §5's first
paragraph.

`ProposalDataSource.submitTransaction` cannot be used for a claim: its internals hardcode the main
synchronizer, so it would submit from the wrong wallet. Funding uses it normally; a claim submits on
its isolated instance.

### 5.1 Two readings of "confirmed", kept apart

`TransactionRepository.normalizeTransactions` reports an outgoing transaction with a block behind it
as `Confirmed`. That is a **display** choice about the history screen, and it is not finality: the
SDK's own `Confirmed` is its full threshold (§4), so routing the whole history through the strict
reading shows every ordinary send in the wallet as pending for twelve minutes after it has visibly
mined.

The custody readers therefore bypass it. `observeAccountTransaction`, `getAccountTransactions`,
`getSyncedAccountTransactionSnapshot` and `findAccountSendByRecipient` carry the SDK's own state
through untouched, because `ConfirmGiftCardFundingUseCase` marks a card funded off them and that
mark never comes back off — a card confirmed at one block is a card a reorg can empty after the
sender has been told it holds money. None of the four has a caller outside the gift feature
(`findAccountSendByRecipient` has none at all); keep it that way or the two readings collapse back
into one.

The recipient's side needs no such care and must not be given it: everything that waits for a claim
to land filters to *incoming* transactions, which the display normalisation never touched.

## 6. Recovery guarantees

This is the part that decides whether a bug costs money.

### 6.1 What is guaranteed

- **A card is persisted before its funding is broadcast.** A crash in between would otherwise lose
  the ephemeral seed and the money with it, permanently. `FundGiftCardUseCase` splits `prepare` from
  `submit` for exactly this reason, and the order between them is load-bearing.
- **Local creation and network submission are separate durable phases.** `fundingCreatedAt` records a
  locally stored txid without making the card shareable. `fundingAttemptedAt` is written only after
  local creation succeeds and immediately before submission, so deterministic pre-network failures
  remain retryable while a process killed mid-submit remains conservatively unresolved.
- **Every stored card is re-shareable from the list screen**, because `StoredGiftCard` keeps
  everything the link needs — including `network` and `birthdayHeight`, which is why they are stored
  rather than used once at creation.
- **No card is ever funded twice.** `hasFundingAttempt` is the durable gate: the note may already be
  spent, and paying twice for one gift is money gone twice. `prepare` re-reads the record it is
  handed rather than trusting the caller's copy, which is a snapshot held across a screen the sender
  can leave and return to.
- **An abandoned draft is the only *sender* record ever discarded.** (The recipient's store has its
  own discard, over receipts that never started a claim — §6.3.) `StoredGiftCard
  .isAbandonedDraft` — `DRAFT` with no funding attempt — describes an address no transaction was
  ever sent to, because `fundingAttemptedAt` is written *before* the broadcast and a failed write
  refuses to submit. `GiftCardLedger.add` drops them when a new card is minted, so editing an amount
  and continuing again supersedes the draft instead of stranding it. Without this the store only
  grows, holding key material that unlocks nothing, inside the one blob every mutation rewrites.
  Tied to minting rather than to a sweep on purpose: a sweep would have to decide when a draft is
  old enough to be dead, and that judgement is unrecoverable if it is wrong.

### 6.2 The two axes, and why they are not one enum

`GiftCardStatus` is a **delivery ordinal** — `DRAFT → FUNDED → SHARED → CLAIMED` — and it only ever
advances, by taking the maximum rather than assigning. A card that regressed would be a card the UI
stops accounting for.

Whether the money is actually on the card is a **different question**, and the enum cannot answer it.
Sharing is legal from the moment a broadcast exists, because a sender may hand the link over in the
~75 seconds before the funding mines; and since `SHARED` outranks `FUNDED` and the ordinal only
climbs, a later confirmation has nowhere to go. So the confirmation is kept off the enum entirely, in
`StoredGiftCard.fundingMinedAt`, and every caller that needs to know whether the money is really
there asks `isFundingMined`.

Two rules fall out of that, and both are load-bearing:

- **Reconciliation is scoped by `isFundingMined`, never by status.** A status-scoped sweep skipped
  precisely the cards that needed it — the ones shared during the submit-to-mine window, which no
  later pass would ever look at again.
- **Balance is not evidence a card was collected.** Collection requires the card's funding txid and
  a finalized outgoing spend of at least the advertised amount in the card's own transaction history.
  Settling is terminal — no re-share, no re-check, and no longer counted by the reset guard — so
  settling a card whose funding is still in the mempool, or was dropped and can still mine before it
  expires, strands the money.

### 6.3 A claim is retryable until finality

The recipient's mirror of §6.1, and the same distinction: `SubmitResult.Success` means every
transaction reached the **mempool**, not that it is final. A claim can expire unmined, be evicted,
or reorg after its first block.

The recipient and link are stored before broadcast. Txids are attached after submission, and the
isolated database remains in place for the SDK's unmined recovery. `ConfirmGiftClaimUseCase` waits
for the SDK's confirmation threshold, writes a finalization checkpoint, deletes the isolated
database, then removes the link. A startup/foreground coordinator reopens unsettled receipts in the
claim screen. Spendable top-ups are swept to the recipient; only fee-reserve dust may be abandoned.

The receipt is written **before the scan**, not before the broadcast, so one exists for every card
this wallet merely looked at. `ReceivedGift.hasClaimAttempt` is the line between the two, and it is
the line every consumer scopes to: `claimSubmissionAttemptedAt` or a txid means a transaction may
exist and the link is the only route to it, while neither means nothing was created and the link is
a copy of one the sender still holds.

Scoping is not cosmetic. Nothing settles a receipt with no txids — `reconcile` only examines those
that have them — so an unscoped reading makes every inconclusive tap permanent: the foreground
coordinator reopens the claim screen for good, and §6.4's guard refuses every destructive action
over a gift that was never taken. `ClaimGiftCardUseCase` therefore discards its own receipt when the
claim returns without creating anything **and when its scan is abandoned**, and every consumer —
`PendingGiftClaimCoordinator`, `hasUnsettledClaims`, and `DisconnectUseCase`'s per-account check —
ignores what is left. `DisconnectUseCase` matters most: unlike the wallet reset it offers no way to
proceed, so a receipt it refuses over and nothing can clear is an account that never disconnects.

The discard costs the recipient their stored copy of a link for a card they never claimed. That is
the right trade only because such a copy is unreachable: there is no received-gifts screen, and the
coordinator no longer surfaces it either, so retaining it is a bearer mnemonic at rest with nothing
able to spend it. The sender's own record is untouched and remains fully recoverable (§7.2).

### 6.4 The reset guard

The guard blocks for sender cards with unshared funds and for received gifts that are not settled.
It also blocks when either store cannot be read. The reset screen offers a separate, explicit
"delete anyway" action for a user who intentionally wants to start fresh.

Every path that clears encrypted preferences must call `EnsureNoUnsharedGiftFundsUseCase`. A guard
that exists on one destructive path and not another is the same bug with extra steps.

**Opening the share sheet is not a hand-off.** The chooser reports the target the user actually
picked, through an `IntentSender`, and only that marks a card shared — a cancelled sheet leaves the
card protected. The receiver is manifest-declared because the sheet outlives the screen that opened
it and, on a cold chooser, sometimes the process.

The residual gap is accepted deliberately: picking a target is not proof of sending. The clipboard
makes the same trade, and it is the right one, because the alternative is a guard nothing can clear —
a wallet nobody can reset. The list therefore also offers a copy action, which is the one hand-off
route that always reports its own outcome, and the reset dialog routes to the gift list rather than
simply refusing.

### 6.5 A claim in flight is neither claimed nor unclaimed

Between broadcast and finality — the SDK's ten confirmations, so about 12.5 minutes and longer if
the claim is slow to mine — a receipt holds this wallet's own claim txids and is not yet settled.
Answering that window as "not collected" puts a Claim button over money already on its way, and the
rescan behind it can only rediscover the transaction already recorded on the device. Offline it
fails with a network error, over a gift that has already moved.

So `GiftClaimPreview` reports it as its own thing, `inFlightClaimTxids`, and the screen has its own
stage for it. `ConfirmGiftClaimUseCase.observeClaimConfirmations` counts it, read-only and from this
wallet's own transactions — nothing there opens the card's wallet or touches its bearer seed.

The count is absent until the claim mines, and that is not a gap that can be closed: the claim is
built and broadcast by the *card's* isolated wallet, so this wallet learns of it only when it mines
and is scanned. A claim still in the mempool and one that will never mine are indistinguishable from
here. That is why the stage keeps an explicit re-check rather than waiting on the count alone — a
screen whose only exit is an event that may never arrive is a trap.

## 7. Risks

### 7.1 Two synchronizers — resolved, verified on device 2026-08-21

`IsolatedSynchronizerSpikeTest` ran two synchronizers concurrently on a OnePlus CPH2747 (Android 16)
against mainnet, on two throwaway random seeds:

- Both came up live and held readable balances; neither went `STOPPED`.
- **No SQLite contention** — zero `SQLITE_BUSY` or "database is locked" in an 8,781-line logcat.
- **Files are segregated**: per-alias `data.sqlite3` and per-alias compactblock directories, disjoint.
- **A duplicate alias is rejected** with `IllegalStateException: Another synchronizer with
  SynchronizerKey(...) is currently active`. A per-card deterministic alias is the intended
  mechanism, not a workaround.
- **A reused alias whose database belongs to a different seed fails closed** with
  `InitializeException.SeedNotRelevant`, which is what lets §3.5 skip an explicit identity check.

**Still untested: Sapling parameter contention.** The parameters are fetched lazily on first *spend*
and the spike only scans. `SaplingParamTool.checkFilesMutex` is process-wide, so the risk is low, but
the first claim that actually builds a transaction is where this gets exercised for real.

The spike is not in CI — it needs the network and takes two minutes. It names its network outright
rather than reading resources, because `zcash_is_testnet` is generated by the **app** module from its
product flavor, so a library module's androidTest always resolves to the `sdk-ext-lib` default.

### 7.2 The record store is custody-critical

`gift_cards_v1` in encrypted preferences is the only recovery path for an unshared card. The
discipline around it:

- **Write before funding. Never widen a decode. Never catch `StoreCorruptedException` into a
  default.** Treating corrupt as absent would let the next write replace a list of funded cards with
  a list of one.
- **The store decodes strictly**, unlike every other user of `EncryptedJsonStore`. A tolerant decode
  drops an unrecognised field and writes the record back without it — an older build silently
  discarding part of the only copy of a card's recovery data. Failing the read is safe: a mutation
  reads before it writes, so a refused read refuses the write too.
- **The key must not be versioned.** Bumping it reads back an absent key, which is
  indistinguishable from "no cards": every stored seed orphaned behind a name nothing looks up any
  more. Additive fields with defaults are the supported change; anything else needs a migration that
  reads the old key and writes the new one.
- **It is excluded from Android Auto Backup by construction** — the backup configs are allowlists
  naming only `address_book`, so `domain="sharedpref"` is not backed up. Never add a `sharedpref`
  include.

### 7.3 Bearer-secret exposure

The link is cash: no binding to a recipient, no revocation. Any clipboard manager, screenshot,
message backup or crawler that touches it can drain it. Fragment placement (§2) removes the
network-leak class; the rest is UX.

`GiftLinkPayload` and `StoredGiftCard` both override `toString()` to redact, and that must be kept if
fields are added — a generated `toString` would drop the mnemonic into any log line, crash report or
exception message that interpolates the object. Codec failures are logged as the throwable and never
as a message interpolating it, because a codec failure embeds the payload it choked on. Only card ids
are loggable.

## 11. Known limitations

### 11.1 Claiming is slow, and the fix means forking the SDK

Measured on a OnePlus CPH2747 against testnet, 2026-08-21: a card created minutes earlier took
**2m22s** to claim, scanning 54,160 blocks at ~380 blocks/second. It should have scanned about 7.

**Reaching the tip is not the problem and cannot be avoided.** Spending a shielded note needs a
Merkle path against a recent anchor, so every commitment between the note and that anchor has to be
processed — and scanning forward is also the only way to learn whether somebody else already claimed
the card. That half is cryptography, not our code.

**Where the scan starts is the problem.** To begin at height H the wallet needs the commitment tree
frontier at H, and `Synchronizer.new` gets that from `CheckpointTool.loadNearest`, which reads
checkpoint files **bundled in the APK** and returns the newest at or below the requested birthday.
The birthday is used only to pick a checkpoint, and up near the tip there is no checkpoint to pick —
two testnet cards born 134 blocks apart both produced `accounts.birthday_height = 4235171`,
identical. Mainnet is better but not fixed: ~15,300 blocks on every claim, growing until the next SDK
release resets it.

| Card age | Blocks | Rough time |
|---|---|---|
| Fresh | ~15,300 (the checkpoint gap) | under a minute |
| 1 month | ~37,000 | ~2 min |
| 3 months | ~104,000 | ~5 min, and crosses §3.6's consent gate |
| 1 year | ~420,000 | ~18 min |

The gap punishes **fresh** cards hardest, which is the case the design assumed was free.

Two consequences are already in the code. A claim's scan is deliberately unbounded — only reaching
the server is timed out — and the screen offers a stop rather than a deadline, because there is no
duration at which giving up is automatically right. And a collection check costs exactly the same
multi-minute scan, which is why it runs one card at a time on request and never as a background
sweep across the list.
