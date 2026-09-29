# Android reverse conversion

The testnet **Convert** screen supports both ZEC → USD and USD → ZEC. Both use the colored
amount fields with balances on the right, stacked ZEC and Private USD rows, and a
swap icon that reverses their order. Only the sending (top) field is editable. Icons
sit on the input line. Tapping its Available balance fills the usable maximum. The
bottom action bar is shared with Send. The reverse
balance counts only spendable notes for the pinned deployment token; an unknown
balance is distinct from zero. Decimal precision is validated without an input hint.
Fresh inputs and direction changes start blank; persisted preview quotes never fill
an input. Only typing or tapping Available supplies an amount. Quote fetching uses
the bottom button's spinner and “Getting quote…” label. Fee summaries fit one line.
Conversion back navigation selects the Pay tab and removes intermediate wallet screens.

The currency selected under You controls fiat balances, input, estimates and fees.
Private-USD input is converted to integer token units with decimal arithmetic; an
unavailable or mismatched local rate cannot reinterpret that input as USD. Max rounds
down before conversion. Accepted quotes retain their original USDC units and deployment.
The Private USD overview places local fiat beside a smaller USD total; tapping swaps
their sizes. It omits the duplicate Available row and explains Send and Withdraw in
the info sheet, with icons on both actions.
Both directions reuse `PrivateUsdProgressView` and `ZappStepList`, including the
animated current step, completed steps, background-work note and success header.
Reverse progress includes the explicit second approval and Zcash sweep confirmations;
refund and cancellation outcomes have separate status displays.
Progress screens omit the delay-warning card and its retry button. Reconciliation
continues automatically in the background.

## Deployment and storage

`ReverseSwapTestnet` pins the hosted maker and relayer, Sepolia chain 11155111,
contract `0xbd9a37f47a988aefc4d80395727f41feb698e225`, and token
`0x5764d0044bef5aa839e0ddafe2073421101b9ed8`. Maker info and quotes must match.
Reverse records persist the entire public deployment. `AtomicSwapSessions` selects
forward deployments from each saved quote; legacy pending swaps keep their original
contract and local service URLs. Unknown deployments fail closed.

The encrypted reverse store records the consumed shared derivation index, quote,
authentication identity, refund-note commitment, acceptance, joint-account birthday
and imported account, signed funding transaction, Ready/refund/payout authorizations,
and signed receive transaction. Authentication keys are rederived from the wallet
and index. No service signing material is embedded.

## Authorizations and recovery

1. Typing previews a verified, persisted quote with a fresh consumed index. Review
   accepts it and imports the joint account before funding. Preview drafts cannot
   accept or fund themselves after restart.
2. Convert requires foreground wallet authorization. Railgun builds and proves an
   atomic Relay Adapt call: unshield enough to leave the exact escrow amount,
   approve exactly that amount, and call `openReverse`. A revert rolls back the
   entire transaction. Signed raw bytes and their hash are saved before broadcast.
3. The worker reads confirmed escrow state and independently syncs the exact joint
   Zcash account. Partial or unconfirmed deposits cannot enable Ready. Maker status
   and transaction IDs are not settlement evidence. A proposal determines the sweep
   fee before Ready can be authorized.
4. The second foreground authorization signs Ready. The worker may retry those
   saved bytes while valid, but never creates Ready by itself. A verified on-chain
   claim supplies the share checked by the native receive signer. The local PCZT
   finalization creates raw transaction bytes without broadcasting; those bytes are
   persisted first and retried unchanged. Only proven transaction expiry permits
   rebuilding the sweep. Mined sweeps wait for the SDK's confirmed state without
   rebroadcasting. The screen shows their confirmation count. Check progress runs
   a serialized reconciliation with visible loading feedback.

Funding reconciliation also checks `eth_getTransactionByHash` for the exact saved
hash before retrying a transaction without a receipt. If submission returns an RPC
error, a matching transaction lookup establishes that the node already has the same
bytes. Error-message text is never used to decide whether funding succeeded.

