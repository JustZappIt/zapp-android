# Upstream parity | Zapp fork vs zodl Android

**Living document.** This is the single tracking record for how the Zapp fork diverges from upstream
`zodl-inc/zodl-android`, what is still owed, and what has already been paid. It supersedes and
replaces the five dated snapshots listed in section 6.2; do not start a new dated file, edit this one.

**As of:** 2026-07-28.

---

## 1. Purpose, and how to use this document

### What this is

Upstream parity is deliberate. The fork keeps upstream's Java/Kotlin packages, module names, gradle
flavor names, directory layout, resource ids and build-property names so that future upstream merges
stay cheap. Everything the fork changes on purpose is product divergence (Zapp visual system, P2P
messaging, the P2P.me offramp, privacy posture, no governance voting); everything else is shared
plumbing that should track upstream.

This document tracks the second category. It answers three questions:

1. What does upstream have that the fork has not taken? (section 3)
2. What has the fork already taken, and when? (section 4)
3. What looked like a gap but is not, so the next sweep stops re-finding it? (section 5)

### What this is not

- Not a process guide. For the fork's structural relationship to upstream, the module inventory, the
  customisation inventory and the security tier classification, see
  [`docs/FORK_MAINTENANCE_GUIDE.md`](../FORK_MAINTENANCE_GUIDE.md).
- Not a security triage procedure. For the tier-by-tier "sync within 48 hours / 1 week / 2 weeks"
  rules and the file-by-file guidance on the deliberately-modified security files, see
  [`docs/UPSTREAM_SECURITY_SYNC_GUIDE.md`](../UPSTREAM_SECURITY_SYNC_GUIDE.md).
- Not a claim that any listed port has been implemented. Section 3 is a backlog; section 4 is the
  evidence trail.

### Conventions

- **MOB-#### ticket ids and PR numbers are join keys.** Preserve them verbatim in commits and in this
  file. MOB ids are upstream's; PR numbers without qualification are `JustZappIt/android-zapp`.
- **Effort** is implementation time for someone already familiar with the area. It excludes review and
  device testing unless called out.
- **"Mechanical"** means the fork file is byte-identical to upstream apart from the change, so the
  patch applies with no adaptation.
- Gap ids (`G1`, `D1`, `R1`) are stable across sweeps. Do not renumber; mark closed items closed.

### How to run the next parity sweep

1. **Fetch both remotes first.** The fork's collaborative branch is `justzappit/main`, not
   `fork/main` and not `origin/main`. `origin` is upstream `zodl-inc/zodl-android`. A sweep run
   against a stale local `main` will re-report already-merged work (this happened on 2026-06-21, see
   R15).
   ```text
   git fetch justzappit && git fetch origin --tags
   ```
   Upstream's release branches matter as much as `origin/main`: 3.8.0 shipped on
   `origin/release/3.8.0` and was still unmerged to `origin/main` on 2026-07-28.
2. **Establish the delta.** Merge base is fixed at `2409c4d7104c44ba72fe05343e0a20585b156be9`
   (upstream release 3.3.1, 2026-04-13). The useful diff is from the *previously audited* upstream
   point to the current one, not from the merge base.
3. **Blob-hash classify every changed file** as old-upstream / new-upstream / diverged / absent
   in the fork, then do hunk-level three-way analysis only on the ones that matter.
4. **Honor the intentional divergences** in section 3.2 before flagging anything.
5. **Verify before reporting.** Every finding in this document was checked against live source at the
   cited `file:line`. Findings that were merely carried forward from an older document have twice now
   turned out to be closed or misdiagnosed (see section 5).
6. **Gate each resulting PR:**
   ```text
   ./gradlew :ui-lib:compileZcashmainnetFossDebugKotlin
   ./gradlew :ui-lib:testZcashmainnetFossDebugUnitTest
   ./gradlew :ui-lib:lintZcashmainnetFossDebug
   ```
   Run store/release variants separately: ABI filtering, minification and resource configuration are
   variant-specific. An SDK pin change additionally needs a full clean build. Fork CI cannot gate this
   work (see H4), so validate ktlint, detekt and compilation locally.

---

## 2. Current state

**As of 2026-07-28.**

| | |
|---|---|
| Fork HEAD | `955ebfd97` on `main` (tracks `justzappit/main`) |
| Fork version | `ZCASH_VERSION_NAME=4.0.0` (the fork owns its versioning) |
| Upstream `origin/main` | `19994980e`, version name 3.7.2, SDK `2.6.5-SNAPSHOT` |
| Upstream newest release | tag `3.8.0-2023` = `a6cbc0f3d` (2026-07-27) on `origin/release/3.8.0`, **not yet merged to `origin/main`** |
| Merge base | `2409c4d71` = upstream release 3.3.1 (2026-04-13) |
| SDK pin | `ZCASH_SDK_VERSION=2.8.0-rc.1-SNAPSHOT`, provenance SHA `a003bd83` in `.zapp-deps` |
| SDK resolution | Maven snapshots. `SDK_INCLUDED_BUILD_PATH` is blank in `gradle.properties`, matching upstream. No sibling SDK clone or local Rust build needed. Four earlier documents each name a different SDK target; all are superseded, see footnote [^4]. |
| `targetSdk` | Fork 35, upstream 36. This is the one build-config divergence that is **not** intentional (G1). |
| Reachability (measured 2026-07-11) | 442 fork-unique commits, 403 upstream-unique commits, 1,703 raw changed paths, dominated by `ui-lib` (1,251). The raw count badly overstates missing work: it counts fork-only files (offramp, EVM, chat, blind-peer, Zapp design, fork docs) and upstream-only files (voting, upstream release infrastructure). |

### What the delta is today

The fork is level with upstream on everything that matters for network compatibility and fund safety,
and is in fact **ahead of `origin/main`** on the Ironwood work because it tracked upstream's
`release/3.8.0` branch directly.

- **Ironwood (NU6.3) is fully ported.** SDK `2.8.0-rc.1-SNAPSHOT` matches upstream 3.8.0 exactly.
  Ironwood pool balances fold into the account's shielded balance, the Keystone firmware >= 3.0.1 gate
  (MOB-1510) is in, and the one-time announcement plus per-pool breakdown (MOB-1534) are ported and
  reskinned. Mainnet activation is block 3,428,143.
- **The swap and offramp quote/status pipeline was fully reconciled on 2026-07-24** across PRs
  #125, #129, #130 and #142. That was the highest-risk shared plumbing because the fork's offramp
  funding bridge rides the same 1Click/NEAR path.
- **Nothing in any audited upstream delta was a security regression in inherited code.** That
  conclusion has held across all five sweeps.

### Upstream work not yet audited

- `origin/main` moved 2 commits past the 2026-07-19 audit point (`05cb52e89..19994980e`): a LICENSE
  copyright-holder change to "Znewco, Inc. (d/b/a Zcash Open Development Lab)" and its merge. Tracked
  as G10.
- `origin/release/3.8.0` carries 14 commits past `origin/main`. All app-code ones are ported (section
  4); the rest are release preparation, a detekt/ktlint fix, and `86f79c07e` "Disable shielded voting:
  hard kill switch and gated entry points", which is N/A because the fork ships no voting.
- Unreleased upstream branches exist for the Orchard/slipstream migration
  (`feature/orchard_migration`, `android-slipstream-ironwood-chp`, `slipstream-integration`) plus
  `feat/tor-onion-lwd-endpoint` and `fix/mob-1475-refund-address-copy`. None are on a release branch.
  The slipstream migration is a 584-file work in progress: **do not port it**, wait for upstream to
  ship it.

---

## 3. Open gaps

### 3.1 Effort-ordered worklist

Every row was verified against live fork source on 2026-07-28 at the locations cited in the detail
notes below. Read the table to triage; read the note before starting work.

