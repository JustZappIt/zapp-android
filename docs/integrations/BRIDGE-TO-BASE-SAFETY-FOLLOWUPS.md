# Bridge-to-Base (Add funds) | safety follow-ups

Status of the follow-ups raised on `feat/offramp-presentation-redesign`. The bridge (`BridgeToBaseVM`
→ `NearBridgeOfframpFunding` → NEAR 1-Click → USDC on Base) is money-movement, so the affordability and
gating rules below are load-bearing.

## Resolved in this PR

1. **Add funds is disabled until a valid, current estimate exists.**
   A failed or in-flight quote leaves `requiredZec == null`. The CTA now gates on
   `evaluateBridgeGate(...)`, which is not submittable when there is no estimate, and the screen shows a
   "getting a live quote" / "couldn't fetch a quote" hint instead of enabling a blind bridge. The
   estimate lifecycle is tracked by `BridgeToBaseVM.EstimateStatus`.

2. **The Zcash network fee is reserved in the affordability check.**
   The bridge debits the deposit `amountIn` **plus** the shielded send's ZIP-317 fee. One predicate,
   `isSpendableZecSufficientForBridge`, reserves `ZEC_BRIDGE_FEE_RESERVE` (the ZIP-317 conventional
   single-recipient fee, 10,000 zat). It is used by **both** the presentation gate (`BridgeToBaseVM`)
   and the send-time guard (`NearBridgeOfframpFunding.openBridge`), so a balance that covers only
   `amountIn` no longer slips through to a generic proposal-build failure. The SDK proposal remains the
   exact source of truth at send time and still fails closed for the rare multi-note case.

3. **Destination/Base gas is not user-funded (confirmed).**
   1-Click's solver delivers USDC to the smart account (`recipient = destinationAddress`,
   `recipientType = DESTINATION_CHAIN`); the user signs only the ZEC deposit, no Base tx. The route cost
   is embedded in the quoted `amountIn`. The subsequent PAY is an ERC-4337 UserOp sponsored by the
   Pimlico paymaster (`ProviderModule` `BundlerClient.sponsorshipPolicyId`). Documented in
   `BridgeToBaseVM`'s header.

4. **Fee / slippage are surfaced, derived from the quote like the swap detail screen.**
   1-Click's `/quote` response carries no *separate* itemized route fee (`QuoteDetails` has only
   `amountIn`/`amountOut` + USD), so, exactly as `SwapDetailVM.createTotalFeesState` does, the ledger
   shows the quote's affiliate/route fee (`SwapQuote.affiliateFeeZatoshi`, "total fees") in ZEC plus the
   max-slippage tolerance (1%). `OfframpTopUpEstimate` carries `affiliateFeeZec` / `slippagePercent` from
   the same quote. The ZEC network (miner) fee is reserved in the affordability gate (see #2), matching
   how the swap flow keeps the miner fee out of this row.

5. **Focused tests**: `BridgeAffordabilityTest` covers quote-unavailable, quote-refresh transitions,
   balance-change, the insufficient-by-fee boundary, the fee-reserving estimate, and the guard predicate.

## Not resolvable in code | must stay gated (#6)

**Mainnet end-to-end validation before removing the production gate.** This moves real ZEC and USDC and
cannot be validated from source. The bridge is off in shipped builds because `P2P_NETWORK` defaults to
Sepolia/blank (`ProviderModule`), which binds `NoRouteOfframpTopUp` / `OfframpTopUpPreview { _, _ -> null }`;
`OFFRAMP_USE_DEV_KEY` additionally separates the dev signing key from the seed-derived one.

**Do not flip `P2P_NETWORK=mainnet` in a shipped build until all of the following pass on-device:**

- [ ] A real mainnet top-up: enter an amount, confirm the estimate + fee reserve match the send, authorize
      the ZEC deposit, and observe `SUCCESS` with USDC landing on the smart account on Base.
- [ ] Insufficient-by-fee: a balance between `amountIn` and `amountIn + fee` is blocked at the CTA with the
      insufficient message (never reaches a send).
- [ ] Resume: kill the app mid-bridge; on relaunch it re-polls the same deposit address (no second bridge,
      no double-send).
- [ ] Terminal failure: a refunded/expired bridge surfaces the terminal message and clears the checkpoint.
- [ ] The follow-on PAY completes against a live merchant with paymaster sponsorship.
- [ ] Pre-tag hygiene from `AGENTS.md`: no `*_LOG_LEVEL=debug` / `*_NETWORK=<flavor>` in `gradle.properties`.

Keeping the gate closed until then is intentional; removing it now would ship unvalidated money movement.
