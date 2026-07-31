# Zapp Android Fork | Comprehensive Maintenance Guide

> **Purpose**: Single reference for maintaining `android-zapp` (Zapp) relative
> to `zodl-android` (upstream Zodl/Zashi). Created 2026-05-20.
>
> **Golden rule**: `zodl-android` is READ-ONLY. All edits happen in `android-zapp`.

---

## Table of Contents

1. [Relationship Model](#1-relationship-model)
2. [Repository Layout](#2-repository-layout)
3. [Module Inventory | Upstream Baseline](#3-module-inventory--upstream-baseline)
4. [Complete Customisation Inventory | Fork](#4-complete-customisation-inventory--fork)
5. [File-Level Diff Summary](#5-file-level-diff-summary)
6. [Dependency Version Divergences](#6-dependency-version-divergences)
7. [Security Tier Classification](#7-security-tier-classification)
8. [Upstream Sync Procedure](#8-upstream-sync-procedure)
9. [Build & Verify Checklist](#9-build--verify-checklist)
10. [Conflict Hotspots](#10-conflict-hotspots)
11. [Removed Upstream Features](#11-removed-upstream-features)
12. [Quick-Reference Scripts](#12-quick-reference-scripts)

---

## 1. Relationship Model

```
zodl-android (upstream Zodl/Zashi)        android-zapp (Zapp)
====================================      ====================================
  Security-critical code           ──────►  Kept identical (Tier 1-2)
  SDK extensions, crypto           ──────►  Kept identical
  UI / branding / navigation       ───X──   Zapp's own UI (ZappTheme, 4-tab shell)
  Upstream-only features           ───X──   Not adopted (voting, keepopen, pay, heightinfo)
  Build config / signing           ──────►  Zapp overrides (package name, versions)
  New Zapp features                         Chat, fiat ramps, ZKP2P, balance chart
```

**Sync strategy**: Cherry-pick security/crypto file changes from upstream.
Never full-merge. UI modules diverge intentionally. See [Section 7](#7-security-tier-classification)
and companion doc `UPSTREAM_SECURITY_SYNC_GUIDE.md` for tier definitions.

---

## 2. Repository Layout

### Workspace structure (sibling repos required)

```
Documents/Zapp-Org/
├── zodl-android/             # Upstream: READ-ONLY reference
├── android-zapp/        # Zapp: all edits here
├── zappMessaging/            # P2P SDK (Hyperswarm/Autobase)
├── bare-kit/                 # BareKit JS runtime v2.0.0
└── zapp-ios/                 # iOS counterpart
```

Pinned sibling SHAs in `.zapp-deps`:
```
zappMessaging=6f6325cbb08a4d31b4fc5258b9d5c9f0ea019663
bare-kit=15a3569f3d704de7151761051c8d9cc2e299414f
zcashAndroidWalletSdk=27af78d334fd54041b6d2420dad711fb32df1bbf
```

### Fork directory structure (additions highlighted with ★)

```
android-zapp/
├── .zapp-deps                          ★ Sibling repo SHA pins
├── ZAPP_CHANGES.md                     ★ 8-commit patch series docs
├── DEVELOPER_SETUP.md                  ★ Build environment guide
├── docs/notifications/P2P-TRANSPORT-NOTES.md  ★ P2P transport and blind peer reference
├── design/                             ★ Zapp design assets (HTML, JSX, SVGs)
├── app/
│   └── src/main/res/xml/
│       └── network_security_config.xml ★ Cleartext disabled, CA pins
├── ui-lib/src/main/java/.../
│   ├── di/ZappMessagingModule.kt       ★ Chat DI wiring
│   ├── screen/chat/                    ★ Full P2P chat (30+ files)
│   ├── screen/tabs/                    ★ 4-tab shell
│   ├── screen/topup/                   ★ Receive-based Top Up chooser
│   ├── screen/swap/upi/                ★ p2p.me fiat off-ramp
│   ├── screen/unifiedsend/             ★ Unified send flow
│   ├── screen/welcome/                 ★ Welcome gate
│   ├── screen/securitysettings/        ★ Security settings
│   └── screen/restoresuccess/          ★ Restore success
├── ui-lib/src/main/res/ui/
│   ├── chat/                           ★ Chat strings
│   ├── offramp/                        ★ Off-ramp strings
│   ├── top_up/                         ★ Top Up strings
│   └── unified_send/                   ★ Unified send strings
├── ui-design-lib/src/main/java/.../
│   ├── theme/ZappTheme.kt              ★ Master theme
│   ├── theme/colors/ZappPalette.kt     ★ Colour tokens
│   ├── component/zapp/ZappComponents.kt ★ Component library
│   └── component/SparkChart.kt         ★ Balance history chart
└── docs/                               ★ Various Zapp-specific docs
```

---

## 3. Module Inventory | Upstream Baseline

Upstream Zodl v3.4.0 has **19 modules**:

| Module | Type | Purpose |
|--------|------|---------|
| `app` | Android App | Final application, Koin init, crash/analytics |
| `build-info-lib` | KMP (JVM) | Git SHA, commit count, release notes |
| `configuration-api-lib` | KMP (JVM) | Remote config interfaces |
| `configuration-impl-android-lib` | Android Lib | Android config implementation |
| `crash-lib` | KMP (JVM) | Common crash collection |
| `crash-android-lib` | Android Lib | Firebase Crashlytics integration |
| `preference-api-lib` | KMP (JVM) | Key-value preference API |
| `preference-impl-android-lib` | Android Lib | EncryptedPreferenceProvider |
| `sdk-ext-lib` | Android Lib | Zcash SDK extensions, Ktor, ZIP321 |
| `spackle-lib` | KMP (JVM) | Random utilities |
| `spackle-android-lib` | Android Lib | Android-specific utilities |
| `ui-design-lib` | Android Lib | Compose design system |
| `ui-lib` | Android Lib | All screens, ViewModels, navigation |
| `test-lib` | Android Lib | Test utilities |
| `ui-integration-test` | Android Test | Integration tests |
| `ui-screenshot-test` | Android Test | Visual regression tests |
| `ui-benchmark-test` | Android Test | Performance benchmarks |
| `buildSrc` | Build | Git utilities |
| `build-conventions-secant` | Included Build | Convention plugins (10 plugins) |

**Key upstream versions** (v3.4.0):
- Kotlin 2.3.10, AGP 8.13.2, Gradle 8.14.4
- Zcash SDK 2.5.0-SNAPSHOT, BIP39 1.0.9, ZIP321 1.0.2
- Compose 1.10.4, Material3 1.4.0, Ktor 3.4.0
- compileSdk 36, targetSdk 36, minSdk 27

**Product flavours** (upstream):
- Network: `zcashmainnet` / `zcashtestnet`
- Distribution: `store` / `foss` / `internal`

---

## 4. Complete Customisation Inventory | Fork

### 4.1 Branding

| Property | Upstream | Fork |
|----------|----------|------|
| App name | `Zodl` | `Zapp` |
| Package name | `co.electriccoin.zcash` | `xyz.justzappit.zapp` |
| Version name | `3.4.0` | `4.0.0` |
| FileProvider authority | `co.electriccoin.zcash.provider` | `${applicationId}.provider` |

### 4.2 New Modules (Gradle composite builds)

| Module | Source | Purpose |
|--------|--------|---------|
| `:zappmessaging` | `../zappMessaging/android` | P2P messaging SDK |
| `:bare-kit` | `../bare-kit/android` | Hyperswarm JS runtime (BareKit) |

Added in `settings.gradle.kts` with `file(...).exists()` guards for optional inclusion.

### 4.3 P2P Chat Integration

**DI**: `ZappMessagingModule.kt`. Registers `ZappMessagingSDK`, provides `ChatViewModel` via Koin.

**Screen tree** (`ui-lib/.../screen/chat/`):

| Directory | Key Files | Purpose |
|-----------|-----------|---------|
| `list/` | `ChatListScreen`, `ChatListVM`, `ChatListState` | Conversation list |
| `room/` | `ChatRoomScreen`, `ChatRoomVM`, `ChatRoomState` | Message thread |
| `contacts/` | `ChatContactsScreen`, `ChatContactsVM` | Contact picker |
| `profile/` | `ChatProfileScreen`, `ChatProfileVM` | Contact profile |
| `newconv/` | `NewConversationScreen`, `NewConversationVM` | New conversation |
| `identity/` | `ChatIdentitySetupScreen`, `ChatIdentitySetupVM` | Ed25519 identity setup |
| `contactedit/` | `ContactEditScreen`, `ContactEditVM` | Edit contacts |
| `scan/` | `ChatScanPublicKeyScreen`, `ChatScanPublicKeyVM` | QR scan-to-add |
| `settings/` | `ChatSettingsScreen`, `ChatSettingsVM` | Chat preferences |
| `media/` | `ImageProcessor`, `FileUtils`, `CameraCaptureState` | Media handling |
| `view/` | `MediaBubble`, `LocationBubble`, `TransactionBubble`, `AttachmentSheet` | Message UI |
| `model/` | `ChatModels`, `BlockedUser`, `ConnectionDetailsUi` | Data models |
| `repository/` | `ChatModerationRepository` | User blocking |
| `common/` | `ChatBootstrap` | SDK initialisation |

**Navigation**: `ChatNavGraph.kt`, dedicated sub-graph registered in `MainAppGraph`.

**Chat features**:
- E2E encrypted P2P over Hyperswarm DHT
- Ed25519 keypair from BIP-39 seed
- Message types: text, media, file, location, wallet address, payment request, transaction
- Network status pill in chat screens
- QR scan contact discovery
- Contact management (edit, delete, block)

### 4.4 Four-Tab Shell

Replaces upstream's single-screen home with a tabbed navigation shell.

| Tab | Content | Key Files |
|-----|---------|-----------|
| Wallet | Balance, send/receive/pay/swap, chart | `WalletTabContent.kt`, `WalletHomeView.kt` |
| Chats | P2P conversation list | `ChatNavGraph.kt` |
| Contacts | Contact list | `ChatContactsScreen.kt` |
| Settings | App preferences | `SettingsTabContent.kt` |

Files in `ui-lib/.../screen/tabs/`:
- `AndroidTabs.kt`: routes and args
- `ZappTabsScaffold.kt`: tab bar
- `FloatingPillNavBar.kt`: custom nav bar
- `WalletSyncStateVM.kt`: sync state

Navigation change: `RootNavGraph` starts at `MainAppGraph` (tabs always visible).
Removed `SecretState`-driven graph switching and `OnboardingNavGraph`.

### 4.5 ZappTheme Design System

**Design philosophy**: Swiss minimalist. No rounded corners, warm off-white backgrounds.

| Token | Value |
|-------|-------|
| Primary accent | `#FF9417` (orange) |
| Background | Warm off-white |
| Shapes | `RectangleShape` (flat) |
| Back button | Bottom-left (`ZappBottomActionBar`) not top-left app bar |

**New files**:
- `ZappTheme.kt`: master theme object
- `ZappPalette.kt`: colour tokens (light/dark)
- `ZappComponents.kt`: `ZappScreenHeader`, `ZappBottomActionBar`, `ZappBackButton`,
  `ZappRow`, `ZappRowDivider`, `ZappGroupHeader`, `ZappInputField`, `ZappChip`, `ZappButton`
- `SparkChart.kt`: Canvas-based balance history chart

**Rule**: Never use `ZashiColors`, `ZashiTypography`, or `RoundedCornerShape`.

### 4.6 Top Up and fiat off-ramp

Top Up is an internal receive flow. `TopUpVM` maps exchange transfers to the transparent-address
QR and transfers from another wallet to the unified-address QR. Home, balance, send, swap, and
insufficient-funds entry points all use the shared `TopUpArgs` dialog route.

Fiat cash-out is a separate p2p.me integration under `screen/swap/upi/` with its pure-JVM protocol
logic in `offramp-lib`.

### 4.7 Balance History Chart

- `GetBalanceHistoryUseCase`: running signed-delta series from `TransactionRepository`
- `SparkChart`: Canvas stroke + vertical gradient fill
- Period chips: 24h / 1w / 1m / All
- Hides on fresh wallet; shows "not enough activity" for empty periods
- Registered in `UseCaseModule.kt`

### 4.8 Flexa Payment Integration

- Feature-flagged via `IS_FLEXA_AVAILABLE` in `ConfigurationEntries.kt`
- `CreateFlexaTransactionUseCase`, `FlexaViewModel`
- Dependencies: `flexa-core:1.1.2`, `flexa-spend:1.1.2`
- Key: `ZCASH_FLEXA_KEY` (CI-injected)

### 4.9 Single-Network Build Switch

`gradle.properties`: `ZCASH_NETWORK=testnet`

`app/build.gradle.kts` `beforeVariants` block filters out the other network's variants,
so only `Zcashtestnet*` tasks are registered. Override:
```bash
./gradlew -PZCASH_NETWORK=mainnet :app:installZcashmainnetStoreDebug
```

Also emits `R.bool.zcash_is_testnet` from the `network` product flavour.

### 4.10 Seed Mismatch Recovery

`ZcashApplication.kt`, `installSeedMismatchHandler()`:
Global uncaught exception handler that catches `InitializeException.SeedNotRelevant`,
erases SDK data, and restarts. Prevents hard crash loops after seed restore errors.

### 4.11 Network Security Config

`app/src/main/res/xml/network_security_config.xml`:
- Disables cleartext traffic
- Pins system CAs
- Allows user CAs in debug builds

### 4.12 AndroidManifest Changes

- `<uses-sdk tools:overrideLibrary="xyz.justzappit.zappmessaging, to.holepunch.bare.kit" />`
  (minSdk 29 libs on minSdk 27 host)
- `android:networkSecurityConfig="@xml/network_security_config"`
- FileProvider authority: `${applicationId}.provider` (dynamic)
- Implicit permissions from deps: CAMERA, ACCESS_FINE_LOCATION

---

## 5. File-Level Diff Summary

### 5.1 Build config files | MODIFIED

| File | What Changed |
|------|-------------|
| `settings.gradle.kts` | +zappmessaging/bare-kit includes, +coil-compose/play-services-location libs, -zcash-sdk-backend lib, +exists() guards on included builds, -backend-lib from SDK dep substitution |
| `build.gradle.kts` (root) | App name `Zodl`→`Zapp`, package name change in `checkProperties` |
| `gradle.properties` | See [Section 6](#6-dependency-version-divergences) for all changes |
| `app/build.gradle.kts` | +`beforeVariants` single-network filter, +`zcash_is_testnet` resValue, comment text tweak |
| `ui-lib/build.gradle.kts` | +chat/top_up/offramp/unified_send res sets, -keep_open/voting, +coil-compose/play-services-location/zappmessaging deps, -zcash-sdk-backend/kotlin-test/ktor-client-mock |
| `app/src/main/AndroidManifest.xml` | +overrideLibrary, +networkSecurityConfig, +dynamic FileProvider authority |

### 5.2 Source files | MODIFIED

| File | Diff Size | What Changed |
|------|-----------|-------------|
| `ZcashApplication.kt` | ~30 lines | +`zappMessagingModule` in Koin, +`installSeedMismatchHandler()` |
| `AuthenticationViewModel.kt` | 166 lines | +PIN auth support, +`PinAuthGate`, +`OnboardingSecurityViewModel` interop |
| `WalletViewModel.kt` | 47 lines | +`currentSeedWords` StateFlow, +`createNewWallet()` method |
| `BiometricActivity.kt` | 14 lines | Import adjustments for Zapp theme/security |
| `RestoreSeedVM.kt` | 8 lines | Navigation to `WrapRestoreSuccessArgs` |

### 5.3 Upstream screens REMOVED from fork

| Screen | Reason |
|--------|--------|
| `voting/` | Not needed for Zapp |
| `keepopen/` | Not needed for Zapp |
| `heightinfo/` | Not needed for Zapp |
| `pay/` | Replaced by 4-tab shell |
| `common/` (screen-level) | Replaced by Zapp equivalents |

### 5.4 Upstream files REMOVED

- `app/src/main/res/xml/auto_backup_config.xml`
- `app/src/main/res/xml/auto_backup_config_android_12.xml`
- `.idea/runConfigurations/*.xml`

### 5.5 Convention plugins | IDENTICAL

`build-conventions-secant/` is **unchanged** between upstream and fork.

### 5.6 Shared modules | IDENTICAL

These modules have NO source differences (build files may differ):
- `sdk-ext-lib/build.gradle.kts`: identical
- `ui-design-lib/build.gradle.kts`: identical
- `preference-api-lib/`: identical
- `preference-impl-android-lib/`: identical
- `spackle-lib/` + `spackle-android-lib/`: identical

---

## 6. Dependency Version Divergences

| Property / Dependency | Upstream (v3.4.0) | Fork (v4.0.0) | Note |
|----------------------|-------------------|---------------|------|
| `ZCASH_VERSION_NAME` | 3.4.0 | 4.0.0 | Zapp versioning |
| `ZCASH_SDK_VERSION` | 2.6.1-SNAPSHOT | 2.6.1-SNAPSHOT | Aligned 2026-06-04; both build SDK from source (snapshot-v2.6.1) |
| `ANDROID_TARGET_SDK_VERSION` | 36 | 35 | Fork one behind |
| `ANDROIDX_COMPOSE_MATERIAL3_VERSION` | 1.4.0 | 1.3.1 | Fork one behind |
| `KTOR_VERSION` | 3.4.0 | 3.1.3 | Fork pins older Ktor |
| `FLEXA_VERSION` | 1.1.3 | 1.1.2 | Fork one behind |
| `JACOCO_VERSION` | 0.8.14 | 0.8.15 | Fork slightly ahead |
| `SDK_INCLUDED_BUILD_PATH` | (empty) | (empty) | Matches upstream; set in `local.properties` for a local SDK build |

**Fork-only gradle.properties entries**:
```
ZCASH_NETWORK=testnet
BLIND_PEER_KEYS=5ccrwsgqfg1hawwcbckmisww4sy3qns5scsntxfztgx7pt4eps5o
BLIND_PEER_BOOTSTRAP=140.245.193.100:49737
ZAPP_MESSAGING_LOG_LEVEL=debug
```

**Fork-only library declarations** (in `settings.gradle.kts`):
- `coil-compose:2.6.0`: image loading for chat
- `play-services-location:21.3.0`: location sharing in chat

**Removed from fork**:
- `zcash-sdk-backend` library declaration
- `backend-lib` in SDK dependency substitution

---

## 7. Security Tier Classification

Full details in `docs/UPSTREAM_SECURITY_SYNC_GUIDE.md`. Summary:

### Tier 1 | CRITICAL (sync within 48 hours)

| Module | Status |
|--------|--------|
| `sdk-ext-lib/` (14 files) | 2 diffs (`CurrencyFormatterExt.kt` missing, `ZcashNetwork.kt` modified) |
| `preference-api-lib/` (8 files) | IDENTICAL |
| `preference-impl-android-lib/` (4 files) | IDENTICAL |

### Tier 2 | HIGH (sync within 1 week)

All spending key, proposal, wallet, biometric, encryption, and key storage files.
Currently **all IDENTICAL** except `BiometricActivity.kt` (14 lines), and
`EncryptedPreferenceKeys.kt` (fork-only addition).

### Tier 3 | MEDIUM (sync within 2 weeks)

| File | Diff Size | Zapp Changes |
|------|-----------|-------------|
| `AuthenticationViewModel.kt` | 166 lines | PIN auth additions |
| `WalletViewModel.kt` | 47 lines | `currentSeedWords`, `createNewWallet()` |
| `RestoreSeedVM.kt` | 8 lines | Navigation to RestoreSuccess |
| `BiometricActivity.kt` | 14 lines | Import adjustments |

### Tier 4 | LOW (quarterly or skip)

`crash-*-lib`, `configuration-*-lib`, `build-info-lib`, `ui-design-lib` (Zapp has own theme),
`spackle-*-lib` (currently identical).

---

## 8. Upstream Sync Procedure

### 8.1 When to sync

- **Security patch in upstream** → Immediate (follow tier timelines)
- **New upstream release** → Evaluate, cherry-pick security changes
- **Dependency bumps** → Match critical deps (SDK, crypto, biometric)

### 8.2 Step-by-step

```bash
# 1. Check upstream changes since last sync
cd ../zodl-android
git log --oneline --since="YYYY-MM-DD" -- \
  sdk-ext-lib/ preference-api-lib/ preference-impl-android-lib/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/common/datasource/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/common/repository/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/common/serialization/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/RestoreWalletUseCase.kt \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/ValidateSeedUseCase.kt \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/common/viewmodel/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/BiometricActivity.kt \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/authentication/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/restore/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/deletewallet/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/scan/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/reviewtransaction/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/transactionprogress/

# 2. For each changed file, classify by tier
#    Tier 1-2 IDENTICAL files → direct copy
#    Tier 2-3 MODIFIED files → manual merge (preserve Zapp additions)

# 3. Apply: IDENTICAL files
cp ../zodl-android/sdk-ext-lib/src/.../SomeFile.kt \
   sdk-ext-lib/src/.../SomeFile.kt

# 4. Apply: MODIFIED files
# Copy upstream, diff, re-apply Zapp-specific changes:
#   AuthenticationViewModel.kt → re-apply PIN auth (search PinAuthGate)
#   WalletViewModel.kt → re-add currentSeedWords + createNewWallet()
#   BiometricActivity.kt → re-apply Zapp imports
#   RestoreSeedVM.kt → re-apply WrapRestoreSuccessArgs navigation

# 5. Verify (see Section 9)
```

### 8.3 Dependency version sync

When upstream bumps any of these, match in the fork:

| Dependency | Where |
|-----------|-------|
| `cash.z.ecc.android:zcash-android-sdk` | `gradle.properties` → `ZCASH_SDK_VERSION` |
| `androidx.security:security-crypto` | `settings.gradle.kts` version catalog |
| `androidx.biometric:biometric` | `settings.gradle.kts` version catalog |
| `com.google.crypto.tink:tink-android` | Transitive via security-crypto |
| `org.jetbrains.kotlin:kotlin-stdlib` | `gradle.properties` |

### 8.4 Gradle properties sync

When syncing, **preserve** these fork-only entries (do not overwrite):
```
ZCASH_RELEASE_APP_NAME=Zapp
ZCASH_RELEASE_PACKAGE_NAME=xyz.justzappit.zapp
ZCASH_VERSION_NAME=4.x.x
BLIND_PEER_KEYS=...
BLIND_PEER_BOOTSTRAP=...
```

**Never** carry a developer-only value into `gradle.properties`. These three must stay **blank**
in the committed file; a local value belongs in `local.properties`, which shadows it:
```
ZCASH_NETWORK=            # a flavor here breaks side-by-side installs and CI
ZAPP_MESSAGING_LOG_LEVEL= # 'debug' ships keypair dumps into release BuildConfig
SDK_INCLUDED_BUILD_PATH=  # blank resolves the SDK from Maven, matching upstream
```

**Evaluate** these. Upstream may bump them for good reason:
```
ANDROID_TARGET_SDK_VERSION    (fork: 35, upstream: 36)
ZCASH_SDK_VERSION             (fork: 2.6.1-SNAPSHOT, upstream: 2.6.1-SNAPSHOT, aligned 2026-06-04)
KTOR_VERSION                  (fork: 3.1.3, upstream: 3.4.0)
FLEXA_VERSION                 (fork: 1.1.2, upstream: 1.1.3)
ANDROIDX_COMPOSE_MATERIAL3_VERSION (fork: 1.3.1, upstream: 1.4.0)
```

---

## 9. Build & Verify Checklist

Run after every sync or significant change:

```bash
cd android-zapp/

# Clean (especially after switching between upstream and fork builds)
./gradlew --stop && rm -rf ~/.gradle/caches/build-cache-1 \
  build-conventions-secant/{build,.gradle,.kotlin} .gradle

# Build
./gradlew :app:assembleZcashtestnetStoreDebug

# Unit tests
./gradlew check

# Lint
./gradlew detektAll && ./gradlew ktlintFormat

# Lock dependencies (after dep changes)
./gradlew resolveAndLockAll --write-locks

# Verify security-critical files are identical to upstream
bash scripts/security-diff.sh   # See Section 12
```

**Environment requirements**:
- JDK 17 exactly (not 21+)
- NDK 27.0.12077973
- CMake 4.1.2+
- Android SDK: compileSdk 36, minSdk 27
- Gradle 8.14.4 (via wrapper)
- Sibling repos checked out at pinned SHAs (`.zapp-deps`)

---

## 10. Conflict Hotspots

When upstream changes land, expect conflicts in these files:

| File | Reason | Resolution Strategy |
|------|--------|-------------------|
| `settings.gradle.kts` | Zapp adds zappmessaging + bare-kit includes, extra libs | Keep Zapp includes, merge upstream lib version changes |
| `gradle.properties` | Zapp overrides app name, package, network, versions | Keep Zapp branding, evaluate version bumps |
| `app/build.gradle.kts` | Zapp adds beforeVariants filter, zcash_is_testnet resValue | Keep Zapp variant logic, merge upstream build changes |
| `ui-lib/build.gradle.kts` | Zapp adds/removes res dirs and deps | Keep Zapp res dirs + deps, merge upstream changes |
| `ui-lib/.../RootNavGraph.kt` | Zapp changes start destination to tabs shell | Keep Zapp navigation, port upstream route additions |
| `ui-lib/.../WalletNavGraph.kt` | Zapp adds chat/contacts/scan routes | Keep Zapp routes, merge upstream nav changes |
| `ui-lib/.../di/*.kt` | Zapp adds messaging DI | Keep Zapp DI, merge upstream module changes |
| `ui-design-lib/.../ZappPalette.kt` | Zapp-specific colour tokens | Keep as-is (Zapp owns this) |
| `ZcashApplication.kt` | Zapp adds messaging module + seed handler | Keep Zapp additions, merge upstream init changes |

---

## 11. Removed Upstream Features

These upstream features are **not exposed** in the fork UI. They are kept as commented
blocks prefixed `// DEAD CODE [hidden]:` to simplify future merges. Do NOT delete them.

| Feature | Upstream Location | Status in Fork |
|---------|------------------|---------------|
| Voting | `screen/voting/` | Directory removed |
| Keep Open | `screen/keepopen/` | Directory removed |
| Height Info | `screen/heightinfo/` | Directory removed |
| Pay tab | `screen/pay/` | Replaced by 4-tab shell |
| Settings > Advanced | Various | Commented out |
| Settings > About | Various | Commented out |
| Backup / Restore seed UI | Various | Commented out (gate on wallet existence) |
| Tor / Privacy settings | Various | Commented out |
| Tax export | Various | Commented out |
| Crash reporting prefs | Various | Commented out |
| Debug menu | Various | Commented out |
| Choose server | Various | Commented out |
| Keystone HW wallet promo | Account list | Removed |
| Auto-backup config | `res/xml/auto_backup_*.xml` | Removed |

---

## 12. Quick-Reference Scripts

### Security diff (save as `scripts/security-diff.sh`)

```bash
#!/bin/bash
UP="../zodl-android"
FK="."

echo "=== Tier 1: CRITICAL ==="
for mod in sdk-ext-lib preference-api-lib preference-impl-android-lib; do
  echo "--- $mod ---"
  diff -rq "$UP/$mod/src" "$FK/$mod/src" 2>/dev/null | grep -v ".gradle\|build/"
done

echo ""
echo "=== Tier 2: HIGH ==="
for f in \
  "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/datasource/ZashiSpendingKeyDataSource.kt" \
  "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/datasource/ProposalDataSource.kt" \
  "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/repository/WalletRepository.kt" \
  "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/repository/BiometricRepository.kt" \
  "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/repository/ZashiProposalRepository.kt" \
  "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/RestoreWalletUseCase.kt" \
  "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/ValidateSeedUseCase.kt" \
  "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/deletewallet/ResetZashiUseCase.kt" \
; do
  if [ -f "$UP/$f" ] && [ -f "$FK/$f" ]; then
    d=$(diff "$UP/$f" "$FK/$f" 2>/dev/null | wc -l)
    if [ "$d" -eq 0 ]; then echo "  IDENTICAL: $(basename $f)"
    else echo "  MODIFIED ($d lines): $(basename $f)"; fi
  elif [ ! -f "$FK/$f" ]; then
    echo "  MISSING: $(basename $f)"
  fi
done

echo ""
echo "=== Tier 3: MEDIUM (modified files) ==="
for f in \
  "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/viewmodel/AuthenticationViewModel.kt" \
  "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/viewmodel/WalletViewModel.kt" \
  "ui-lib/src/main/java/co/electriccoin/zcash/ui/BiometricActivity.kt" \
  "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/restore/seed/RestoreSeedVM.kt" \
; do
  if [ -f "$UP/$f" ] && [ -f "$FK/$f" ]; then
    d=$(diff "$UP/$f" "$FK/$f" 2>/dev/null | wc -l)
    echo "  DIFF ($d lines): $(basename $f)"
  fi
done
```

### Full file-list diff

```bash
# Files only in fork (new Zapp additions)
diff <(cd ../zodl-android && find . -not -path './.git/*' -not -path './.gradle/*' \
  -not -path './.idea/*' -not -path './.kotlin/*' -not -path '*/build/*' | sort) \
  <(cd . && find . -not -path './.git/*' -not -path './.gradle/*' \
  -not -path './.idea/*' -not -path './.kotlin/*' -not -path '*/build/*' | sort) \
  | grep "^>" | head -50
```

### Merge procedure (from ZAPP_CHANGES.md)

```bash
# 1. Fetch new Zodl
git fetch <zodl-remote> main:upstream/zodl

# 2. Create merge branch
git checkout -b zapp/merge-zodl-X.Y.Z main

# 3. Merge (conflicts in hotspot files)
git merge upstream/zodl

# 4. Resolve, verify, PR
./gradlew :app:assembleZcashtestnetStoreDebug
./gradlew check
```

---

## Companion Documents

| Document | Location | Purpose |
|----------|----------|---------|
| `ZAPP_CHANGES.md` | Root | 8-commit patch series, merge guide |
| `UPSTREAM_SECURITY_SYNC_GUIDE.md` | `docs/` | Security tier details, sync procedure |
| `DEVELOPER_SETUP.md` | Root | Build environment version pins |
| `AGENTS.md` | Root | Architecture overview, key files |
| `UPSTREAM-PARITY.md` | `docs/audits/` | Live record of what upstream has that the fork has not taken |
| `ZAPP_CHANGES.md` | Root | What this fork changes relative to upstream, by area |

---

*Last updated: 2026-05-20*
