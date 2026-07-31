# thirdweb → Pimlico ERC-4337 migration | 2026-05-23

End-to-end log of swapping the offramp's ERC-4337 bundler + verifying-paymaster from
thirdweb to Pimlico, plus the rabbit-hole bugs uncovered along the way. Captures both
what landed and what's still open.

## TL;DR

- **Why:** thirdweb gates mainnet AA sponsorship behind a $99/mo Growth plan. Pimlico
  offers pay-as-you-go (~$0.01 per sponsored UserOp) with no subscription floor.
- **Migration:** ~5 surgical edits across `BundlerClient.kt`, `Erc4337Submitter.kt`,
  `ProviderModule.kt`, `gradle.properties`, `ui-lib/build.gradle.kts`. Same
  ERC-4337 v0.6 EntryPoint, same thirdweb-deployed account factory, same smart-account
  address. Only the bundler/paymaster *service* changed.
- **Status:** Sepolia end-to-end passes (Order #220 placed). Mainnet flow lands the
  bridge + first 3 sponsored UserOps but a stale-Pimlico-mempool race trips AA25 on
  the 4th (`setSellOrderUpi`). Diagnosed, not yet fixed.

## Background

Existing wiring used thirdweb's bundler at `https://<chainId>.bundler.thirdweb.com/v2`
authenticated via `X-Client-Id` header. Smart account was the thirdweb prebuilt
`Account` contract behind the prebuilt `AccountFactory` at
`0x85e23b94e7F5E9cC1fF78BCe78cfb15B81f0DF00` (same address on all chains). EntryPoint
v0.6 at `0x5FF137D4b0FDCD49DcA30c7CF57E578a026d2789` (also same on all chains).

Sepolia sponsorship worked free on thirdweb. Mainnet sponsorship required Growth tier
($99/mo), too steep for a test/iteration phase.

## Pricing discovery

Confirmed by reading thirdweb pricing page directly (their support chatbot conflated
"thirdweb Payments", fiat-onramp / swap product, with "Account Abstraction"):

| Provider | Free tier | Pay-as-you-go | Subscription |
|---|---|---|---|
| **thirdweb AA** | Testnets only | No PAYG; credits don't unlock mainnet | $99/mo Growth, +2.5% mainnet gas |
| **Pimlico** | Testnets only (1M credits/mo) | Card required, no monthly minimum, billed at $1K accumulated | Enterprise (custom) |

Pimlico cost model: each sponsored UserOp is ~1,050 credits = **~$0.0105** plus a 10%
surcharge on actual gas. Full 4-step offramp flow ≈ 3–4¢ on mainnet.

## What we changed (code)

| File | Change |
|---|---|
| `gradle.properties` | Replaced committed `THIRDWEB_CLIENT_ID` with `PIMLICO_API_KEY=` (blank default; comment notes the key is *not* frontend-safe, must live in `local.properties`) |
| `ui-lib/build.gradle.kts:208-215` | `THIRDWEB_CLIENT_ID` `buildConfigField` → `PIMLICO_API_KEY` |
| `evm-lib/.../BundlerClient.kt` | URL builder → `api.pimlico.io/v2/<chainId>/rpc?apikey=…`. Dropped `X-Client-Id` header (Pimlico uses query-param auth). Renamed `thirdweb_getUserOperationGasPrice` → `pimlico_getUserOperationGasPrice` with `standard`-tier unwrap. Split `pm_getPaymasterStubData` / `pm_sponsorUserOperation` because they take **different 3rd-param shapes** (see API quirks below). Added local nonce cursor support via constructor. |
| `evm-lib/.../Erc4337Submitter.kt` | Added `nonceCursor: BigInteger?` instance field. After each successful `bundler.sendUserOperation`, advance cursor locally. Sidesteps cross-RPC nonce races on fast-block chains. |
| `ui-lib/.../ProviderModule.kt:190-198` | Pass `PIMLICO_API_KEY` + `chainId` to `BundlerClient` constructor. |

## Pimlico API quirks discovered live (curl-probed against Base Sepolia)

The docs and SDK reference were ambiguous; only live probing revealed the actual
shapes. **Do not assume ERC-7677 standard compliance**:

| Method | 3rd param expected | Pimlico-specific? |
|---|---|---|
| `pm_getPaymasterStubData` | chain-ID hex string (e.g. `"0x14a34"`) | **Yes**, non-standard, but same shape thirdweb used |
| `pm_sponsorUserOperation` | context object (e.g. `{}` or `{sponsorshipPolicyId: ...}`) | ERC-7677 standard |

Other findings:

- `pimlico_getUserOperationGasPrice` returns `{slow, standard, fast}` tiers, not a flat
  `{maxFeePerGas, maxPriorityFeePerGas}`. Pick `standard` for sensible default.
- `api.pimlico.io/v2/<chain>/rpc` serves **only** ERC-4337 + Pimlico extension methods.
  It does **not** serve `eth_call`, confirmed by direct probe. Our smart-account
  derivation and EntryPoint nonce reads have to use a separate node RPC (Alchemy /
  Infura), which is what gives rise to the nonce-race bug below.
- Supported EntryPoints on Base Sepolia: v0.6, v0.7, v0.8. Verified via
  `pm_supportedEntryPoints`. We stay on v0.6 to match the p2p.me Diamond.

## Pre-flight bugs uncovered (not part of the migration itself, but blocked the rebuild)

Mainly side-effects of the most recent upstream merge `29275269`:

1. **Gradle wrapper bump (9.5.1) incompatible with pinned SDK lockfile**
   `../zcash-android-wallet-sdk/build-conventions/gradle.lockfile` was written under
   Gradle 8.x, hard-pinning `kotlin-dsl` 5.2.0 and `kotlin-stdlib` 2.3.20. Gradle 9.5.1
   wants 6.5.7 / 2.0.21. Configuration fails before any task runs. Rolled back the 4
   wrapper files (`gradle-wrapper.jar/.properties`, `gradlew`, `gradlew.bat`) to 8.14.4
   in the working tree, **currently uncommitted**. Proper fix is upstream SDK regenerating
   its lockfiles for Gradle 9.x.

2. **Chat reply UI calls missing `ZMMessage` fields**
   `ChatModels.kt:96-98` and `ChatRoomVM.kt:631-642` reference `replyToId`,
   `replyToSenderName`, `replyToContent` on `ZMMessage` and `sdk.sendMessage(...)`.
   Those fields don't exist in the pinned zappMessaging SHA `6f6325cb`. Stubbed
   both call sites with TODO comments. Reply UI now local-echo only.

3. **`Zatoshi.fromZecString` signature changed in SDK bump**
   `CreateFlexaTransactionUseCase.kt:96` was calling the old `(Context, String)`
   signature. Updated to `(String, Locale)` with `context.resources.configuration.locales[0]`,
   matching the pattern at `EnvironmentInfo.kt:38`.

## Test results

### Sepolia smoke test | PASSED ✅

Full flow worked end-to-end:

- Bridging funds (no-op on testnet: `PreFundedOfframpFunding`)
- Approving USDC ✅ (1st sponsored UserOp)
- Placing the order ✅ → received Order ID #220 from the Diamond
  `0xeb0BB8E3c014D915D9B2df03aBB130a1Fb44beb9`
- Waiting for merchant to accept: hung, as expected on Sepolia. The merchant bot is
  offline (see memory note `Sepolia merchant bot offline since 2026-04-16`). Not a
  blocker; the on-chain placement is what we needed to validate.

First attempt actually failed with AA25 between approve and placeOrder. That's what
motivated the local nonce cursor fix. Second attempt with the fix passed cleanly.

### Mainnet test | PARTIAL ✅, blocked at step 6

- Bridging funds: no-op (account pre-funded with 0.6 USDC directly)
- Approving USDC ✅: UserOp at nonce **0**, sponsored by Pimlico paymaster
  `0x6666666666667849c56f2850848cE1C4da65c68b`
- Placing the order ✅: UserOp at nonce **1**, received Order ID #547859 from the
  Diamond `0x4cad6eC90e65baBec9335cAd728DDC610c316368`
- Waiting for merchant to accept ✅: real mainnet merchant accepted
- Allowance top-up ✅: UserOp at nonce **2** (hidden step: orchestrator's
  `broadcastSetSellOrderUpi` runs a 4th approve when `updatedAmount > placedMicros`,
  here 0.7 > 0.6 USDC; see `OfframpOrchestrator.kt:352-361`)
- Sending encrypted UPI ❌: `setSellOrderUpi` UserOp at nonce **3** rejected with
  `AA25 invalid account nonce`

### The mainnet AA25 | diagnosis

Confirmed via Infura `eth_getLogs` (3 `UserOperationEvent`s landed at nonces 0, 1, 2)
and the actual failing request payload pulled from the Pimlico dashboard:

```json
{
  "method": "eth_estimateUserOperationGas",
  "params": [{ "nonce": "0x3", "sender": "0xdD53a3Db…", ... },
             "0x5FF137D4b0FDCD49DcA30c7CF57E578a026d2789", null]
}
```

- On-chain `EntryPoint.getNonce(sender, 0)` returns **3** (correct: next expected nonce
  after 3 confirmed ops).
- We sent **0x3 = 3**, matches.
- Pimlico's simulator returns AA25 anyway.

**This is not a bug in our cursor logic.** The cursor sent the correct value. The
rejection is Pimlico-side state lag, almost certainly a pending UserOp in their
mempool view treating nonce 3 as already-reserved.

## Open issues / TODOs

| Item | Severity | Notes |
|---|---|---|
| **Mainnet AA25 race on subsequent UserOps** | High | Workarounds: (1) wait + retry via `resume()` path, (2) switch to 2D nonce keys per UserOp (~15 lines in `Erc4337Submitter.kt`). Need to validate (1) first. |
| **`PIMLICO_API_KEY` committed to `gradle.properties`** | High (security) | Pimlico keys are not frontend-safe. Move to `local.properties` (gitignored) and rotate the key in the dashboard before any push. |
| **Gradle wrapper rollback (4 files) uncommitted in working tree** | Medium | Don't ship the wrapper bump until SDK lockfiles are regenerated for Gradle 9.x. |
| **3 TODO patches for upstream chat/Flexa breakage** | Medium | Marked `TODO` in code. Revert when zappMessaging ships `replyTo*` API and bump the pin; Flexa fix is permanent. |
| **`DevOfframpAccountProvider` wired for mainnet too** | High (must revert before ship) | Currently both networks use the committed dev EOA so the smart-account address is stable across rebuilds during testing. `ProviderModule.kt:178-194` has the revert recipe in a comment. Anyone with the repo can drain the dev account's mainnet balance. Keep it small. |
| **Bundler logs URL with apikey in plaintext** | Low | ktor's INFO-level HTTP logger will print `api.pimlico.io/v2/.../rpc?apikey=…` to logcat. Acceptable for debug builds; harden before release. |

## Smart account address (reference)

`0xdD53a3Db48e5b69F34Abc1fA3156Dc3d0c269D5E`, derived deterministically from the
committed dev key in `DevOfframpAccountProvider.kt:13` plus the thirdweb factory at
`0x85e2…0DF00`. Same address on every chain (factory deployed at identical address,
CREATE2 salt = owner). Pre-funding once = good for every chain + every rebuild until
the dev-key wiring is reverted.

## How to reproduce the working build

```bash
# Add to local.properties (gitignored):
#   PIMLICO_API_KEY=pim_…
#   P2P_RPC_URL_BASE_MAINNET=https://base-mainnet.infura.io/v3/<key>
#   P2P_SUBGRAPH_URL_MAINNET=…
#   ZCASH_NETWORK=mainnet   (or testnet)
#   P2P_NETWORK=mainnet     (or sepolia)

# Sepolia testnet build (Zcash testnet flavor, Sepolia offramp):
./gradlew :app:installZcashtestnetFossDebug

# Mainnet build (Zcash mainnet flavor, Base mainnet offramp):
./gradlew :app:installZcashmainnetFossDebug -PZCASH_NETWORK=mainnet -PP2P_NETWORK=mainnet
```

The two APKs coexist on the device (different package names: `xyz.justzappit.zapp.testnet.foss.debug`
vs `xyz.justzappit.zapp.foss.debug`).

## Useful one-shot probes

```bash
# Pimlico endpoint sanity (returns chain ID):
curl -sS "https://api.pimlico.io/v2/8453/rpc?apikey=$KEY" -X POST \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}'

# Smart-account on-chain nonce (Base mainnet):
curl -sS "$BASE_MAINNET_RPC" -X POST -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"eth_call","params":[{
        "to":"0x5FF137D4b0FDCD49DcA30c7CF57E578a026d2789",
        "data":"0x35567e1a000000000000000000000000dD53a3Db48e5b69F34Abc1fA3156Dc3d0c269D5E0000000000000000000000000000000000000000000000000000000000000000"
      },"latest"]}'

# UserOperationEvent logs for a sender (Infura, 10k block limit):
curl -sS "$INFURA_BASE_MAINNET" -X POST -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"eth_getLogs","params":[{
        "fromBlock":"0x...","toBlock":"latest",
        "address":"0x5FF137D4b0FDCD49DcA30c7CF57E578a026d2789",
        "topics":["0x49628fd1471006c1482da88028e9ce4dbb080b815c9b0344d39e5a8e6ec1419f",
                  null,
                  "0x000000000000000000000000dd53a3db48e5b69f34abc1fa3156dc3d0c269d5e"]
      }]}'
```

"https://base-mainnet.infura.io/v3/<YOUR_INFURA_KEY>", // Infura - 10k block limit