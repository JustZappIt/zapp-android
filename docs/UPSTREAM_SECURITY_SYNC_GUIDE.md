# Upstream Security Sync Guide

How to keep Zapp (`android-zapp`) secure by tracking security-relevant changes
in `zodl-android` (upstream Zodl). Zapp is an independent fork. No full merges happen.
Security updates are cherry-picked file-by-file.

---

## 1. Relationship Model

```
zodl-android (upstream)          android-zapp (Zapp)
========================         ========================
  Security-critical code  ------>  Identical copies
  UI / branding           ----X-   Zapp's own UI
  New features (voting,   ----X-   Not adopted unless
   pay, keepopen)                   explicitly wanted
```

**Rule**: Security-critical files in the fork MUST stay identical to upstream.
When upstream changes one of these files, copy it over verbatim.

---

## 2. Module Security Classification

### Tier 1 | CRITICAL (always sync immediately)

These modules handle cryptographic operations, key material, and wallet state.
Any upstream change here MUST be applied to the fork within 48 hours.

| Module | Files | What It Does |
|--------|-------|-------------|
| `sdk-ext-lib/` | 14 files | Zcash SDK extensions: address types, ZEC formatting, network config |
| `preference-api-lib/` | 8 src files | Preference API: key types, defaults |
| `preference-impl-android-lib/` | 4 src files | `EncryptedPreferenceProvider`, `AndroidPreferenceProvider`: encrypted DataStore |

**Current status**: `sdk-ext-lib` has 2 diffs (missing `CurrencyFormatterExt.kt` + modified `ZcashNetwork.kt`). All preference libs are IDENTICAL.

### Tier 2 | HIGH (sync within 1 week)

These handle authentication, wallet lifecycle, and transaction signing.

| File (in `ui-lib/`) | What It Does | Fork Status |
|---------------------|-------------|-------------|
| `common/datasource/ZashiSpendingKeyDataSource.kt` | Spending key derivation | IDENTICAL |
| `common/datasource/ProposalDataSource.kt` | Transaction proposal creation | IDENTICAL |
| `common/repository/WalletRepository.kt` | Wallet init, seed persistence | IDENTICAL |
| `common/repository/BiometricRepository.kt` | Biometric auth state | IDENTICAL |
| `common/repository/ZashiProposalRepository.kt` | Proposal signing + submission | IDENTICAL |
| `common/repository/KeystoneProposalRepository.kt` | Keystone HW signing | IDENTICAL |
| `common/usecase/RestoreWalletUseCase.kt` | Seed restore logic | IDENTICAL |
| `common/usecase/ValidateSeedUseCase.kt` | BIP-39 seed validation | IDENTICAL |
| `common/serialization/addressbook/AddressBookEncryptor.kt` | AES-GCM address book encryption | IDENTICAL |
| `common/serialization/addressbook/AddressBookKey.kt` | Encryption key derivation | IDENTICAL |
| `common/serialization/metadata/MetadaEncryptor.kt` | AES-GCM metadata encryption | IDENTICAL |
| `common/serialization/metadata/MetadataKey.kt` | Encryption key derivation | IDENTICAL |
| `common/provider/AddressBookKeyStorageProvider.kt` | Key storage for address book | IDENTICAL |
| `common/provider/MetadataKeyStorageProvider.kt` | Key storage for metadata | IDENTICAL |
| `common/provider/EphemeralKeySetProvider.kt` | Ephemeral transaction keys | IDENTICAL |
| `screen/deletewallet/ResetZashiUseCase.kt` | Secure wallet wipe | IDENTICAL |
| `preference/EncryptedPreferenceKeys.kt` | Encrypted preference key defs | FORK-ONLY (Zapp addition) |
| `BiometricActivity.kt` | Biometric prompt activity | MODIFIED (14 lines diff) |

### Tier 3 | MEDIUM (sync within 2 weeks)

These handle authentication UI and wallet state display.