Cancellation intent persists. Refund recovery observes contract deadlines and
alternating lock turns. The secret is sent only after a fresh confirmed refund lock
has more than 300 seconds remaining. Private payout and rescue requests persist and
retry. Reverse storage roles are checked explicitly: stored maker = USDC user;
stored user = ZEC maker.

The refund screen offers “Recover refund” only after verifying a paid-out refund
and a returned vault balance greater than the verified relayer fee. Eligibility is
checked while the screen is observed. Empty or uneconomic vaults produce no recovery
submission; duplicate actions reuse the persisted recovery request. Refunded and
cancelled screens omit the former conversion estimate.

The existing foreground WorkManager worker, restart receiver, notifications and
activity resume path handle both directions. Forward escrow-before-ZEC-deposit
ordering is unchanged. ZEC input includes the deposit fee; the client fits a verified
USD-denominated quote below the entered total, and persists that authorized total.
The actual Zcash proposal is checked against it before transaction creation. Preview
drafts do not block the opposite direction; accepting a swap reserves the shared
wallet under one lock.

## Companion dependency

`../zecSwap` on `feature/android` needs companion commit `ecad30c` for the `signRefundRescue` JNI and
Kotlin wrapper changes. This exposes the existing contract Rescue digest; it does
not change the contract or API protocol. All three packaged Android ABIs were
rebuilt with that export. Do not publish the Android change without its companion.

## Validation

- The selected-currency and overview update passed the testnet APK build, Detekt and six focused
  currency/quote tests. It was installed on the physical CPH2747 without clearing wallet data.
  Device inspection confirmed INR balances, blank conversion inputs and the bottom
  “Getting quote…” spinner. Remaining visual/navigation checks were stopped while the user
  interacted with the phone; no payment was submitted during these UI checks.
- Testnet APK build and installation on physical CPH2747 succeeded; mainnet Kotlin compilation also passed.
- A full core run passed 612 offramp JVM tests, including forward tests and 21 reverse API/driver
  tests for roles, quotes, confirmations, cancellation, deadlines and recovery.
- 20 selected Android UI/model tests passed before the final input-layout adjustment, including deployment binding and
  reverse spendable-balance handling.
- The final input-layout build and Detekt passed, as did the focused 19-test reverse driver suite.
- The progress refresh update also passed the testnet APK build, Detekt and all 19
  focused driver tests, including resuming a mined sweep without rebuilding or rebroadcasting it.
- The shared progress-screen update passed the APK build, Detekt and three focused
  UI-state tests covering the approval step, sweep confirmations and refund/cancellation displays.
- A focused RPC test passed for missing/known transactions and rejection of a mismatched hash.
- The refund retry test also covers empty and fee-sized vaults, eligibility checks,
  repeated recovery taps and reuse of an already signed recovery request.
- Formatting of changed Kotlin files passed.
- Railgun integer fee tests and browser Sepolia initialization/sync passed.
- Nine JNI vector tests passed; Android native builds succeeded for all three ABIs.
- The separate EVM suite has an existing retry-delay test failure in
  `BundlerClientTest` / `RpcHttpClient`; the new transaction lookup was tested separately.
- `checkProperties` is blocked by local developer property overrides. The committed
  credential defaults and network filter remain blank and unchanged.

On the physical CPH2747, the user authorized a 1 USDC reverse swap. It advanced
through escrow and settlement to a persisted ZEC sweep, displaying a 1.002506 USDC
total debit and 0.00188 ZEC net receive. After updating and restarting the app during
sweep confirmation, the SDK confirmed the saved transaction and the screen showed
“ZEC received in your shielded wallet.” This device run exercises the
Railgun funding path absent from the public-token server smoke tests. Sepolia uses the
wallet's existing public test gas account to broadcast Relay Adapt transactions;
its ETH fee is separate from the private USDC debit. A production broadcaster
integration and mainnet deployment remain outside this testnet implementation.

A subsequent 0.5 USDC conversion reached the confirmed refund-payout state on the
same phone. Repeated taps on the previously unconditional recovery button failed
the balance check before signing or submitting. The conditional button fixes that UI;
the refund's eventual Railgun screening/spendability was not independently checked.
