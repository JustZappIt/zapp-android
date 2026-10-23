# Shielded USD to ZEC conversions

A reverse conversion pays dollars from the Shielded USD (Railgun) balance into a zecSwap escrow:
USDC on Ethereum in mainnet builds, ZecSwap Test USD on Sepolia in testnet builds. The maker
deposits ZEC into a Zcash account both sides hold a share of, and the app sweeps that account into
the wallet once the maker claims the escrow.

The engine is `ReverseSwapDriver` in `offramp-lib` (pure JVM, host-tested). The app side is
`ReverseSwapRepository`, which runs it through the same `SwapLoop`, worker and notifications as
forward swaps.

## The windows contract

zecSwap's contract has no claim or refund locks. At every moment at most one side may reveal its
share, by the clock and the escrow's stage alone, and a reveal mined outside its window reverts with
the share in public calldata. For a reverse escrow:

- the maker may claim once the escrow is ready, until `t1` (the quote's `refundAfter`);
- the user may refund while the escrow isn't ready, and from `t1` for good;
- a ready is taken only before `t0` (the quote's `readyDeadline`);
- `openReverse` refuses a `t0` under `MIN_WINDOW` from when it lands, or a `t1` under it from `t0`.

The judgements are pure functions in `SwapWindows.kt`, ported from zecSwap's `evm/judge.rs`, and
the third audit's 97 cases replay against them (`WindowsSpecTest`). Each is made on one consistent
read: a single `eth_call` to Multicall3 for the escrow, the newest block's time and its number
(`AtomicSwapChain.swapNow`), as two reads from a load-balanced node can answer a block apart. A
window opens by the block's time; how much of it is left goes by the later of the block's time and
the device clock. A head more than three minutes behind the device clock is refused.

## Deployment and storage

- A deployment pins its contract's windows (`SwapDeployment.windows`: 1 hour and 24 hours on
  mainnet, 30 minutes and 1 hour on testnet). Before any swap is read, the contract must answer
  `MIN_WINDOW()` and `LATE_WINDOW()` as pinned and revert `LOCK_DURATION()`, which only the lock
  contract answers: the lock contract's six-word swap record would otherwise read silently as the
  windows contract's four. The maker's `/v1/info` and the relayer's `/v1/terms` must name no other
  `contractKind` or windows.
- A reverse record keeps its whole public `SwapDeployment`: service URLs, RPC URL, chain id,
  contract, token, Railgun proxy, maker, relayer and windows. Its driver comes from that
  deployment, and the verifier rejects a record from any other one. A forward record keeps only its
  quote. `AtomicSwapSessions.deploymentFor` matches the quote's chain, contract and token against
  the known deployments and fails closed on anything else.
- The lock contracts users swapped on before (`0x4708…` on mainnet, `0xD75E…` on testnet) stay
  known as retired deployments: they have no windows, their records read as before, and nothing
  drives them. A retired conversion that never paid ends; one with an escrow is refused, for
  support to settle. They also stay in the index check, so a restored wallet never reuses a swap
  index whose share is public there.
- Both stores are encrypted preferences decoded strictly (an unknown key is an error):
  `atomicswap_state_v3` for forward swaps, `reverse_swap_v3` for reverse ones. Typed fields write
  the JSON earlier builds wrote, and new fields are optional, with defaults that aren't written.
  Addresses are lowercase hex, amounts are decimal strings of base units, and swap ids are lowercase
  32-byte hex. `SwapRecordStorageTest` and the store tests pin kept records byte for byte.
- Swap keys are derived again from the wallet seed and the swap index. `SwapIndices` hands out
  indices for both directions and skips any that a known contract shows in use. No service signing
  material is stored.

## Authorizations

1. **Preview.** `quote` checks the maker's info against the deployment, takes a fresh index, and
   verifies the quote: deployment, our address and refund-note commitment, and deadlines far enough
   off, leaving `MIN_WINDOW` from the funding deadline to `t0` and from `t0` to `t1`. It proves our
   acceptance and saves the record as `QUOTED`. A preview is never accepted or funded on its own,
   and it doesn't block a forward swap.
2. **Review.** The review shows the preview's own cost; nothing is accepted by looking at it.
3. **Convert**, the first foreground authorization, behind the app lock. Going ahead moves the record
   to `ACCEPTING`; the quote is accepted at the maker, which must return the expected swap id, and
   the joint account is imported from the quote-time birthday. Then Railgun builds one Relay Adapt
   transaction that unshields, approves exactly the escrow, and calls `openReverse`. Its cost must
   equal the reviewed cost: a different one is kept and shown, and nothing is paid until the user
   converts again or cancels. The exact proved calldata is kept before sending it to the relayer's
   `POST /v1/reverse/fund`. The relayer simulates, estimates gas and signs the Ethereum envelope.
   Android saves the returned hash and waits for the confirmed escrow on chain. A response timeout
   keeps the same proof: the driver checks the escrow first and only resubmits unchanged calldata
   while the node doesn't know its hash and the funding deadline has not passed.
4. **Ready**, the second foreground authorization. It's allowed only when the whole deposit is
   spendable in the joint account, the escrow is confirmed, and on one consistent read the escrow is
   still open with the signature's two-minute life ending before `t0`; never after a refund was
   asked for or posted. The signature's deadline is kept with the record before the signature
   leaves: a refund waits until the chain is past it. The background sends a kept ready again while
   the escrow is open and its deadline holds, but never signs one.
5. **Receive.** Once the escrow is claimed, the revealed share lets the native signer sign the sweep
   home, to a fresh Orchard-only address of the wallet each time: the maker holds the joint
   account's viewing key, and with it could read where a sweep goes. The sweep is kept before it's
   first sent, and after that it's only sent again unchanged. It's rebuilt only once proven expired,
   meaning the wallet has scanned past its expiry height without finding it. The deployment's Zcash
   confirmations complete the conversion, as they do a forward refund's sweep, and the joint account
   is forgotten.

## Sponsored funding

New funding requires `GET /v1/terms` to advertise `reverseFunding` for the pinned chain, escrow,
relayer, maker, token and Railgun's own Relay Adapt on that chain (`RailgunContracts`). Android
checks these before proving, and refuses terms without a `fee`. The proof uses public-wallet mode,
so `requireSuccess` is bound to true, with no Railgun broadcaster fee note. The cross-contract calls
approve the exact escrow amount, open the signed reverse quote, and transfer the advertised `fee`
in the escrow token to the relayer's address; leftover ERC20 dust is shielded back into the user's
balance. The unshield covers escrow plus fee after Railgun's unshield fee, the review shows the fee
as the broadcast fee, and Max leaves room for it. The relayer is paid only if the escrow opens,
which its simulation checks before it spends gas.

Only `{swapId, chainId, to, data, value:"0"}` leaves the phone. Spending keys stay in the Railgun
wallet. The relayer supplies the ETH and enforces its advertised gas and calldata limits. Missing
sponsorship blocks the conversion.

An empty funding response means the service found a matching escrow, not that Android has
confirmed funding. The driver independently verifies the escrow's contents and confirmations
before continuing. A service response alone never authorizes Ready.

## Refunds and cancellation

- Cancelling persists `cancelRequested`. The screen offers it until the sweep home is built. With
  nothing funded, the conversion ends at once. A funding transaction the node never saw is not sent
  again. A funding that reverted, or no escrow 10 minutes after the funding deadline, ends it as
  cancelled. Once there's an escrow, cancelling starts the refund.
- A refund is due once cancelled, once the escrow shows refunded, when it's open from `t0`, or when
  it's ready from `t1`.
- The user's share is revealed only where the refund's window lets it, judged on a fresh consistent
  read through a head less than three minutes behind the device clock: an open escrow once the
  chain is past the deadline of the ready we signed, or at `t0`; a ready one from `t1`. A refund
  that landed behind a ready would revert with the share public, and the maker could take both
  sides, so an open escrow is never refunded while a ready we signed can still land.
- It's judged again on another fresh read right before it goes, once the share is derived and the
  payout signed. `refundPostedAt` is kept with the record before the refund goes, and no ready is
  ever signed after it. A relayer response or an earlier observation is never enough.
- The relayer answers `503` with `Retry-After` while a ready it sent for the escrow may still land.
  A wait of up to 30 seconds is waited out and the same request posted again, for up to 90 seconds
  in all; a longer one is left to the next step, which judges the refund again first.
- A payout is signed only when it's sent, and it's kept first. The relayer's terms must name this
  deployment, its windows, and a relayer other than the maker. The fee must be below the amount.
- Rescue reshields a refund that Railgun returned to its vault. It's offered only for a refunded
  swap whose escrow shows paid out and whose vault holds more than the relayer's fee. The swap
  stays finished, so a rescue never holds up another conversion.

## Invariants

- Only one conversion goes ahead at a time, either way. Accepting holds `AtomicSwapSessions.acceptanceLock`.
- Everything the driver acts on is checked against the deployment, the swap's own keys, and the
  escrow read at a block that has its confirmations; a ready and a refund are judged on one
  consistent read of the newest block as well. The roles are the forward swap's turned round: our
  key is the contract's maker, and the ZEC maker is its user.
- Nothing that carries a share is ever simulated (`eth_call` or `eth_estimateGas`): the node would
  see it before the relayer sends it.
- Maker status, relayer responses and error text are never evidence of funding or settlement.
- Every transaction is kept before it's first sent and is only sent again from those bytes. A kept
  ready is reused while it holds.

## Background work

`SwapLoop` advances the conversion under way in each direction and retries failed steps every
15 seconds. It shows why a step failed as a typed `AtomicSwapProblem`. It asks for the user after
five minutes of failures they could help with, or when a reverse swap waits for Ready. The
WorkManager worker keeps the process alive for either direction. It runs as a foreground service
when started from the app. Notifications and the app's resume path cover both directions. Only
forward swaps set wake alarms: around `t0`, before `t1`, and at the end of the maker's late window
while a swap unwinds. `AtomicSwapWakeReceiver` resumes both loops when woken, but its last-call
notification is for forward swaps only.

A forward swap hands its claim to the relayer once its deposit is out (`POST /v1/claim/hold`, kept
as posted before it goes), and the relayer sends it the moment the claim's window opens: the user
may leave the app then. The relayer holds the user's share until then, which the owner accepts
(zecSwap's `docs/deployment.md`, Held claims). While the app runs, it claims itself only if nothing
landed a minute after the window opened. A relayer that refuses the hand-over (`400`) or lacks the
route (`404`) leaves the claim to the app, as before. A refund after a hand-over still needs the app
to sweep the deposit home.

## Dependency

The app needs `../zecSwap` at `88f8dfe`, whose Android wrapper and native libraries are the earlier
pin's but for `ReverseAtomicSwap.refundSecret`, which the app never called. Everything the app signs
is byte for byte the same on the windows contract: `ReverseOpen`, `Ready`, `Payout`, `RefundPayout`
and `Rescue`, under the domain `("ZecSwap", "1")`. The `.zapp-deps` pin and the workflow's
`ZECSWAP_REF` name it too.
