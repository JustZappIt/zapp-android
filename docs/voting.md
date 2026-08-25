# Coinholder polling (governance voting)

Zapp carries upstream zodl-android's coinholder-polling feature: a shielded vote where the wallet
proves it held ZEC at a snapshot height without revealing which notes, splits its voting weight into
encrypted shares, and submits them to a set of tallying servers. The plumbing is upstream's,
verbatim; the screens are the fork's own.

`ZAPP_CHANGES.md` §8 previously listed this feature as absent by standing decision. That entry is
gone: the reasoning behind it was that cherry-picking pieces of a large security-sensitive feature
is worse than not having it, and this port does not cherry-pick — it takes the whole module.

## Shipping status

**Off.** `VOTING_ENABLED` in `FeatureVotingImpls.kt` is `false`, so the settings entry is hidden and
`HomeVM`'s session recovery bails out. The screens and routes are registered but unreachable.

The blocker is not the feature. It is the SDK:

| SDK snapshot | Slipstream sync engine | public `VotingSdk` facade |
|---|---|---|
| `3.0.1-SNAPSHOT` (the fork's prior pin) | 257 classes | none |
| `3.0.2-SNAPSHOT` | 255 classes | none |
| `3.1.0-SNAPSHOT` (**current pin**) | **none** | **26 classes** |

No published artifact has both. 3.0.1's native library is not even built with `cfg(zcash_voting)` —
it carries the voting Kotlin classes but exports zero voting JNI symbols, so every native call would
fail at runtime. 3.1.0 exports 63 of them.

So this branch is on 3.1.0 and has therefore lost Slipstream. `WalletCoordinatorFactory` no longer
passes `isSlipstreamEnabled`, because the parameter does not exist any more. Sync falls back to the
stock engine. That matters beyond sync speed: `GiftCardLedger`, `GiftCard` and `FundGiftCardUseCase`
all reason in comments about Slipstream automatically resubmitting a locally-created outgoing
transaction, and that behaviour is what changes.

Upstream did not drop Slipstream casually, and this shapes the choice. SDK commit `6b4d6339`,
*"MOB-1757: Remove the Slipstream sync engine and its AGPL dependency"*, removes it because the
`zodl-slipstream` crate is AGPL-3.0-only while the SDK publishes MIT artifacts — *"the native
library and the `zcash-android-sdk` AAR have been shipping copyleft code under an MIT POM since
3.0.0"* — and the same series adds a cargo-deny licence gate to keep it out. Slipstream is not
coming back to a published snapshot.

Zapp is itself AGPL-3.0-only, so that particular licence conflict does not bind the fork. But it
means keeping Slipstream is no longer "pin an older snapshot": it is maintaining a permanent fork of
the SDK's native build against an upstream licence gate.

**Resolve before merging.** Three ways out:

1. Accept the trade and re-verify the gift-card and send paths against the stock engine.
2. Build the SDK locally from `../zcash-android-wallet-sdk` on `android-slipstream-ironwood-chp`,
   which has both (53 slipstream files, 20 voting files). This is how upstream develops voting, but
   it means setting `SDK_INCLUDED_BUILD_PATH`, building the Rust backend locally, and rewriting the
   `.zapp-deps` note that currently says the fork deliberately does not do that.
3. Wait for a published snapshot carrying both — which, given MOB-1757, is not going to happen.

## What the port contains

- `feature-voting/` — 133 files from upstream `origin/main`, taken whole. Models, the Ktor API
  provider, the crypto client over `VotingSdk`, repositories, use cases, the PCZT/Keystone signing
  path, recovery, and the share-tracking WorkManager job. Its 124 unit tests come along and pass.
- `ui-lib/.../common/voting/VotingContracts.kt` — the seam. `ui-lib` never imports feature-voting;
  it talks to `VotingHomeHooks`, `VotingSettingsEntry` and `VotingNavContributor`, and the app module
  binds the implementations through `featureVotingModule`. Same shape as the migration seam.

## Fork-specific changes

Everything below is a deliberate divergence from upstream, not an accident of porting.

**The screens were rewritten.** Upstream's voting UI uses `ZashiColors`, `RoundedCornerShape`,
`CircleShape`, `ZashiButton` and Haze frosted headers, and puts back navigation in the top bar. The
fork bans all of that. The information architecture, reading order and copy are upstream's; the
surfaces are the fork's. Concretely:

- Back moved to the bottom-left via `ZappBottomActionBar`, so `VoteAppBar` is title-only plus the
  optional poll-source gear.
- Every corner is square. Round radio buttons became a bordered box with a filled inset; circular
  icon wells became square tiles; the frosted sheet headers became pinned headers with a hairline.
- `VoteButtons.kt` is the single adapter turning the view models' `ButtonState` into a `ZappButton`,
  so no voting screen has to know the Zashi button exists.
- Poll status chips read active as accent and voted as success. Upstream tints both green, which
  makes the two hard to tell apart in a list.
- `ZashiConfirmationStyle` was added to `ui-design-lib` because the view models emit it, but only the
  half that carries meaning: an unverified-poll warning leads with the cautious action. Upstream also
  rounds that sheet's corners; the fork does not.

**Strings** live in `ui-lib/src/main/res/ui/voting/` rather than upstream's
`ui-design-lib/.../common/`, following the fork's per-feature split. The ids are upstream's verbatim,
so upstream string patches still apply. 152 of the 197 carry `tools:ignore="MissingTranslation"` —
that is upstream's own state, not a gap this port introduced.

**Detekt** findings in the copied plumbing are baselined rather than fixed, per `AGENTS.md`: 62
entries lifted from upstream's own baseline.

**A real fix came with it.** `BaseKeystoneScanner` gained `scanSessionId` (upstream's fix for
"Keystone voting PCZT scan silently looping forever", #2441). Without it, scanning the second bundle
after the first reuses the finished decode result and loops forever.

## Wiring

`settings.gradle.kts` (module), `app/build.gradle.kts` (dependency), `ZcashApplication`
(`featureVotingModule`), `WalletNavGraph` (destinations via `VotingNavContributor`), `MoreVM` (the
settings entry, gated on `VOTING_ENABLED`), `HomeVM` (session recovery and share-tracking resume),
`ProviderModule` (the two how-to-vote preference providers), `UseCaseModule`
(`GetWalletSeedBytesUseCase`), `SynchronizerProvider` (`getVotingWalletDbPath`), and
`ConfigurationEntries` (`voting_config_url`, `voting_server_url`).

`ui-lib/build.gradle.kts` enumerates resource directories explicitly, so `src/main/res/ui/voting` had
to be added to the list or none of the strings resolve.
