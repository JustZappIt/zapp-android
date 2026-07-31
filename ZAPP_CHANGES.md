# What Zapp changes relative to upstream

Zapp is a fork of [`zodl-inc/zodl-android`](https://github.com/zodl-inc/zodl-android), which is
itself the Zashi lineage. Upstream is the golden standard for style and for everything that is not
a deliberate product difference.

This page is organised by **area**, not by commit. The fork carries several hundred commits on top
of upstream and the published history is squashed, so a per-commit list would go stale the day it
was written. Read this for the shape of the divergence, then read
[`docs/audits/UPSTREAM-PARITY.md`](docs/audits/UPSTREAM-PARITY.md) for the live tracking record of
which specific upstream patches have been taken, which are still owed, and which were examined and
ruled out.

---

## 1. Branding and package id

| | Upstream | Zapp |
|---|---|---|
| `ZCASH_RELEASE_APP_NAME` | `Zodl` | `Zapp` |
| `ZCASH_RELEASE_PACKAGE_NAME` | `co.electriccoin.zcash` | `xyz.justzappit.zapp` |
| `ZCASH_VERSION_NAME` | tracks zodl releases | independent line, currently `4.1.0` |

The **application id** changes so the two apps can coexist on one device. The **Kotlin and Java
packages do not**: everything still lives under `co.electriccoin.zcash.*`, and the app module's
`namespace` is still `co.electriccoin.zcash.app`. See section 9 for why.

Launcher icons, the Play listing under `fastlane/`, and the release notes under `docs/whatsNew/`
are Zapp's. Upstream ships English and Spanish; the fork adds Portuguese and Indonesian
(`ui-lib/src/main/res/ui/*/values-pt`, `values-in` and `values-b+id`). Indonesian needs both
directories, the legacy `in` code for Android 14 and below and the BCP-47 `b+id` for Android 15+,
and both must appear in the locale allowlist in `secant.android-build-conventions.gradle.kts` or
they are stripped from the APK.

## 2. The Zapp visual design system

Zapp layers its own design tokens **on top of** upstream's rather than replacing them.
`ProvideZappTheme` installs Zapp colors, typography and spacing into the composition without
touching Material3, so any screen still rendering through `ZcashTheme` and the `Zashi*` component
wrappers keeps its inherited look and stays cheap to merge.

- `ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/theme/ZappTheme.kt` and
  `ZappSpacing.kt`
- `.../design/theme/colors/ZappPalette.kt`, `ZappTypography.kt`
- `.../design/component/zapp/` : the Zapp component set (`ZappFab`, `ZappSpeedDialFab`,
  `ZappInputField`, `ZappBorderedCard`, `ZappStackedActionBar`, `ZappConfirmationBottomSheet`,
  `ZappSettlementLedger` and others)
- `.../design/animation/ZappMotion.kt`, `ZappPressScale.kt`, `ZappShake.kt`

The house rules for which theme a given file may reach for are summarised under "Theming" in
[`AGENTS.md`](AGENTS.md). Structural `Zashi*` wrappers stay; `ZashiColors` / `ZashiTypography` /
`ZashiDimensions` are not used in new Zapp UI.

## 3. Application shell and navigation

Upstream is a wallet with a home screen. Zapp is a three-tab shell (Pay, Chats, You) with the
wallet living inside the Pay tab.

- `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/tabs/` : `ZappTabsScaffold`,
  `FloatingPillNavBar`, `WalletHomeView`, `WalletBalanceCard`, `PayActionSpeedDial`,
  `SettingsTabContent`
- `.../screen/welcome/`, `.../screen/splash/`, `.../screen/onboarding/` : the fork's own
  first-run gate, rendered inside the tabs destination rather than as a separate nav graph
- `.../screen/unifiedsend/` : the fork's send surface. Upstream's `screen/pay/` does not exist
  here; `UnifiedSendVM` is the equivalent, which is the single most common cause of an upstream
  patch not applying cleanly

## 4. P2P encrypted messaging

The messaging engine is a [Bare](https://github.com/holepunchto/bare) (Holepunch) JavaScript
worklet running in-process over Hyperswarm, not a service the app talks to. Two Gradle projects are
pulled in from sibling checkouts by `settings.gradle.kts`:

```
include(":zappmessaging")   ->  ../zappMessaging/android
include(":bare-kit")        ->  ../bare-kit/android
```

Both resolve to a workspace-root path as a fallback, which is what CI uses. `.zapp-deps` pins the
exact commit of each sibling and CI verifies the `zappMessaging` SHA before building.

Where it attaches in this repo:

- `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/` : the whole chat product.
  `list/`, `room/`, `contacts/`, `newconv/`, `profile/`, `settings/`, `identity/`, `media/`,
  `scan/`, `onlinestatus/`, `readreceipts/`, `backgrounddelivery/`, `support/`, `repository/`
- `ui-lib/src/main/java/co/electriccoin/zcash/di/ZappMessagingModule.kt` and
  `ChatViewModelModule.kt` : the Koin wiring
- `.../screen/chat/common/ChatBootstrap.kt` : initialises the SDK and derives the chat identity
  from the wallet's BIP-39 seed phrase, so there is no separate signup. The Ed25519 identity is a
  deterministic function of the wallet you already have
- `.../screen/chat/repository/ChatContactsRepository.kt` : the wallet address book is the single
  source of truth for chat contacts, rather than a second parallel contact store
- `app/src/main/AndroidManifest.xml` carries the `tools:overrideLibrary` entry for
  `xyz.justzappit.zappmessaging, to.holepunch.bare.kit`, both of which declare `minSdk 29` against
  the app's 27

**Honest limitation:** `JustZappIt/zappMessaging` is currently a private repository. Everything in
this repo is complete and readable, but an outside contributor cannot build the messaging half
today. The wallet, offramp and design-system work are unaffected.

## 5. Background notifications

Upstream ships no chat, so it ships nothing here. Zapp's push is a **doorbell, not a mail carrier**:
the push payload carries no message content, it only wakes the app, which then pulls the still
end-to-end-encrypted message over its own channel.

- `ui-lib/src/main/java/co/electriccoin/zcash/ui/common/push/` : the shared contract
  (`ChatPushBackend`, `PushRegistrar`, `ChatDoorbellValidator`, `TopicSubscriptionReconciler`,
  `ChatNotificationTiming`)
- `ui-lib/src/foss/java/.../push/` : the no-Google path. A self-hosted ntfy topic plus Zapp's own
  `ChatWakeService` foreground service, so there is no distributor app to install
- `ui-lib/src/google/java/.../push/` : contentless FCM topics, compiled into the `store` and
  `internal` distributions only (`ui-lib/build.gradle.kts` maps `src/google/` onto both)
- `docs/notifications/` : the architecture, the deployment units for the relay, and the operational
  notes

## 6. The P2P.me offramp

Zapp can spend ZEC into local fiat rails (UPI, Pix, and several Latin American corridors) through
[p2p.me](https://p2p.me), whose Diamond contract lives on Base. The protocol work is deliberately
kept out of the Android modules so it can be unit-tested on the host JVM:

- `evm-lib/` : Kotlin Multiplatform EVM primitives under `xyz.justzappit.evm`. RLP, EIP-1559
  transactions, ERC-4337 v0.6 user operations, secp256k1 signing, ECIES, an RPC client
- `offramp-lib/` : the p2p.me protocol under `xyz.justzappit.offramp`. Order construction and
  reading, the subgraph client, payment-address decryption, and the per-corridor QR parsers (UPI,
  Pix, EMV, PagoMovil, MercadoPago, QRIS, NGN, COP)

The Android-facing surfaces:

- `ui-lib/.../screen/swap/upi/` : the offramp flow itself, including the ZEC-to-USDC funding bridge
  and the scan-and-pay corridors
- `ui-lib/.../screen/settings/p2p/` : payment-method selection and P2P transaction history
- `ui-lib/.../screen/topup/` : the top-up entry point

Configuration arrives through the usual `local.properties` to `gradle.properties` to environment
chain: `P2P_NETWORK`, `P2P_RPC_URL_BASE_*`, `P2P_SUBGRAPH_URL_*`, `PIMLICO_API_KEY`,
`PIMLICO_SPONSORSHIP_POLICY_ID`, `OFFRAMP_USE_DEV_KEY`. The committed defaults are blank or safe;
anything developer-specific belongs in `local.properties`.

## 7. Privacy posture

- **Tor is on by default for every provisioned wallet.** `WalletRepository` writes the preference
  as part of both create and restore, and upstream's restore-time Tor opt-in screen
  (`screen/restore/tor/`) is deleted rather than defaulted. A user cannot end up with a wallet that
  silently never had Tor.
- **The `foss` distribution stays genuinely Google-free**, including notifications. That is
  upstream's flavor design; the fork's contribution is refusing to let the chat stack break it.
- **Chat has no accounts, no phone numbers and no directory.** Identity is derived from the wallet
  seed. The relay that provides offline delivery handles encrypted blobs and a routing referrer; it
  never sees plaintext.
- **Known limitations are published, not buried.** [`SECURITY.md`](SECURITY.md) describes the
  high-severity findings that are still open, in enough detail to judge the risk, so you can decide
  what to trust this build with.

## 8. Upstream surfaces the fork does not ship

- **Coinholder governance voting.** The complete upstream feature (models, repositories, PCZT and
  Keystone signing, recovery, workers, screens, resources, tests) is absent by standing decision.
  It is a large security-sensitive feature, not a missing plumbing patch, and cherry-picking pieces
  of it is worse than not having it. Tracked as `D1` in the parity document.
- **Flexa is retained but inert.** `screen/flexa/`, `FlexaRepository`, `GetFlexaStatusUseCase` and
  the `IntegrationsVM` entry are kept byte-compatible with upstream so that merges in that area
  stay trivial, but `ZCASH_FLEXA_KEY` is blank, so `FlexaRepository.init()` never builds a client
  configuration and the SDK is never initialised. Do not delete this code to "clean up"; deleting
  it costs more at every future merge than leaving it costs at runtime.

---

## 9. How we stay close to upstream

Parity is a deliberate discipline, not an accident. The fork changes product and leaves plumbing
alone, because the cheaper an upstream merge is, the faster a Zcash security fix reaches Zapp users.

Unchanged on purpose, and not to be "tidied":

| Thing | Kept as | Why |
|---|---|---|
| Kotlin / Java packages | `co.electriccoin.zcash.*` | A rename would conflict every upstream file at once |
| Gradle module names | `app`, `ui-lib`, `ui-design-lib`, `sdk-ext-lib`, ... | Patch paths must match |
| Product flavors | `zcashmainnet` / `zcashtestnet` × `store` / `foss` / `internal` | Upstream's variant matrix and CI task names |
| Directory layout | upstream's `src/main/res/ui/<feature>/` split | Resource merge conflicts otherwise |
| Resource ids | upstream's | Upstream string and drawable patches apply verbatim |
| Build-property names | `ZCASH_*`, `ANDROID_*`, `IS_*` | Upstream `gradle.properties` diffs apply verbatim |

Fork-only modules take a fork-only package (`xyz.justzappit.evm`, `xyz.justzappit.offramp`) because
they have no upstream counterpart to collide with.

The live record of what has been ported, what is outstanding, and what was investigated and
correctly rejected is [`docs/audits/UPSTREAM-PARITY.md`](docs/audits/UPSTREAM-PARITY.md). It is a
living document: edit it, do not start a new dated snapshot beside it.

### Merge procedure

```bash
# 1. Fetch upstream onto a tracking branch. Check its release branches too:
#    upstream ships releases on release/X.Y.Z before merging them to main.
git fetch <zodl-remote>

# 2. Branch off the fork's main.
git checkout -b zapp/merge-zodl-X.Y.Z main

# 3. Merge. Expect conflicts concentrated in the files below.
git merge <zodl-remote>/main

# 4. Verify the composite build before opening a PR.
./gradlew :app:assembleZcashtestnetFossDebug
```

Conflicts cluster in a predictable set:

| File | Reason |
|---|---|
| `settings.gradle.kts` | the `:zappmessaging` and `:bare-kit` sibling projects |
| `gradle.properties` | Zapp app name, package id, version line, and the fork-only `P2P_*`, `BLIND_PEER_*`, `NTFY_*`, `ZAPP_MESSAGING_*` keys |
| `app/build.gradle.kts` | the `ZCASH_NETWORK` variant filter and the testnet `resValue` |
| `ui-lib/.../WalletNavGraph.kt`, `RootNavGraph.kt`, `Navigator.kt` | chat, offramp and tabs routes |
| `ui-lib/.../screen/tabs/` | entirely fork-authored |
| `ui-lib/.../screen/unifiedsend/` | replaces upstream's `screen/pay/` |
| `ui-design-lib/.../theme/`, `.../component/zapp/` | the Zapp design system |
| `.github/workflows/` | the sibling-checkout and `.zapp-deps` verification steps |

Before starting, read the parity document's "How to run the next parity sweep" section. It records
which apparent gaps have already been chased down and refuted, which saves a day of rediscovery.