| File (in `ui-lib/`) | What It Does | Fork Status |
|---------------------|-------------|-------------|
| `common/viewmodel/AuthenticationViewModel.kt` | App-lock auth flow | MODIFIED (166 lines diff) |
| `common/viewmodel/WalletViewModel.kt` | Wallet state machine | MODIFIED (47 lines diff) |
| `screen/restore/seed/RestoreSeedVM.kt` | Seed entry validation | MODIFIED (8 lines diff) |
| `screen/authentication/` (all files) | Biometric/auth UI | IDENTICAL (view files) |
| `screen/scan/` (all files) | QR scanner + address parsing | Check on each sync |
| `screen/reviewtransaction/` | Transaction review before send | Check on each sync |
| `screen/transactionprogress/` | Send progress + confirmation | Check on each sync |

### Tier 4 | LOW (sync quarterly or skip)

| Module | Why Low |
|--------|---------|
| `crash-lib/` + `crash-android-lib/` | Crash reporting: sync only for data-leak fixes |
| `configuration-api-lib/` + `configuration-impl-android-lib/` | Remote config: sync only for flag-bypass fixes |
| `build-info-lib/` | Version metadata: informational only |
| `ui-design-lib/` | Zapp has own theme (`ZappTheme`), never sync UI tokens |
| `spackle-lib/` + `spackle-android-lib/` | Utility code: currently IDENTICAL, sync if changed |

**Current status**: `crash-android-lib` has 6 diffs (Firebase-related files Zapp removed).
All other Tier 4 modules are IDENTICAL.

---

## 3. Modified Files | What Zapp Changed and Why

These files differ between upstream and fork. When upstream updates them, the
Zapp-specific changes must be manually re-applied after copying the upstream version.

### `AuthenticationViewModel.kt` (166-line diff)
**What Zapp changed**: Added PIN-based auth support alongside biometric auth.
Integrated `PinAuthGate` from `common/security/PinAuthGate.kt`. Added
`OnboardingSecurityViewModel` interop for post-onboarding auth enrollment.
**Sync procedure**: Copy upstream file, then re-apply PIN auth additions. Search
for `PinAuthGate`, `OnboardingSecurityViewModel`, and `SecuritySettings` references.

### `WalletViewModel.kt` (47-line diff)
**What Zapp changed**: Added `currentSeedWords` StateFlow for onboarding seed-reveal.
Added `createNewWallet()` method used by `ZappOnboardingFlow` and `WalletTabContent`.
**Sync procedure**: Copy upstream file, then re-add the `currentSeedWords` property
and `createNewWallet()` method.

### `BiometricActivity.kt` (14-line diff)
**What Zapp changed**: Minor, likely import adjustments for Zapp theme/security.
**Sync procedure**: Copy upstream file, diff, re-apply any Zapp-specific imports.

### `RestoreSeedVM.kt` (8-line diff)
**What Zapp changed**: Minor navigation adjustment (RestoreSuccess route).
**Sync procedure**: Copy upstream file, re-apply the `WrapRestoreSuccessArgs` navigation.

### `ZcashNetwork.kt` in `sdk-ext-lib` (differs)
**What Zapp changed**: Likely testnet default or network selection logic.
**Sync procedure**: Compare carefully. This affects which blockchain network the app uses.

---

## 4. Security Sync Procedure

### Step 1 | Identify upstream changes

```bash
# From the zodl-android directory, check what changed since last sync
cd ../zodl-android
git log --oneline --since="YYYY-MM-DD" -- \
  sdk-ext-lib/ \
  preference-api-lib/ \
  preference-impl-android-lib/ \
  spackle-lib/ \
  spackle-android-lib/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/common/datasource/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/common/repository/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/common/serialization/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/RestoreWalletUseCase.kt \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/common/usecase/ValidateSeedUseCase.kt \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/common/viewmodel/AuthenticationViewModel.kt \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/common/viewmodel/WalletViewModel.kt \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/BiometricActivity.kt \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/authentication/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/restore/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/deletewallet/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/scan/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/reviewtransaction/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/transactionprogress/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/scankeystone/ \
  ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/signkeystonetransaction/
```

### Step 2 | Classify each changed file

