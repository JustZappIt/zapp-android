# Coinholder polling (governance voting)

Zapp carries upstream zodl-android's coinholder-polling feature: a shielded vote where the wallet
proves it held ZEC at a snapshot height without revealing which notes, splits its voting weight into
encrypted shares, and submits them to a set of tallying servers. The plumbing is upstream's,
verbatim; the screens are the fork's own.

`ZAPP_CHANGES.md` §8 previously listed this feature as absent by standing decision. That entry is
gone: the reasoning behind it was that cherry-picking pieces of a large security-sensitive feature
is worse than not having it, and this port does not cherry-pick — it takes the whole module.

## Shipping status

**On.** `VOTING_ENABLED` is `true` and the feature is reachable from the "You" tab.

An earlier revision of this document said the opposite, on the grounds that SDK 3.1.0 drops the
ZODL Slipstream sync engine and that shipping voting therefore meant losing it. **That was wrong.**
Slipstream was not deleted at 3.1.0 — it was *split out*: the AGPL-licensed engine left the
MIT-published `zcash-android-sdk` and became its own `zcash-android-sdk-slipstream` artifact, which
still reaches the app transitively through `sdk-incubator`. Verified on device: the running build
stacks through `com.zodl.slipstream.SlipstreamSynchronizer.prepare`. `isSlipstreamEnabled`
disappeared from `WalletCoordinator` because the engine stopped being opt-in per wallet, not
because sync fell back to `CompactBlockProcessor`. Both features ship together.

What the SDK bump is genuinely for: 3.0.1 carries the voting Kotlin classes but a native library
built without `cfg(zcash_voting)`, exporting **zero** voting JNI symbols, so every native call would
fail at runtime. 3.1.0 exports 63 and adds the public `VotingSdk` facade.

### One real risk, and it is about pinning

`ZCASH_SDK_VERSION=3.1.0-SNAPSHOT` is a *mutable* coordinate. SDK commit `6b4d6339`
("MOB-1757: Remove the Slipstream sync engine and its AGPL dependency") removes the engine outright
and adds a cargo-deny licence gate to keep it out; it merged on 2026-08-21, **after** the snapshot
the fork currently resolves (`3.1.0-20260820.161424-1`, built 16:14 UTC on 2026-08-20). When
`3.1.0-SNAPSHOT` is next republished from a commit that includes it, Slipstream will vanish from
under this branch silently, with no code change and no build failure — the first symptom would be a
behaviour change in sync.

Zapp is itself AGPL-3.0-only, so upstream's MIT/AGPL conflict does not bind the fork. But keeping
Slipstream past that point means either pinning the exact timestamped snapshot or maintaining a fork
of the SDK's native build against an upstream licence gate. **Decide this before merging**, and
consider pinning `3.1.0-20260820.161424-1` explicitly in the meantime.

### Why the poll list is empty right now

Not a defect, and not the UI. Against the live production config:

- The only round ZODL endorses is **"NU7 Scope"** (`16eef7eb…`, active). It is absent from the
  dynamic voting config, so `RoundAuthenticator` returns `MISSING_ROUND` and the wallet refuses to
  show it — it will not act on a round whose attestation it cannot verify.
- Every round the config *does* attest is a closed `[TEST]` round (status 3), and one of those is
  `auth_version: 1`, which `RoundAuthenticator` rejects by deliberate policy (MOB-1678): v1 signs
  only the raw `ea_pk` and does not pin the round id or PIR layout.

So "no polls right now" is the security model working. Upstream's own Android build shows the same
against this config today. It resolves when the publisher adds the live round to the config, or via
a custom config source added from the screen's gear. Do not relax the authenticator to make the list
populate.

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