| id | area | what upstream has | fork status | effort | why deferred |
|---|---|---|---|---|---|
| **G1** | build config · `targetSdk` | `ANDROID_TARGET_SDK_VERSION=36` | 35 at `gradle.properties:196`, an accidental regression | ~10 min + regression pass | Needs an Android 16 pass. **PR #128 open, unmerged.** |
| **G2** | design-lib · `ZashiInfoText` | A `verticalAlignment` parameter | Hardcoded at `ZashiInfoText.kt:32` | ~10 min | None. Prerequisite for G8. |
| **G3** | design-lib · `RadioButtonState` | An `isEnabled` field | Absent at `ZashiRadioButton.kt:169` | ~15 min | Diff hygiene only. No upstream call site passes it either. |
| **G4** | swap · slippage disclaimer | `warning` typed `ZashiDisclaimerState?`, rendered via `ZashiDisclaimer` | Typed `StyledStringResource?`; the view hand-rolls a `ZashiCard` and loses icon and severity | ~30 min · 3 files | Cosmetic regression, not a behavior gap. |
| **G5** | Keystone · select-account screen | No "Forget this device" action; middle-ellipsized address | Stale `negativeButtonState`; address still head-truncated | ~45 min · 4 files | Low impact while G12 keeps the flow unreachable. |
| **G6** | design-lib · bottom-sheet parameters | `sheetGesturesEnabled`, `shape`, `properties`, `containerColor` | **Partially closed.** Two of four present. See footnote [^1]. | ~1h · additive | None. Do it **before** G14. |
| **G7** | restore · loading affordance and test tag | Coverage came via the deleted `RestoreTorTags.RESTORE_BTN` | Two orphans left behind by `448e39414`: no `isLoading`, no test tag | ~1-2h | Fork-local polish, no upstream pressure. |
| **G8** | settings · manual-server privacy explanation | `MultiServerInfoFooter()` in `ChooseServerView` (`ae7e90018`) | No footer at all. **Rewrite, not port:** upstream's copy is factually wrong for this fork. | ~3h | New copy must be written and translated into 5 locales. Depends on G2. |
| **G9** | send · MOB-385 anchor-failure guidance | Detects "Unable to compute anchor" and surfaces sync guidance | **No anchor handling at all** | ~2h | Recoverability and support UX; no new signing primitive. |
| **G10** | legal · upstream copyright holder | Upstream renamed its holder to Znewco, Inc. (`723d29966`) | Fork attributes "Copyright (c) 2021-2025 Zcash" | ~10 min | Attribution freshness, not a defect. Worth a legal read first. |
| **G11** | test infra · `testTagsAsResourceId` | Test tags exposed as resource ids | Zero occurrences in the fork | LOW | No instrumentation suite depends on it today. |
| **G12** | Keystone · connect flow unreachable at the root | Two entry points into Keystone connect | **Both severed.** The whole flow is dead at the root. | ~10 lines; the product decision dominates | Needs a product answer on the Zapp You-tab IA. **PR #94 open, may cover part of it.** |
| **G13** | design-lib · info-sheet consolidation | `InfoBottomSheetView` used at 15 call sites | Used at 1 of 13; the other 12 hand-roll it | ~half day · repetitive | Do it **after** G14 or apply that fix 12 times. |
| **G14** | design-lib · MOB-1449 sheet scrim and dismiss rework | Window-owned dim replacing the shared nav-state mechanism | Not started; the bug **is** present (Material3 1.4.0 pinned in both) | ~1-2 days incl. device testing | Architectural decision required, plus 15 sheets that will not inherit the fix. |
| **G15** | swap · MOB-1473 curated swap assets | `GetCuratedSwapAssetsUseCase` + a 29-pair allowlist | Not started; `GetSwapAssetsUseCase` still live in 4 VMs | ~1-2 days · adaptation | Four forced adaptations; upstream's diff does not apply. |
| **G16** | resources · MOB-1430 string-key unification | snake_case keys renamed, 54 English files collapsed to 4 | Not started. 644 colliding keys, 831 references, **917 unmapped Zapp keys**. | ~2-4 days · low risk, very high volume | **Buys diff hygiene, not functionality. Deferrable indefinitely; must not block anything above it.** |

**Sequencing.** G2 before G8. G6 before G14. G14 before G13. G12 needs a product answer before code.
G16 last, alone. Everything else is independent.

#### Item detail

**G1 · `targetSdk` 36.** The fork **inherited 36 at the fork point**. Fork commit `d66b2c85d`
("chore: initial Swiss designs", 2026-04-20, a 69-file UI commit) silently changed it to 35; nothing
in that commit justifies it and no comment or flag explains it, so it reads as an accidental
regression rather than an intentional divergence. `compileSdk` is already 36 in both. Consequences of
staying on 35: Play's target-API policy clock, and the app opts out of Android 16 behavior changes it
will eventually have to face anyway. Revert the line and run a behavior pass on Android 16
(predictive back, edge-to-edge enforcement).

**G2 · `ZashiInfoText` alignment.** Upstream `ZashiInfoText.kt:29,32` gained
`verticalAlignment: Alignment.Vertical = Alignment.CenterVertically` passed through to the `Row`. The
fork's signature stops at `textAlign` and hardcodes the alignment at `:32`. All 8 existing call sites
would keep the default, so this is safe and purely additive.

**G3 · `RadioButtonState.isEnabled`.** Without it the fork cannot express disabled styling, click
suppression or haptic suppression. Worth noting that **no upstream call site passes it**: it is
currently unused API surface upstream. Take it only for diff hygiene, or decline it explicitly.

**G4 · Swap slippage disclaimer.** `SwapSlippageState.kt:11` types `warning` as
`StyledStringResource?` where upstream uses `ZashiDisclaimerState?`, so `SwapSlippageView.kt:79-95`
hand-rolls a `ZashiCard` instead of calling `ZashiDisclaimer(state = it)` and loses the warning icon
and severity semantics. About 20 lines. The `ZashiDisclaimer` component already exists in the fork.
The similar-looking `KeepOpenView.kt:108-128` `DisclaimerCard` **is** an intentional Swiss reskin:
leave that one alone.

**G5 · Select Keystone account cleanup.** Two sub-items, both confirmed. (a) Remove the stale
"Forget this device" `negativeButtonState` at `SelectKeystoneAccountState.kt:13`,
`SelectKeystoneAccountViewModel.kt:70-74`, `SelectKeystoneAccountView.kt:103-107` and 2 previews.
(b) Swap the full unencoded UA subtitle for `stringResByAddress(...)`, which middle-ellipsizes and
applies `ROBOTO_MONO`; `DeriveKeystoneAccountUnifiedAddressUseCase.kt:18` still head-truncates with
`"${address.take(ADDRESS_MAX_LENGTH)}..."`. The helper already exists at parity in the fork
(`StringResource.kt:179`) and is used at about 30 other call sites: this screen was simply missed.

**G6 · Bottom-sheet parameter widening (residual).** `properties` and `containerColor` are already
present at `ZashiModalBottomSheet.kt:44-45`. Still missing: `sheetGesturesEnabled`, and `shape` is
hardcoded to `ZashiModalBottomSheetDefaults.SheetShape` at `:54`. Purely additive pass-through
plumbing, so no call-site breakage. Do this before G14: the fork's sheet composable is **older** than
upstream's pre-fix version, and G14's patch expects these parameters to exist. See footnote [^1].

**G7 · Restore loading affordance and estimation test tag.** Not an upstream port. The fork
deliberately deleted `screen/restore/tor/*` in `448e39414` ("Tor on by default") and Tor is now forced
at `WalletRepository.kt:239,284`. That deletion took two things with it that were never re-homed.
(a) `RestoreBDHeightVM.kt:67-73` and `RestoreBDEstimationVM.kt:47-52` fire a suspend restore that
blocks on the secret-state flip with **no `isLoading` on the button**, a dead button for the duration;
the fork's own `ZappRestoreFlowVM` has the affordance and `RestoreBDDateVM.kt:37-43` already uses
`mutableLce`/`withLce`, so the idiom is in-repo. (b) The estimation screen has **no test tag at all**
on its restore button; upstream's coverage came via `RestoreTorTags.RESTORE_BTN`, deleted with the
screen. Add `RestoreBDEstimationTags`. Error surfacing is fine: `1c3bea48a` survived in
`RestoreWalletAndNavigateUseCase.kt:28-46`.

**G8 · Manual-server privacy explanation.** Upstream `ae7e90018` adds a `MultiServerInfoFooter()` to
`ChooseServerView`, with a small `ZashiInfoText` alignment extension (G2) and localized strings. The
fork's choose-server screen has no privacy footer at all.

