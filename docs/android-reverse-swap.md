# Private USD to ZEC conversions

A reverse conversion pays test USDC from the private (Railgun) balance into a zecSwap escrow on
Sepolia. The maker deposits ZEC into a Zcash account both sides hold a share of, and the app sweeps
that account into the wallet once the maker claims the escrow. Testnet builds only:
`AtomicSwapDeployments.current` is null on mainnet, so neither direction is offered there.

The engine is `ReverseSwapDriver` in `offramp-lib` (pure JVM, host-tested). The app side is
`ReverseSwapRepository`, which runs it through the same `SwapLoop`, worker and notifications as
forward swaps.

## Deployment and storage

- A reverse record keeps its whole public `SwapDeployment`: service URLs, RPC URL, chain id,
  contract, token, Railgun proxy, maker, relayer and the most a relayer may charge. Its driver
  comes from that deployment, and the verifier rejects a record from any other one. A forward
  record keeps only its quote. `AtomicSwapSessions.deploymentFor` matches the quote's chain,
  contract and token against the known deployments and fails closed on anything else.
- Both stores are encrypted preferences decoded strictly (an unknown key is an error):
  `atomicswap_state_v2` for forward swaps, `reverse_swap_v1` for reverse ones. Typed fields write
  the JSON earlier builds wrote. Addresses are lowercase hex, amounts are decimal strings of base
  units, and swap ids are lowercase 32-byte hex. `SwapRecordStorageTest` and the store tests pin
  kept records byte for byte.
- Swap keys are derived again from the wallet seed and the swap index. `SwapIndices` hands out
  indices for both directions and skips any that a known contract shows in use. No service signing
  material is stored.

## Authorizations

1. **Preview.** `quote` checks the maker's info against the deployment, takes a fresh index, and
   verifies the quote: deployment, our address and refund-note commitment, and deadlines far enough
   off. It proves our acceptance and saves the record as `QUOTED`. A preview is never accepted or
   funded on its own, and it doesn't block a forward swap.
2. **Review.** The review shows the preview's own cost; nothing is accepted by looking at it.
3. **Convert**, the first foreground authorization, behind the app lock. Going ahead moves the record
   to `ACCEPTING`; the quote is accepted at the maker, which must return the expected swap id, and
   the joint account is imported from the quote-time birthday. Then Railgun builds one Relay Adapt
   transaction that unshields, approves exactly the escrow, and calls `openReverse`. Its cost must
   equal the reviewed cost: a different one is kept and shown, and nothing is paid until the user
   converts again or cancels. The signed bytes are checked against their hash and kept before the
   first broadcast. After that they're only sent again unchanged, and only while the node doesn't
   know the hash.
4. **Ready**, the second foreground authorization. It's allowed only when the whole deposit is
   spendable in the joint account, the escrow is open without a refund lock, and the signature's
   two-minute lifetime ends before the ready deadline. The background sends a kept `ready` again
   while it holds but never signs one.
5. **Receive.** Once the escrow is claimed, the revealed share lets the native signer sign the sweep
   home. The sweep is kept before it's first sent, and after that it's only sent again unchanged.
   It's rebuilt only once proven expired, meaning the wallet has scanned past its expiry height
   without finding it. The deployment's Zcash confirmations complete the conversion, as they do a
   forward refund's sweep, and the joint account is forgotten.

## Refunds and cancellation

- Cancelling persists `cancelRequested`. The screen offers it until the sweep home is built. With
  nothing funded, the conversion ends at once. A funding transaction the node never saw is not sent
  again. A funding that reverted, or no escrow 10 minutes after the funding deadline, ends it as
  cancelled. Once there's an escrow, cancelling starts the refund.
- A refund is due once cancelled, once the escrow shows refunded, when it's open past the ready
  deadline, or when it's ready past `refundAfter`. The app takes a refund lock only on its own turn:
  no claim lock held, and not the maker's turn after a lapsed lock of ours.
- The claim secret is revealed only under a refund lock read fresh from the chain, with more than
  five minutes left, through a head less than three minutes behind the device clock. A relayer
  response or an earlier observation is never enough.
- A payout is signed only when it's sent, and it's kept first. The relayer's terms must name this
  deployment and a relayer other than the maker. The fee must be within the deployment's maximum
  and below the amount.
- Rescue reshields a refund that Railgun returned to its vault. It's offered only for a refunded
  swap whose escrow shows paid out and whose vault holds more than the relayer's fee. The swap
  stays finished, so a rescue never holds up another conversion.

## Invariants

- Only one conversion goes ahead at a time, either way. Accepting holds `AtomicSwapSessions.acceptanceLock`.
- Everything the driver acts on is checked against the deployment, the swap's own keys, and the
  escrow read at a block that has its confirmations. The roles are the forward swap's turned round:
  our key is the contract's maker, and the ZEC maker is its user.
- Maker status, relayer responses and error text are never evidence of funding or settlement.
- Every transaction is kept before it's first sent and is only sent again from those bytes. A kept
  Ready or refund-lock authorization is reused while it holds.

## Background work

`SwapLoop` advances the conversion under way in each direction and retries failed steps every
15 seconds. It shows why a step failed as a typed `AtomicSwapProblem`. It asks for the user after
five minutes of failures they could help with, or when a reverse swap waits for Ready. The
WorkManager worker keeps the process alive for either direction. It runs as a foreground service
when started from the app. Notifications and the app's resume path cover both directions. Only
forward swaps set wake alarms, around `t0` and before `t1`. `AtomicSwapWakeReceiver` resumes both
loops when woken, but its last-call notification is for forward swaps only.

## Dependency

The app needs `../zecSwap` at or after `9f2da32` ("feat(android): derive payout notes from a separate
railgun seed"), the commit that adds `RailgunSeed`, which payout notes and the Railgun address are
derived from. It carries the packaged Android native libraries, and the rescue signature
(`ReverseAtomicSwap.signRefundRescue`). The `.zapp-deps` pin and the workflow's `ZECSWAP_REF` must
name it too.
