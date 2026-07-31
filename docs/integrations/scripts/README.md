# Offramp integration scripts

These scripts produce fixture data that's committed back into Kotlin test files.
They depend on `viem`/`@noble/*` from `p2pdotme-sdk`, so they run via:

```sh
# from inside a p2pdotme-sdk clone with `bun install` already run:
bun /path/to/zodl-android/docs/integrations/scripts/<script>.ts
```

Neither script touches the SDK source tree: they're read by bun, imports resolve
against the SDK's `node_modules`, and output goes to stdout. Paste output back
into the matching `*Test.kt` constants.

## `generate-ecies-fixture.ts`

Produces frozen ECIES test vectors that prove the Kotlin port in
`evm-lib/.../crypto/Ecies.kt` is wire-compatible with `@p2pdotme/sdk`.

The script imports from the SDK's source, so it must be run from inside a clone
of `p2pdotme-sdk` (private to us: the @noble/* deps live in that tree).

```sh
# from inside p2pdotme-sdk/
cp /path/to/zodl-android/docs/integrations/scripts/generate-ecies-fixture.ts scripts/
bun install   # if not already
bun run scripts/generate-ecies-fixture.ts
```

Paste the four `ciphertextHex` strings back into `evm-lib`'s
`EciesTest.kt` (the `SDK_FIXTURES` companion list).

Only re-run when the SDK's ECIES wire format changes. Fixtures are
committed and travel with the test.

## `generate-revert-selectors.ts`

Regenerates `offramp-lib/.../orchestrator/KnownContractErrors.kt`, the wholesale
selector → canonical error-code table, by regex-parsing
`p2pdotme-sdk/src/contracts/errors.ts`. No node_modules required; self-contained.

```sh
bun /path/to/zodl-android/docs/integrations/scripts/generate-revert-selectors.ts \
  /path/to/p2pdotme-sdk \
  > /path/to/zodl-android/offramp-lib/src/jvmMain/kotlin/xyz/justzappit/offramp/orchestrator/KnownContractErrors.kt
```

The path arg defaults to `../p2pdotme-sdk` if omitted. That SDK clone is not public. The SDK
is the single source of truth: `contracts/errors.ts` provides the error *code*
(selector → `SCREAMING_SNAKE`) and `contracts/error-messages.ts` the human *message*;
the generator joins them and emits one `Map<Selector4, Entry(name, message)>`. The
older `user-app-client/src/lib/errors.ts` conflated the two, which produced
selector-name collisions. Do not point this back at it. Selectors are emitted in
stable (alphabetical) order so a re-run with no source change produces zero diff.
The generator fails fast if any selector has no message. Keep both SDK files in
lock-step. Re-run whenever you sync to a newer SDK release. `KnownRevertsTest`
asserts the table stays ≥120 entries, every code is distinct, and every curated
selector still exists in the generated table.

## `thirdweb-refund-smoketest.ts`

Live end-to-end test of the thirdweb bundler + paymaster against Base Sepolia. Sends a
`autoCancelExpiredOrders([id])` UserOp through the same pipeline the Android app uses, with
the same smart account (derived from the wallet seed via BIP-39 + `m/44'/60'/0'/0/0`).

Use to:
- Confirm thirdweb wiring works end-to-end on Sepolia, orthogonal to any cancelOrder-specific
  authorization 500s.
- Actually refund an expired PAY order so the Android app's poll observes `CANCELLED`.

```sh
# from inside a p2pdotme-sdk clone with `bun install` already run:
OFFRAMP_OWNER_MNEMONIC="word1 word2 ... word24" \
OFFRAMP_ORDER_ID=216 \
THIRDWEB_CLIENT_ID=<YOUR_THIRDWEB_CLIENT_ID> \
bun /path/to/zodl-android/docs/integrations/scripts/thirdweb-refund-smoketest.ts
```

The script prints every bundler request/response and the final on-chain state. If thirdweb is
healthy, the order flips to `status=4` (CANCELLED). If thirdweb returns 500 on a clean
`autoCancelExpiredOrders` op, the wiring problem is general (not specific to the prior
`cancelOrder` revert).

## `generate-calldata-fixtures.ts`

Emits viem-encoded calldata for `approve`, `placeOrder`, `setSellOrderUpi`,
`getOrdersById`, `getAssignableMerchantsFromCircle`. The ABI fragments are
inlined into the script (kept in sync manually with `p2pdotme-sdk/src/contracts/abis/`),
so it only needs viem in the cwd's `node_modules`.

These fixtures aren't yet wired into Kotlin tests. The encoder currently
self-tests against the well-known `approve` reference vector + ABI-spec
invariants. Run this script + paste output into a future
`AbiCalldataFixturesTest.kt` before the first mainnet broadcast for a
strong cross-language check.