**Upstream's copy is factually wrong for this fork.** It reads "We may use multiple servers to
optimize performance... choose Manual connection mode", but the fork has no connection-mode concept
(`ChooseServerState` has no `connectionMode`), no `GetAutomaticEndpointUseCase`, and no
`MultiEndpointTransactionSubmitter`. The fork **only ever talks to the single server you select**.
Shipping upstream's string would tell users something untrue about their own privacy posture.

Write new copy for the fork's actual behavior, a single selected server with Tor on by default to hide
the IP from it, and render it in Zapp components against `ZappBottomActionBar`, not upstream's
`ZashiColors`/`ZashiHorizontalDivider` bottom bar. `ic_info` already exists in the fork. 5 locales.
Preserve `ValidateEndpointUseCase` and the `ChooseServerVM` error path untouched. This is independent
of D2: do **not** adopt automatic server selection merely to obtain this footer.

**G9 · MOB-385 anchor-computation failure guidance.** Upstream `c0076cdb7`, `a04ddee77`, `542310471`,
`6961dbde6`, `c581b9d54` detect `TransactionEncoderException.TransactionNotCreatedException` whose
root cause contains "Unable to compute anchor" and surface dedicated sync guidance instead of generic
failure copy. The fork has none of it (zero `isAnchorError` hits in `ui-lib`).

Code is about 15 lines: port `isAnchorError()` verbatim and restructure the fork's subject-based
`when (proposal)` (`TransactionProgressVM.kt:358-374`, 4 branches) into a predicate `when { }` with
the anchor check first, above the proposal branches, because anchor failures can occur on any proposal
type. **String placement diverges deliberately:** upstream's `542310471`/`6961dbde6` moved
send-confirmation strings from `ui-lib` into `ui-design-lib/.../common/`, and the fork has not done
that move, so add the new string to the fork's own `ui-lib/src/main/res/ui/send_confirmation/values*/`
and **do not** port those two relocation commits, which would fight the fork's resource layout.
Translation surface is 5 locale dirs, not upstream's 2. Preserve the fork's redaction hardening
(`SendEmailUseCase.kt:151` `stackTraceToLimitedString(250)`, the bare "Grpc failure" body at
`:121-130`); an anchor port touches only the subtitle expression. Add tests covering regular sends,
shielding and swap proposals.

**G10 · Upstream copyright holder.** `origin/main` `723d29966` updates upstream's LICENSE holder to
"Copyright © 2026 Znewco, Inc. (d/b/a Zcash Open Development Lab)". The fork's `LICENSE-MIT:3` and
`NOTICE:14` attribute upstream as "Copyright (c) 2021-2025 Zcash", which was correct when written.
Decide whether to mirror upstream's new wording. **The fork lineage recorded in `LICENSE`,
`LICENSE-MIT`, `LICENSE-APACHE` and `NOTICE` is legally required under MIT and stays regardless of
this decision.**

**G11 · `testTagsAsResourceId`.** Carried from the 2026-06-10 LOW polish batch and never actioned.
Zero occurrences anywhere in the fork.

**G12 · Keystone connect flow is unreachable at the root.** The explainer link works (see R1). What
does not work is reaching the flow that contains it. **Both** upstream entry points into Keystone
connect are severed in the fork:

- `AccountListVM.kt:53` sets `addWalletButton = null` (and `AccountListView.kt:157` mirrors it);
  upstream wires it at `:80`/`:99` to `forward(ConnectKeystoneArgs)`.
- `IntegrationsArgs` has **zero navigators**: it appears only at its definition
  (`IntegrationsScreen.kt:19`) and its registration (`WalletNavGraph.kt:283`). Upstream navigates from
  `ZashiTopAppBarVM.kt:115,118`; the fork's `common/appbar/` has no Integrations reference.

Since `IntegrationsVM.kt:110` is the only remaining `forward(ConnectKeystoneArgs)` call, the chain
`[nothing] → Integrations → ConnectKeystone → KeystoneExplainer` is dead at the root. No alternate
entry exists anywhere in `ui-lib` or `app`. Restoring `addWalletButton` is about 10 lines; re-homing
`IntegrationsArgs` needs a product call about where it belongs in the Zapp You-tab IA, since the fork
replaced upstream's top-app-bar overflow. **Nothing in the code says whether `addWalletButton = null`
was deliberate**: no comment, no flag. Confirm intent before building. PR #94
(`feature/keystone-zapp-reskin`) is open and may already cover part of this. This was the
highest-severity finding of the 2026-07-19 sweep; see footnote [^2].

**G13 · Info-sheet consolidation.** `InfoBottomSheetView.kt` is byte-identical between the repos, but
the fork uses it at **1 of 13** call sites (`HeightInfoView.kt:38`) where upstream uses it at 15. The
other 12 hand-roll `ZashiScreenModalBottomSheet` + `Column`/`verticalScroll`/`padding`/`ZashiButton`;
`AndroidWalletSyncingInfo.kt:46-83` is 38 lines against upstream's 21. Rendered output matches, so
this is **duplication, not a behavior gap**, but it multiplies G14's blast radius twelvefold. Do it
after G14 settles, or accept applying that fix 12 times.

**G14 · MOB-1449 bottom-sheet scrim and dismiss rework.** Upstream `5d747297d` / `fa4dc7228`. Upstream
**deletes** the shared nav-state mechanism rather than adding one: `SheetStateManager.kt` and
`util/LocalNavRoute.kt` go away, replaced by a platform window-animation approach where the host
window owns the dim via `FLAG_DIM_BEHIND`, Material's scrim is disabled, the dim is mirrored with
`animateFloatAsState`, and a `BottomSheetWindowAnimationEffect()` re-applies a window exit animation
to work around Material3 1.4.0 nulling the sheet dialog's `windowAnimationStyle`. See footnote [^3].

**The bug is genuinely present:** both repos pin `ANDROIDX_COMPOSE_MATERIAL3_VERSION=1.4.0`. Good news
on scope: upstream's diff touched **zero sheet call sites**, so the fork's 37
`ZashiScreenModalBottomSheet` files and 45 `rememberScreenModalBottomSheetState` sites inherit the fix
for free, and `ZcashTheme.kt`, `SheetStateManager.kt` and `LocalNavRoute.kt` are byte-identical to
upstream's pre-fix state so those hunks apply clean.

What makes it multi-day rather than an afternoon:

- The fork's `ZashiScreenModalBottomSheet` is **older** than upstream's pre-fix version. It lacks G6's
  parameters and lacks the `wasShown` → `Hidden → onBack()` guard upstream had *before* the fix and
  kept after. About 40 lines of hand-porting.
- **Architectural decision required.** The fork's `SheetStateManager` (driven from `Navigator.kt:46`)
  solves a related problem, animating the sheet out before a programmatic nav, by a different route.
  Upstream's design and the fork's are mutually exclusive: decide whether to retire `SheetStateManager`
  or keep it and take only the dim rework.
- **Verify the fork actually exhibits the bug on-device first.** Drag-dismiss, scrim tap, system back,
  and forward/replace all need checking before and after.
- 15 sheets bypass `ZashiScreenModalBottomSheet` entirely and will **not** inherit the fix: the chat
  sheets (`AttachmentSheet`, `SplitBillSheet`, `ChatRoomGroupInfoSheet`, `MediaAttachmentSheet`,
  `AddChatContactSheet`, `EditChatContactSheet`, `NetworkStatus`) plus `AddContactSheet` and
  `EditContactSheet` on raw Material. Roughly 8 to 10 need individual attention if they are nav-hosted;
  the two `ZashiInScreenModalBottomSheet` uses are legitimately out of scope.

**G15 · MOB-1473 curated swap assets.** Upstream `2dcfc5060`, `46b158640` add
`GetCuratedSwapAssetsUseCase` and a 29-pair `allowedAssets` allowlist with a `by lazy` cache on
`SimpleSwapAssetProvider`, thread curation through `FilterSwapBlockchainsUseCase`,
`GetABSwapContactsUseCase` and `GetPreselectedSwapAssetUseCase`, delete `GetSwapAssetsUseCase` and
`BlockchainProvider.getHardcodedBlockchains()`, and add a USDT0 drawable. About 10 source files, DI,
1 binary asset, 4 new test files. Forced adaptations, all confirmed:

- Upstream's commit touches `screen/pay/PayVM.kt`, which **does not exist in the fork**. The
  equivalent surface is `screen/unifiedsend/UnifiedSendVM.kt`.
- Deleting `GetSwapAssetsUseCase` cascades through 4 fork VMs (`SwapVM`, `SwapAssetPickerVM`,
  `SwapBlockchainPickerVM`, `UnifiedSendVM`) plus `UseCaseModule.kt:275`.
- The fork's `PreselectSwapAssetUseCase` is a structurally different predecessor of upstream's
  `GetPreselectedSwapAssetUseCase`: flow/`channelFlow` side-effecting with a ZEC default, versus
  upstream's suspend with a BTC default. Upstream's 6-line diff does not apply; this needs a rewrite
  or a hand-written curation `takeIf`.
- DI must use `singleOf`, not the fork's current `factoryOf` (`ProviderModule.kt:141`), or the lazy
  cache becomes per-injection.
- Decide whether to keep `getHardcodedBlockchains()`; its only consumer is the file this rewrites.

**G16 · MOB-1430 Android string-key unification.** Upstream `f298246ff`, `3a0c3a73e`, `a57fb9653`
(about 605 file-touches) renamed keys from snake_case to iOS-path-segmented lowerCamelCase and
collapsed 54 English `strings.xml` files into 4, relocating `ui-lib` feature strings into
`ui-design-lib`. The "dot-notation" label is misleading: Android resource names cannot contain dots,
and upstream HEAD has zero. Measured fork exposure on 2026-07-19:

| dimension | count |
|---|---|
| Fork keys colliding with upstream's renamed-away set | **644** (566 referenced in code) |
| `R.string.<key>` occurrences to update | **831** |
| Kotlin/Java files touched | **196** |
| Resource files to rewrite or consolidate | **288** (to ~20 if mirroring upstream's structure) |
| Zapp-specific keys with **no** upstream mapping | **917** |
| Translated values to migrate | **~7,250** |

Low risk is accurate: renames are compiler-verified and there is exactly **one** `@string/` reference
outside `strings.xml`. The risk concentrates in the 917 Zapp-specific keys absent from upstream's
`docs/mob-1430-master.csv`. Re-homing them requires naming judgment (does `chat_*` stay `chat_*` or
become `messaging_*`?) and each decision multiplies across 5 locale dirs; the chat block alone is
392 keys × 5 = 1,960 entries, 100% unmapped. A script driven by upstream's CSV handles the 644
mechanical cases; the Zapp tail is manual. Must be a dedicated PR touching nothing else. **This buys
diff hygiene against upstream, not functionality: it is deferrable indefinitely and should not block
anything above it.**

#### Optional, not gaps

- **MOB-1124 exchange-rate-unavailable bottom sheet.** The fork deliberately shows a lightweight
  `StyledExchangeUnavailablePopup` tooltip on the balance card (`StyledExchangeBalance.kt:189`)
  instead of upstream's full-screen `ExchangeRateUnavailable` sheet. `ExchangeRateState.error` is
  classified and consumed either way. Adopting upstream's sheet is a UX choice: 4 ZappTheme files and
  4 strings, triggered off the fork's `UnifiedSendArgs`/Request entries, not upstream's `Send()`-based
  `NavigateToSendUseCase`.
- **`CMCApiProviderTest.kt`.** MOB-1378's Tor-only CMC client shipped without upstream's test. Adding
  it is cheap coverage.

### 3.2 Standing deferrals | intentional omissions, not parity defects

Re-confirmed at every sweep since 2026-06-04. Record these in
[`docs/FORK_MAINTENANCE_GUIDE.md`](../FORK_MAINTENANCE_GUIDE.md) so future sweeps stop re-flagging them.

| id | omission | reason |
|---|---|---|
| **D1** | **Coinholder voting / governance.** The complete upstream feature: models, repositories, PCZT/Keystone signing, recovery, workers, screens, resources, configuration and tests, ~133 files. | Out of scope by standing decision. It is a large security-sensitive feature, not a missing shared plumbing patch. If product scope changes, adopt it as a separately reviewed project; do not cherry-pick isolated voting files. This also covers the Keystone per-scan-session QR decoder reset (`scanSessionId` / `resetDecoderForNewSession`, upstream `ParseKeystonePCZTUseCase.kt:97`), which arrived in the coinholder-polling commit `2f204beed`, and `SkipRemainingKeystoneBundlesUseCase*` despite its name. Note upstream 3.8.0 `86f79c07e` added a hard kill switch disabling shielded voting; still N/A here. |
| **D2** | **MOB-1144 automatic server selection.** Upstream's automatic endpoint provider/repository, fastest-server persistence, and the Automatic/Manual `ChooseServer` rewrite. Also `SynchronizerProvider.getSynchronizerOrNull()` and the `PersistEndpointUseCase` → `PersistServerSelectionUseCase` rename. | Manual-only server selection is the fork's deliberate privacy posture. The full UI port would also revert the ZappTheme `ChooseServerView`. G8 is independent of this and should still be done. |
| **D3** | **MOB-1145 Phase B/C.** Multi-endpoint broadcast (`MultiEndpointTransactionSubmitter`) and the Automatic/Manual `ChooseServer` surface. | Coupled to D2. Upstream shipped it in 3.7.0 gated on Automatic server selection, having previously reverted an earlier version in `e0c9cf154`. Keeping it out also correctly keeps `GrpcFailure.description`/`reason`, `GrpcFailure.pendingDescription()`, the `SendEmailUseCase` timeout copy, `TransactionProgressVM.pendingDescription` and `send_confirmation_pending_timeout_subtitle` out of the fork: they are driven by `reason=TIMEOUT`, set only by the Phase B submitter, so they would ship as inert dead code. If Phase B is ever adopted, pull the post-merge final blobs (`ef53bf7f8`, `c1ca39e9b`), not the initial `fc620ec51`. |
| **D4** | **Upstream release and Zodl infrastructure.** Linear release automation, F-Droid and Solana publishing, AWS GPG signing, the `release.yaml` overhaul, `create-release.yml`, `e2e-smoke.yml`, upstream Crashlytics wiring, changelogs, whatsNew, fastlane and Play metadata. | The fork has a separate, manual release process and product identity. Review CI fixes selectively only when they apply to Zapp's own workflows. The Firebase deltas in `crash-android-lib` (10 files) are the fork's intentional Crashlytics strip, not drift. MOB-1361 (Firebase collection-off-until-consent manifest flags) has no payload to gate here: the fork has no Firebase deps and no `google-services.json`. |
| **D5** | **`RestoreTor` screen.** Deleted deliberately in `448e39414`; Tor is forced on at `WalletRepository.kt:239,284`. | Do not port it back. G7 covers only the two things that deletion dropped by accident. |
| **D6** | **Upstream UI skin and product copy.** Zashi theme tokens, upstream screen layouts, branding, screenshots, copy, screen renames. `RoundedCornerShape(0.dp)` versus upstream's `12.dp`/`8.dp`/`20.dp` is Swiss design. | Not parity defects when the underlying state transitions and data flow are equivalent. Normalize these away during review instead of trying to make the fork visually identical. |
| **D7** | **`values-in` + `values-b+id` duplication.** | Looks like double-maintenance but is deliberate and aapt-verified: Android <= 14 resolves the legacy `in` code, 15+ resolves modern `id`. Both must ship. |
| **D8** | **Ktor `HttpTimeout` guard for Tor clients** (upstream `#2255` `supportsKtorTimeouts()`). | Dormant, not missing. The fork installs no `HttpTimeout` plugin, so the bug does not exist today, and the voting half (`VotingApiProvider`) has no fork analog. A gotcha comment already sits in `HttpClientProvider.configureHttpClient()` (PR #48). Implement the functional guard only if a timeout plugin is ever installed. |
| **D9** | **Assorted small upstream refactors:** restore-subtree verbatim renames, the design-lib widget batch, the M3 tooltip, MOB-1315, and the `settings.gradle` `zcash-sdk-backend` version-catalog entry. | Recorded in the 2026-06-10 deferral ledger and never re-raised. Take them only as part of a wider design-lib pass. |
| **D10** | **Slipstream / Orchard-migration feature branches.** | Not on any upstream release branch. 584-file work in progress. Wait for upstream to ship. |

### 3.3 Housekeeping

| id | item | note |
|---|---|---|
| **H1** | `.github/workflows/release.yaml:166` still carries a `publish_to_solana` block inherited from upstream that does not reflect the fork's manual Play process. The 2026-06-21 sweep also flagged dead `gcloud-kms` and `Build-FOSS-F-Droid` remnants in the same file. | Confirm with the release owner before pruning. |
| **H2** | `test-lib/src/main/kotlin/co/electriccoin/zcash/test/ZcashUiTestRunner.kt` drops upstream's `wakeLock.acquire()` (upstream has it at `:25`). The fork still creates the wake lock (`:24`) and releases it (`:30`) but never acquires it. | Test-harness only, but unexplained. Worth a glance. |
| **H3** | `BOUNCY_CASTLE_VERSION=1.78.1` is fork-only (offramp and EVM crypto), never existed upstream, and is old. | Outside parity scope. Handle in a separate dependency-CVE pass. |
| **H4** | **Fork CI cannot gate parity work.** `validate_gradle_wrapper` (`pull-request.yml:32`, `deploy.yml:31`) fails at runner setup on every branch and skips the downstream static-analysis and test jobs. | Infrastructure, not source: it cannot be fixed from this repo. Validate ktlint, detekt and compilation locally. |
| **H5** | The 2026-06-21 and 2026-07-11 reviews existed **only inside a git stash** (`f201c774f`, "tmp before main release aab", 2026-07-15) and were never committed. They were one `git stash drop` from being lost, and were restored to `docs/audits/` on 2026-07-19. | Their content is preserved here. This is the reason parity findings now live in one committed file. |

### Footnotes | contradictions between source documents, resolved

[^1]: **G6, bottom-sheet parameters.** The 2026-07-19 review states "the fork has none of them and
hardcodes shape at `ZashiModalBottomSheet.kt:53`". Verified against live source on 2026-07-28:
`properties` and `containerColor` **are** present at `:44-45`. Only `sheetGesturesEnabled` is missing
and `shape` is still hardcoded (now at `:54`). Resolution: the item is partially closed; the remaining
scope is two parameters, not four. The 2026-07-11 framing of this as "bottom-sheet navigation
animation" was also imprecise: parameter widening (G6) and the scrim rework (G14) are separate items.

[^2]: **G12, Keystone reachability.** Three documents disagree. 2026-06-10 item 2 and 2026-07-11 both
say the Keystone connect **tutorial entry point** is unreachable and prescribe a 2-file fix. That was
ported on 2026-06-10 (`fb212c064`) and then **refuted** by the 2026-07-19 sweep, which verified the
info-icon path is fully wired (`ConnectKeystoneView.kt:46-53` → `ConnectKeystoneScreen.kt:22-24` →
`WalletNavGraph.kt:295`) and matches upstream, including the commented-out inline link which is
commented out upstream too. Resolution: the tutorial-entry gap is **closed and retired** (R1). What
replaced it is a different and larger finding: the flow that contains the tutorial is unreachable at
its root. That is G12, and it is not the same item despite touching the same screen.

[^3]: **G14, MOB-1449 direction.** The 2026-07-11 review describes upstream as *adding* "shared
route/sheet state and animation resources". The 2026-07-19 verification found the opposite: upstream
**deletes** `SheetStateManager.kt` and `util/LocalNavRoute.kt` and replaces them with a platform
window-animation approach. Resolution: 2026-07-19 is correct and is what the table records. This
matters because it turns the item from "add resources" into "decide whether to retire the fork's own
`SheetStateManager`".

[^4]: **SDK pin, four different targets.** 2026-06-04 called for 2.4.8 → 2.6.1-SNAPSHOT; 2026-06-10
for 2.6.2 (`216ae477`); 2026-06-21 for 2.6.4; 2026-07-19 for 2.6.5. All four are superseded. The fork
is on `2.8.0-rc.1-SNAPSHOT` (provenance SHA `a003bd83`), which is exactly what upstream 3.8.0 ships,
and it now resolves from Maven snapshots rather than `includeBuild`, also matching upstream. The
2026-06-04 instruction "do NOT blank `SDK_INCLUDED_BUILD_PATH` like upstream did" no longer applies:
the fork deliberately blanked it on 2026-07-28 to match upstream. See section 4, 2026-07-28.

[^5]: **fastestEndpoints, two distinct rewrites.** The 2026-06-07 reconciliation line "fastestEndpoints
rewrite RESOLVED" refers to the *older* rewrite, ported in PR #60. Upstream's `ef77228a2` is a
*second*, different rewrite (single linear flow chain, previous-result cache hoisted to a class field
so it survives `WhileSubscribed` restarts, `refreshFastestServers()` using `.value` instead of
`first()`). Resolution: both are ported. The second landed as `696600982` on 2026-06-10.

---

## 4. Closed / ported ledger

One line per landed unit, oldest first. This is where the bulk of the five source documents collapses.

| date | what landed | ref |
|---|---|---|
| 2026-06-05 | **Upstream sync Batch 1** (3.3.1 → 3.5.2 correctness/security): P0-3 exact-zatoshi conversion for swap proposals (`#2198`, `a2b2a5075`) plus `RequestSwapQuoteUseCaseTest` (4 cases); P1-1 exchange rate falls back to the synchronizer on **any** CMC error, not just 5xx (MOB-1195, `e521bbcd4`); P0-4 default server reordered to zec.rocks first, mainnet only, testnet cipherscan untouched (MOB-1322, `d660e3780`); P1-2 `zcash_is_testnet=true` `bools.xml` for `zcashtestnetInternalDebug`/`Release` (`#2209`); P2-1 Keystone import calls `resetSynchronizer()` (`#2221`); P1-5 three fixes from `#2192` (`TransactionRepository` pending-while-syncing, NEAR asset `distinctBy`, `WebBrowserUtil` `runCatching` on both `launchUrl` calls). | PR #46 |
| 2026-06-05 | **Batch 2**, dependency bumps: Ktor 3.1.3 → 3.4.0 (P1-4, transport for `offramp-lib`/`evm-lib`/`sdk-ext-lib`, no API breaks); Material3 1.3.1 → 1.4.0 and Flexa 1.1.2 → 1.1.3 (P2-2), which required upstream's MOB-1111 adaptations in `ZashiModalBottomSheet.kt` and `TopAppBarColors.kt`. Did not touch `ZCASH_SDK_VERSION`, `JACOCO_VERSION`, `ZCASH_VERSION_NAME` or `BOUNCY_CASTLE_VERSION`. | PR #47 |
| 2026-06-05 | **Batch 3**: INV-1 Pay/Swap/Send accessibility content descriptions, wiring two orphaned strings back up in `ExactInputVMMapper.kt` and `UnifiedSendViewModel.kt` (`#2233`, authored upstream by the fork owner); P1-3 Tor-timeout gotcha comment in `HttpClientProvider.configureHttpClient()`. | PR #48 |
| 2026-06-06 | **Batch 4**, the required network-compatibility bump: SDK 2.4.8 → **2.6.1-SNAPSHOT** built from the SDK's `release/snapshot-v2.6.1` branch, pin `27af78d3`; `MockSynchronizer.kt` (androidTest) updated to the new `Synchronizer` interface (`rescanFromHeight` → `rewindToHeight`, plus `fullyScannedHeight`, `getTreeState`, `getWalletDbPathForVoting`). Production code needed **zero** changes. Verified with a full `:app:assembleZcashtestnetFossDebug`. | PR #49 |
| 2026-06-07 | Upstream parity: network timeouts, exchange-rate privacy, MOB-1336 decommissioned-server migration (`09cc1cb15`, including `getDecommissionedHosts()`, `migrateDecommissionedEndpointIfNeeded()`, `walletRepository.init()`), memtagMode, HttpTimeout/log-chunking, the exchange-rate `?: true` revert. | PR #50 |
| 2026-06-07 | MOB-1122 swap `FLEX_INPUT` mode, including `SWAP_INTO_ZEC`. Reverses the 2026-06-04 INV-4 "likely SKIP" recommendation. | PR #51 |
| 2026-06-07 | MOB-1139 resync feature restored, including the UI entry point. Reverses the 2026-06-04 INV-6 deferral. | PR #52 |
| 2026-06-07 | Keystone birthday import + `KeepOpen` flow and the home-nav switch. | PR #53 |
| 2026-06-07 | Tier 1: revive dead-code partial ports. | PR #54 |
| 2026-06-07 | Tier 2: MOB-987 locale decimal formatter, and the `ZatoshiExt` orphans removed. | PR #55 |
| 2026-06-07 | Tier 3: Keystone birthday sub-flow screens (MOB-371). | PR #56 |
| 2026-06-07 | Tier 4: Disconnect/ResetZashi Lce migration and silent-failure fix (MOB-371), plus `LceState`/`VmHelpers` infra, `ParseKeystonePCZT` → `KeystoneSDKProvider`, `WalletAccount` `requireNotNull`, biometric cancel-mapping. | PR #57 |
| 2026-06-08 | Content-verify parity: `WalletRepository` (the first fastestEndpoints rewrite, plus suspend `updateWalletEndpoint`), resync `LceState`, `RestoreTor` failure surfacing (`1c3bea48a`). | PR #60 |
| 2026-06-10 | **MOB-1346 log redaction.** Ktor body logging gated to `if (BuildConfig.DEBUG) LogLevel.ALL else LogLevel.NONE` and header sanitization extended from `Authorization`-only to a `SANITIZED_HEADERS` set adding `X-CMC_PRO_API_KEY` and `X-Helper-Token` (`HttpClientProvider.kt:100,101,146`), plus the `-assumenosideeffects class android.util.Log` rule in `spackle-android-lib/proguard-consumer.txt:15`. The shared client carries the CMC API key and every NEAR quote and offramp-funding body, so this was the top-priority item in that delta. | `971249509` |
| 2026-06-10 | **MOB-1340/1345 fail-closed swap quote validation.** Concrete quote classes moved to `near/` with `init` `require()`s (asset-id echo match, positive formatted amount, raw-versus-formatted consistency at asset decimals, client-snapshotted slippage-tolerance echo, slippage floor/ceiling versus the server's worst-case guarantee), the `validateQuote` use-case layer (user-amount echo, asset snapshot, destination and refund address echo with `requestNextShieldedAddress` hoisted pre-suspension), and the `isTerminal` fix that stops status polling on FAILED/EXPIRED. Ported as one unit from final upstream blobs including `4022784dd`, without which every real quote is rejected. Fork extras: validation added to the fork-only `requestExactInputIntoZec` entry point, and equivalent echo checks at the `OfframpBridgeWallet` call sites that bypass the use-case layer. | `f592df524`, `e9286bf9f` |
| 2026-06-10 | MOB-1190 `OptInExchangeRateAndTorUseCase`: declining Tor no longer force-disables currency conversion. | `44ec53a58` |
| 2026-06-10 | Keystone connect tutorial entry point wired. | `fb212c064` |
| 2026-06-10 | Restore-date migrated to `LceState`. | `72c7d8785` |
| 2026-06-10 | Upstream's second fastest-server coroutines rewrite (`ef77228a2`), keeping the fork's `walletProvisioningError` hooks. See footnote [^5]. | `696600982` |
| 2026-06-10 | SDK pin 2.6.1 → **2.6.2-SNAPSHOT** (`216ae477`). | `aa7333ff9` |
| 2026-06-18 | **MOB-1124 multi-currency / local-currency conversion** and **MOB-1378 fetch exchange rates over Tor only**. Faithful adoption of upstream's picker `State`/`VM`/`Screen`, `ExchangeRateRepository` core, `classifyExchangeRateError`, `shouldFallBackToSynchronizerRoute`, `PreferredFiatProvider`, `NavigateToSelectFiatCurrencyUseCase`, `OptInExchangeRateUseCase(optIn, fiat?)`, DI and strings with full es parity, reskinned to ZappTheme. Host-matched Tor-only CMC client with fail-loud clearnet refusal. No SDK bump required. | PR #75 |
| 2026-06-21 | Open-source readiness: dual MIT OR Apache-2.0 licensing for Zapp-original code, `NOTICE`, `LICENSE`, `LICENSE-MIT`, `LICENSE-APACHE`. | PR #77 |
| 2026-06-22 | **Upstream 3.6.0 quick-wins.** MOB-1398: delete the manual `SdkSynchronizer.close()` in `WalletRepository.persistWalletInternal` that raced a Synchronizer replacement, plus its two dead imports. MOB-1384: `ndk { abiFilters }` limiting the release APK to `arm64-v8a` and `armeabi-v7a`, DEBUG untouched for the CI x86 emulator. MOB-1135: verbatim 4-file swap to zxing-cpp 3.0.2 in the **foss** `QrCodeAnalyzerImpl.kt`, keeping `libs.zxing` for `JvmQrCodeGenerator`. | PR #78 |
| 2026-06-22 | **MOB-1376 mask recovery phrase until reveal**, plus the accessibility follow-up `fa71afa50`. Upstream's patch touches only `ui-design-lib` `ZashiSeedText`/`ZashiSeedWordText`, which are **dead preview-only code in the fork**; the real leak was in two fork-rewritten ZappTheme grids, `WalletBackupView.kt` and `onboarding/MessagingIdentityView.kt`, whose `SeedGrid` rendered the mnemonic in plaintext under a cosmetic blur. Both the parity port and the real fix landed, with `general_masked_seed_word`/`general_hidden_seed_word` in en and es. The biometric reveal gate already existed and was correct. | PR #79 |
| 2026-06-22 | **MOB-1145 Phase A, redaction only.** `ProposalDataSource` gRPC-status redaction in the partial-failure support email. Scope was refined during the port: the `GrpcFailure.description`/`reason` fields, `SendEmailUseCase` timeout copy, `TransactionProgressVM.pendingDescription` and `send_confirmation_pending_timeout_subtitle` are all driven by `reason=TIMEOUT`, set only by the deferred Phase B submitter, so they were folded into Phase B rather than shipped as dead code. | PR #80 |
| 2026-06-22 | Strip fork-added comments from diverged files, reducing merge noise. | PR #82 |
| 2026-06-25 | **MOB-1370/1371 NEAR swap quote hardening.** Hand-merged 3 source files plus 2 fork tests around the fork's `timeEstimate`/`flexInput`/`referral` divergence. Makes `minAmountIn`/`minAmountOut` required, throws `SwapAmountInconsistencyException`, and emits a sanitized `SwapAmountConsistencyRejectedSignal` via `GlobalCrashReporter`. | PR #81 |
| 2026-07-12 | **MOB-1356 amount parsing and decimal separators.** Fork commits `fdea403c9`, `30d4e1476`, `2f366ef23`, `e2d7b4415`, `428d77be1`. Core files byte-identical to upstream's final state and the 22-test parser suite is present and wired. This also closed the long-standing 2026-06-04 INV-3 locale-formatter item: `CurrencyFormatterExt.kt` now lives at `ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/util/` with an androidTest suite. | PR #113 |
| 2026-07-24 | Swap: end the quote request on a missing asset and report the real mode. A quote request published `Loading` then returned early before entering the `try`, so the catch never ran and `RequestSwapQuoteUseCase` hung; and `requestExactInputIntoZec`'s three error sites reported `EXACT_INPUT` while success reported `FLEX_INPUT`. Owns `SwapRepository.kt`; PRs #129, #130 and #142 rebased on it. | PR #125 |
| 2026-07-24 | **D2D Keystore corruption crash fix** (upstream `ca3399acc`, mis-titled "MOB-1452: Mocked interface impl for existing wireframes"). On Android 16 a device-to-device transfer copies the `EncryptedSharedPreferences` file but not the hardware-bound Keystore key, so every launch threw `AEADBadTagException`. Catch it, wipe the orphaned prefs and Keystore entry, recreate fresh so the user can restore from seed. Launch-blocking crash fixed by one file plus `EncryptedPreferenceProviderTest.kt` additions. | PR #126 |
| 2026-07-24 | Restore-success resource residue: deleted the 5 locale `strings.xml` under `ui-lib/src/main/res/ui/restore_success/`. Two corrections found during implementation and honored: **kept the drawables**, because `img_success_dialog` is unreferenced in the fork but upstream still uses it at `KeepOpenView.kt:60`; and **repaired rather than deleted** the screenshot assert, repointing `ScreenshotTest.kt:277,284` at the fork's `KeepOpenFlow.RESTORE` button (`general_got_it`) instead of the deleted `restore_success_button`, matching upstream's own repoint to `restoreInfo_gotIt`. | PR #127 |
| 2026-07-24 | Swap status ticker-fallback lookup (upstream `e6afe328a`). 1Click normalizes asset ids for routing, so the id echoed back in a status response need not string-match the one sent; the exact-`assetId` lookup threw `TokenNotFoundException` and killed BTC status polling. Falls back to the ticker embedded in the normalized id. | PR #129 |
| 2026-07-24 | Swap fails closed when a swap has no stored metadata, and when supported assets are unusable (upstream `866ec4741`). The status poll had verified returned assets through `expectedMetadata?.origin?.let { }`, so a null lookup skipped both checks and polling continued writing unverified server data back into metadata with nothing shown to the user. Took the **structural** option: the asset-refresh bail-out no longer passes a null error through, falling back to an explicit exception. | PR #130 |
| 2026-07-24 | **MOB-1130 sync banner blocks-remaining threshold** (upstream `759c77138`, `4007945d2`). `blocksRemaining` added to `WalletSnapshot`, derived in `WalletSnapshotDataSource` from `networkHeight − fullyScannedHeight`, and `GetHomeMessageUseCase`'s `progress >= 98f || progress == 0f` heuristic replaced with `blocksRemaining < SYNCING_BANNER_HIDE_BELOW_BLOCKS` (3456L). Logic extracted to a testable top-level `syncingMessageFor(...)`, with the 9-test threshold suite. Behavioral correctness fix: percentage misleads when chain height moves. | PR #141 |
| 2026-07-24 | **Swap quote asset-id echo check removed**, matching upstream `ec0301df9` then `641644993`. 1Click rewrites echoed asset ids for routing (`nep141:btc.omft.near` → `1cs_v1:btc:native:coin`), so the exact-match guards in `NearSwapQuote.init` rejected every valid ZEC→BTC quote with "Quote Unavailable". Both guards deleted, the two fork tests asserting them removed, and stale comments at `OfframpBridgeWallet.kt:313-315` and `RequestSwapQuoteUseCase.kt:262-271` corrected. `NearSwapQuote.init` is now byte-identical to upstream. See R5 for the wrong compensating control this item originally called for. | PR #142 |
| 2026-07-28 | **Ironwood (NU6.3) compatibility.** SDK bumped to `2.8.0-rc.1-SNAPSHOT` and switched to Maven-snapshot resolution with `SDK_INCLUDED_BUILD_PATH` blank, matching upstream 3.8.0 exactly; `.zapp-deps` records provenance SHA `a003bd83`. Ironwood pool balances folded into `AccountDataSource`'s `UnifiedInfo`, which previously read `.orchard` alone and would have made post-activation incoming shielded funds invisible in every displayed balance and unspendable via `canSpend()`. Keystone firmware >= 3.0.1 required for signed transactions (MOB-1510): NU6.3 introduces v6 transactions and older firmware produces signatures that fail extraction with `MissingSpendAuthSig` after the user has already approved on-device; the firmware stamp is parsed off the signed PCZT and normalized from the device's raw internal numbering. Mainnet activation is block 3,428,143. | PR #144 (closed; commits `c3bd763d7`, `b963a33b0`, `8036dc1e9` landed on `main`) |
| 2026-07-28 | **Ironwood UI** (MOB-1534): the one-time announcement screen and the per-pool balance breakdown, ported from upstream 3.8.0 and reskinned to Zapp. Follow-ups: enlarged the breakdown tap target from ~14dp to the 48dp minimum and corrected the guide link (PR #146); moved the announcement `LaunchedEffect` from `composable<TabsArgs>` so it holds until onboarding is done, since `ZappTabsScaffold` renders the welcome gate, `ZappOnboardingFlow` and `ZappRestoreFlow` inside that same destination and the fork has no upstream-equivalent `HomeArgs` (PR #147). | PRs #145, #146, #147 |

### Verified already-present at the 2026-06-04 sweep, no action taken

These upstream changes were independently present in the fork and needed no port: `#2200`/`#2194`
(MOB-1103 correct-account and synchronous fresh shielded address); `#2196` (MOB-982 Tor improvement,
no `PersistableWalletTorProvider`); `#2197` (MOB-1086 clipboard `EXTRA_IS_SENSITIVE` with API-34
guard); `#2180` (MOB-987 `StringResource` `maxDecimals`/`includeGroupingSeparator`/
`TickerLocation.HIDDEN` API surface); `#2195` (MOB-1110 internal-flavor MLKit QR).

---

## 5. Retired / misdiagnosed

Gaps that turned out not to be gaps. **Preserving these is the point:** it stops the next sweep
re-finding them.

| id | claim | verdict |
|---|---|---|
| **R1** | "Keystone connect tutorial entry point is registered but unreachable" (2026-06-10 item 2, 2026-07-11). | **REFUTED** on 2026-07-19. The info-icon → explainer path is fully wired (`ConnectKeystoneView.kt:46-53` → `ConnectKeystoneScreen.kt:22-24` → `WalletNavGraph.kt:295`) and matches upstream; the commented-out inline link is commented out upstream too. The initial 2026-06-10 reconciliation had also mistaken the long-present MOB-1100 error-path decoder reset for a different Keystone feature. Superseded by G12, which is a different finding. See footnote [^2]. |
| **R2** | "Delete the dead `restore-success` package; it is superseded by `KeepOpen`, still shipped and still registered." (2026-06-10 item 5, 2026-07-11). | **REFUTED.** The route was already deleted, inherited from upstream `b17af3e80`. The DI registration at `UseCaseModule.kt:252` is **live**: `HomeVM.kt:66,100,132-133` consumes it. Do **not** delete `IsRestoreSuccessDialogVisibleUseCase` or that registration. Only resource residue remained, closed by PR #127. |
| **R3** | "`NearSwapAsset` value semantics and missing metadata are explicit upstream; the fork lacks the model" (2026-07-11). | **DONE BY DIVERGENCE.** The fork replaced the model with its own `SwapAsset` sealed interface (`DynamicSwapAsset`/`ZecSwapAsset`), which already has the flat no-dto-backing property upstream's `866ec4741` was establishing. |
| **R4** | "MOB-1356 must be applied across send, request, swap and the offramp amount field" (2026-07-11). | **PARTLY N/A.** The offramp field needed no work: `ZappOfframpHeroAmountField` renders the shared `ZashiNumberTextField`, so the fix reached it for free. The fork's send path deleted `AmountState`/`ZecSendExt` entirely and carries a parsed `BigDecimal` rather than round-tripping a locale-formatted string, so the upstream bug class is structurally impossible there. Skipping those upstream hunks was correct, not an omission. |
| **R5** | "Add an explicit `requireMatchingAsset` at both `OfframpBridgeWallet` bypass sites to compensate for the deleted quote asset-id guard" (2026-07-19 item 15, step 1). | **WRONG, and recorded so the next auditor does not repeat it.** Both bypass sites (`openBridge`, `NearPullbackOfframpRefund.pullbackTarget`) reach 1Click through `requestQuote`, which builds `NearSwapQuote` from the caller's *own* `originAsset`/`destinationAsset` objects, so `requireMatchingAsset(quote.originAsset, …)` there compares an object against itself and can never fail. Nor was a control needed: the deleted guard compared server-controlled `response.quoteRequest.originAsset` against our own request, so a server substituting assets would simply echo back what we sent. It caught a confused server or a client bug, not an adversary. The fund-safety invariants at both bypass sites are unchanged (`requireQuoteMatchesUserAmount`, `requireMatchingAddress` on destination and refund addresses, and the amount/slippage checks still in `NearSwapQuote.init`), and the status path keeps the real server-echo check via `findAssetByEchoedId` plus `requireMatchingAsset` against stored metadata. **General lesson: do not "finish" a fund-safety item by deleting validation.** |
| **R6** | "MOB-1449 adds a shared nav-state mechanism" (2026-07-11). | **INVERTED.** Upstream *deletes* `SheetStateManager.kt` and `util/LocalNavRoute.kt`. See footnote [^3]. |
| **R7** | "Upstream SHA-pins GitHub Actions and the fork does not" (2026-06-10 candidate). | **ADVERSARIALLY REFUTED.** Upstream pinned only two actions, in a workflow the fork does not have, and still tag-pins the same actions the fork tag-pins. |
| **R8** | "The fork systematically lacks shared `ui-lib` unit tests" and "`SkipRemainingKeystoneBundlesUseCaseTest` has an unported 2-line delta" (2026-06-10 candidates). | **REFUTED.** Upstream's 25 tests versus the fork's count differ almost entirely by 21 voting tests plus 3 swap tests that were ported with MOB-1340/1345. The `SkipRemainingKeystoneBundles*` use case is part of the voting feature despite its name. |
| **R9** | "Flexa submit-success handling and proposal log redaction are missing" (2026-06-07 SUPPLEMENT §4). | **FALSE POSITIVES.** Upstream *reverted* the server-broadcast feature in `e0c9cf154`, so those files are byte-identical to upstream again. |
| **R10** | "MOB-1336 decommissioned servers is new in the 2026-06-10 delta." | **ALREADY PORTED** on 2026-06-07 as `09cc1cb15`, taken from the `release/3.5.3` branch before upstream backmerged it. It only looked new because the backmerge post-dated the prior audit point. |
| **R11** | "Upstream bumped JaCoCo, follow it." | **DO NOT.** Upstream went 0.8.15 → 0.8.14, a downgrade. The fork is already at 0.8.14. |
| **R12** | "Multi-server transaction broadcast (`#2223`) is a gap." | **Retired then reopened as a deferral.** It was fully reverted upstream in `e0c9cf154` with cleanup in `871c9fed3`, and the fork never had it. Upstream re-landed it in 3.7.0 gated on Automatic server selection, so it now lives as D3, coupled to D2. Do not port it standalone: it has privacy fan-out implications for a privacy-focused fork. |
| **R13** | "Replace the hardcoded `0xFF34C759` in `RestoreTorView.kt:154`" (2026-06-10 LOW polish batch). | **MOOT.** The whole `screen/restore/tor/*` subtree was deleted in `448e39414` when Tor became forced-on. No such file exists. |
| **R14** | "The fastestEndpoints rewrite is already resolved" versus "the fastestEndpoints rewrite is an open P2". | **BOTH TRUE, different rewrites.** See footnote [^5]. Both are ported. |
| **R15** | The 2026-06-21 sweep's first pass reported MOB-1124 and MOB-1378 as open. | **STALE-BASE ARTIFACT.** It ran against `main` at `69924550d` because `justzappit` had not been fetched; the fork's `main` tracks `justzappit/main`, not `origin` or `fork`. After `git fetch justzappit`, `main` was `285a313c5` and both items were already merged via PR #75. Every other finding in that sweep was re-verified against `285a313c5` and was unchanged. **Always fetch `justzappit` before comparing.** |
| **R16** | "MOB-1361 Firebase collection-off-until-consent flags are missing." | **N/A.** The fork has Crashlytics and Analytics fully stripped: no deps, no `google-services.json`, `FirebaseInitProvider` removed. There is no payload to gate. |
| **R17** | "INV-4: the swap `FLEX_INPUT` refactor should probably be skipped" (2026-06-04) and "INV-6: resync should be deferred" (2026-06-04). | **BOTH REVERSED and ported**, as PR #51 and PR #52 on 2026-06-07. Recorded because the 2026-06-04 recommendation still reads "likely SKIP" and "DEFER". |

---

## 6. Methodology and provenance

### 6.1 Method

Every sweep in the lineage used the same shape, refined over five iterations:

1. **Fetch and pin the comparison points.** Fork tip, upstream tip, previously audited upstream point,
   merge base. Record all four; a sweep whose base is wrong produces confident nonsense (R15).
2. **Classify mechanically before reading.** Blob-hash every changed file as old-upstream /
   new-upstream / diverged / absent in the fork. Only diverged and absent files need human analysis.
3. **Three-way hunk-level analysis** on the survivors, honoring the intentional divergences in section
   3.2 up front rather than rediscovering them per finding.
4. **Adversarial verification.** Each actionable finding passed two independent verifiers: does the
   upstream change exist as described, and does it apply to the fork as described. This is what caught
   R1, R2, R5, R6, R7, R8 and R9.
5. **Completeness critic.** Confirm every non-merge commit and every merge-resolution edit in the
   delta maps to a covered topic, so nothing is silently dropped.
6. **Evidence, not inheritance.** From 2026-07-19 onward, statuses are re-derived from live source at a
   cited `file:line` rather than carried forward from the previous document. Seven previously-listed
   gaps died on contact with that rule.

Agent counts across the lineage, for calibration: 63 agents (2026-06-10), 25 (2026-06-21).

### 6.2 Source-document provenance

All five are superseded by this file and are not published. The rows below record what each one
contributed and are retained for provenance only; the filenames are not links.

| document | date | fork ref compared | upstream ref compared | delta audited | what it uniquely contributed |
|---|---|---|---|---|---|
| `UPSTREAM-SYNC-2026-06-04.md` (425 lines, tracked) | 2026-06-04 | `main` @ `b6b96379a`, version 4.0.0, SDK 2.4.8 via `includeBuild` pin `88499ad` | `origin/main` @ `ea8d06ec6` = release **3.5.2** | 204 commits / 46 PRs across releases 3.4.0 → 3.5.2 | The batch plan (P0/P1/P2/INV tiering) that produced PRs #46-#49, the detailed SDK-bump procedure, the `MockSynchronizer` interface delta, and the SKIP list with reasons. Also the original INV items, two of which (INV-4, INV-6) were later reversed (R17). |
| `UPSTREAM-PARITY-2026-06-10-DELTA.md` (109 lines, tracked) | 2026-06-10 | `main` @ `8088ab070` | `origin/main` @ `fba96432a`, the **3.5.3 backmerge** | 45 commits / 30 files, `95762b4fc..origin/main`, plus re-verification of every open item from the 2026-06-07 audits | MOB-1346 and MOB-1340/1345, the two security ports that defined that train. The reconciliation of the 2026-06-07 carry-over list, the R1 decoder-reset correction, and the first written deferral ledger. |
| `UPSTREAM-PARITY-2026-06-21-3.6.0.md` (151 lines, **untracked**, recovered from stash `f201c774f`) | 2026-06-21 | `main` @ `285a313c5` after `git fetch justzappit` (first pass wrongly used `69924550d`) | upstream clone pulled to **v3.6.0**, `c826fa8bd` | `fba96432a..origin/main` = v3.6.0 | The 3.6.0 tiering that produced PRs #78-#81: MOB-1398, MOB-1384, MOB-1135, MOB-1145 Phase A, MOB-1376, MOB-1370/1371. The MOB-1376 finding that upstream's patch touches dead preview code while the real leak is in two fork-rewritten grids. R15, the stale-base lesson. |
| `UPSTREAM-PARITY-2026-07-11.md` (252 lines, **untracked**, recovered from stash `f201c774f`) | 2026-07-11 | `justzappit/main` @ `593ea2ec1` | `origin/main` @ `b4edd6f66` (2026-07-01), release 3.7.x line | Reachability survey: 442 fork-unique, 403 upstream-unique, 1,703 raw changed paths | The reachability measurements still cited in section 2, the first framing of the swap pipeline as needing an adapted port rather than a cherry-pick, and the "intentional omissions" taxonomy that section 3.2 inherits. Its MOB-1449 framing was later inverted (R6, footnote [^3]). |
| `UPSTREAM-PARITY-2026-07-19.md` (406 lines, tracked) | 2026-07-19 | `justzappit/main` @ `dff2c3033`, also verified against working branch `feat/you-tab-profile-first` (`709ecf7ad`) | `origin/main` @ `05cb52e89`, release **3.7.2** (tag `3.7.2-2009`) | 28 commits `b4edd6f66..05cb52e89`, of which four touch app code | The 21-item effort-ordered worklist that section 3.1 is built from, the seven retirements in section 5, the MOB-1430 exposure measurements, and the discovery that the two preceding reviews existed only in a git stash (H5). Method shift: five parallel readers re-verifying every carried-over item against live source. |

---

*Next sweep: compare `justzappit/main` against upstream `origin/main` once upstream merges
`release/3.8.0`, and re-check G1 through G16 against live source before reporting any of them.*