For each changed file, determine:
1. Is it in Tier 1-2? → Must sync
2. Is it in Tier 3? → Should sync
3. Is it a file Zapp modified? → Manual re-apply needed (see Section 3)
4. Is it a file Zapp didn't modify? → Direct copy

### Step 3 | Apply changes

```bash
# For IDENTICAL files (direct copy):
cp ../zodl-android/path/to/file.kt ./path/to/file.kt

# For MODIFIED files (manual merge):
# 1. Copy upstream version to a temp location
cp ../zodl-android/path/to/file.kt /tmp/upstream_file.kt

# 2. Diff against current fork version
diff ./path/to/file.kt /tmp/upstream_file.kt

# 3. Apply upstream security changes while preserving Zapp additions
# (see Section 3 for file-specific guidance)
```

### Step 4 | Verify

```bash
cd <this repo>

# Build
./gradlew :app:assembleZcashtestnetStoreDebug

# Tests
./gradlew check

# Lint
./gradlew detektAll && ./gradlew ktlintFormat

# Verify identical files are still identical
diff ../zodl-android/sdk-ext-lib/src/main/java/cash/z/ecc/sdk/model/ZecRequest.kt \
     sdk-ext-lib/src/main/java/cash/z/ecc/sdk/model/ZecRequest.kt
# (repeat for each Tier 1-2 file)
```

### Step 5 | Document

Add a dated entry to a sync log (e.g., append to this file or a separate
`SECURITY_SYNC_LOG.md`):

```
## YYYY-MM-DD Security Sync

Upstream commits reviewed: abc1234..def5678
Files updated:
- sdk-ext-lib/src/.../ZecRequest.kt (Tier 1, direct copy)
- ui-lib/.../AuthenticationViewModel.kt (Tier 2, manual merge: re-applied PIN auth)
Verified: build ✓, tests ✓, lint ✓
```

---

## 5. Quick-Reference Diff Command

Run this to get a snapshot of all differences between upstream and fork for
security-critical files:

```bash
#!/bin/bash
# save as scripts/security-diff.sh

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

for dir in \
  "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/serialization" \
  "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider" \
; do
  diff -rq "$UP/$dir" "$FK/$dir" 2>/dev/null | grep -v ".gradle\|build/"
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

---

## 6. Security Review Checklist

When applying upstream security changes, verify:

- [ ] **Seed phrase handling**: no plaintext logging, no clipboard exposure, proper zeroization
- [ ] **Key material**: `UnifiedSpendingKey` never stored outside `EncryptedPreferenceProvider`
- [ ] **Transaction signing**: proposals validated before signing, amounts match user intent
- [ ] **Biometric auth**: `BiometricPrompt` uses `CryptoObject`, no fallback to insecure methods
- [ ] **Network config**: lightwalletd server URLs are valid, TLS enforced
- [ ] **Address validation**: recipient addresses validated before proposal creation
- [ ] **Encrypted storage**: `EncryptedSharedPreferences` used for all sensitive data
- [ ] **No new permissions**: check if upstream added new Android permissions
- [ ] **Dependency versions**: check if upstream bumped `zcash-android-sdk` or crypto dependencies
- [ ] **Build config**: verify `compileSdk`, `targetSdk`, `minSdk` haven't introduced vulnerabilities

---

## 7. Upstream Dependency Versions to Track

These are the critical dependencies whose versions should match upstream:

| Dependency | What It Is | Where Defined |
|-----------|-----------|---------------|
| `cash.z.ecc.android:zcash-android-sdk` | Core Zcash SDK | `gradle/libs.versions.toml` or `buildSrc/` |
| `org.jetbrains.kotlin:kotlin-stdlib` | Kotlin stdlib | `buildSrc/` |
| `androidx.security:security-crypto` | EncryptedSharedPreferences | `buildSrc/` |
| `androidx.biometric:biometric` | Biometric API | `buildSrc/` |
| `com.google.crypto.tink:tink-android` | Crypto primitives | Transitive via security-crypto |

When upstream bumps any of these, apply the same version bump to the fork.
