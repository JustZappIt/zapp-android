# Zapp for Android

Zapp is a privacy-first Zcash wallet with an end-to-end encrypted peer-to-peer messenger built
into it. Shielded ZEC for the money, Hyperswarm for the messages, and a peer-to-peer offramp for
spending in local currency. No account, no custody, no tracking. Crash reporting on Play builds
is opt-in and off by default, and the `foss` flavor has no Google dependency at all.

Zapp is a privacy-focused Android wallet forked from [Zodl](https://github.com/zodl-inc/zodl-android),
itself a fork of Electric Coin Company's
[Zashi](https://github.com/Electric-Coin-Company/zashi-android). The fork diverged at upstream
commit [`2409c4d7`](https://github.com/zodl-inc/zodl-android/commit/2409c4d7104c44ba72fe05343e0a20585b156be9)
(the zodl 3.3.1 release merge) and carries roughly 500 commits of divergence on top of it. What the
fork has taken from upstream since, and what it still owes, is tracked in
[docs/audits/UPSTREAM-PARITY.md](docs/audits/UPSTREAM-PARITY.md).

## What is different from upstream

- **Encrypted P2P chat.** Direct and group messaging over Hyperswarm, with Ed25519 identities
  derived from the wallet seed. No Zapp server can read your messages: a project-run blind peer
  stores encrypted blocks so they reach a recipient who was offline, and it cannot decrypt them.
- **A peer-to-peer offramp.** Scan a merchant QR and pay in local currency out of shielded ZEC,
  through the p2p.me contracts on Base. Seven currencies are wired up: INR, BRL, IDR, ARS, VEN,
  NGN, COP. A second rail cashes out through Peer (ZKP2P) on Base mainnet: you post the order, a
  buyer pays you on Revolut, Zelle, Chime or Monzo, and the protocol releases your USDC against
  their proof.
- **A peer-to-peer onramp.** Buy ZEC by paying a matched merchant in local currency. The purchase
  settles as USDC in your own Base account and continues into a swap that delivers shielded ZEC to
  your wallet, resumable across a kill or a reinstall on the same seed.
- **A different shell and visual system.** Three tabs (Pay | Chats | You), a Swiss-minimalist token
  set (`ZappTheme`), flat geometry, and bottom-left back navigation.
- **No governance voting.** Upstream's voting feature is intentionally not carried.

Everything else is deliberately kept in lockstep with upstream: Kotlin package names, module names,
Gradle flavors, resource ids and build-property names all stay as `zodl-android` has them so that
future upstream merges stay cheap.

## Features

- Shielded-by-default Zcash send and receive, with unified, transparent and shielded addresses
- Ironwood (NU6.3) support: a per-pool balance breakdown on the home screen, and a background
  migration that moves Orchard funds into Ironwood in standard-sized pieces over time, so the
  amounts leaving the old pool do not identify you
- Portfolio value chart in your local currency, with the balance series reconstructed on device
  and only the daily price series fetched, over Tor; switchable off in settings
- Transaction history, filters, notes and tax export
- Address book, with the same records backing chat contacts
- Encrypted chat: DMs, groups, media, read receipts, payment requests
- Keystone hardware-wallet signing over animated QR
- Swaps to and from shielded ZEC through NEAR intents
- Flexa payment integration, present and kept merge-compatible with upstream but inert:
  `ZCASH_FLEXA_KEY` ships blank so the SDK is never initialised
- Top Up shows your own receive addresses (from an exchange, or from another wallet) instead of
  handing you to a third-party on-ramp; Buy ZEC is the peer-to-peer alternative, with no account
  and no third-party custody
- Tor routing on by default for exchange-rate lookups, transaction submission and integrations,
  with a settings toggle to turn it off
- Background chat notifications: contentless FCM topics on the Google-enabled `store` and
  `internal` flavors, a self-hosted doorbell with no Google dependency on `foss`
  (see [docs/notifications/](docs/notifications))
- English, Spanish, Portuguese and Indonesian

## Screenshots

TODO: add current screenshots. The inherited upstream store screenshots were removed from this
repository and have not been replaced yet.

## Building

### Known limitation: this cannot be configured outside the org today

`settings.gradle.kts` includes two sibling projects **by path**, so both must be checked out next
to this repository on disk:

```kotlin
include(":zappmessaging")
project(":zappmessaging").projectDir = externalProjectDir("../zappMessaging/android", "zappMessaging/android")
include(":bare-kit")
project(":bare-kit").projectDir = externalProjectDir("../bare-kit/android", "bare-kit/android")
```

`bare-kit` is public: it is an unmodified clone of [holepunchto/bare-kit](https://github.com/holepunchto/bare-kit)
at the commit pinned in [`.zapp-deps`](.zapp-deps) (a v2.0.0 checkout).

`JustZappIt/zappMessaging` is **private at the time of writing**. Gradle fails at configuration
time if it is missing, and there is no flag that stubs the messaging module out, so people outside
the JustZappIt org cannot currently configure or build this project. CI works only because it
checks the repository out with a read token (`ZAPP_MESSAGING_READ_TOKEN`). This is a known
limitation, stated here rather than worked around.

For the same reason, this repository ships without the GitHub Actions workflows: every job depends
on that private checkout and on repository secrets, so none of them could run here. The references
to CI elsewhere in this document describe the internal repository, not something you can run from
this tree.

### Prerequisites

- JDK 17. This is the only supported JDK: `JVM_TOOLCHAIN=17` in `gradle.properties`,
  `gradle/daemon-jvm.properties` pins the Gradle daemon to Temurin 17, and CI reads the same
  property. In Android Studio, set **Settings > Build > Build Tools > Gradle > Gradle JDK** to a
  JDK 17 install rather than the bundled JBR.
- Android SDK platforms 36 (this repo) and 34 (required by `bare-kit`), NDK `27.0.12077973`
  (this repo) and NDK `28.2.13676358` plus CMake
  4.1.2 (required by `bare-kit`'s native build).
- Node.js, for `bare-kit`'s npm dependencies.
- `adb` on `PATH`, and an emulator or device.

Exact versions of everything else are in
[Build environment | exact versions](#build-environment--exact-versions) below.

### Checkout layout and first build

```
<some-dir>/
  zodl-android/        <-- this repository
  zappMessaging/       <-- required, currently private
  bare-kit/            <-- required, public
```

From the repository root, with both siblings pinned to the SHAs recorded in
[`.zapp-deps`](.zapp-deps):

```sh
git clone https://github.com/JustZappIt/zappMessaging.git ../zappMessaging
git -C ../zappMessaging checkout "$(grep '^zappMessaging=' .zapp-deps | cut -d= -f2)"

git clone https://github.com/holepunchto/bare-kit.git ../bare-kit
git -C ../bare-kit checkout "$(grep '^bare-kit=' .zapp-deps | cut -d= -f2)"
(cd ../bare-kit && npm ci)

./gradlew :app:installZcashmainnetFossDebug
adb shell monkey -p xyz.justzappit.zapp.foss.debug -c android.intent.category.LAUNCHER 1
```

The `foss` flavor is the easiest local build: it needs no `google-services.json` and pulls in no
Firebase or ML Kit. CI checks the two siblings out at the same SHAs and fails the build if they do
not match `.zapp-deps`.

If you change the messaging JavaScript in `../zappMessaging`, rebuild its worklet bundle with
`npm run build:android` there before running Gradle again.

### Networks and flavors

Variants are the product of two flavor dimensions and the build type:

| Dimension | Values | Effect |
| --- | --- | --- |
| `network` | `zcashmainnet`, `zcashtestnet` | Testnet adds a `.testnet` application-id suffix, so both install side by side |
| `distribution` | `store`, `foss`, `internal` | `foss` adds `.foss` and drops Google dependencies, `internal` adds `.internal` |

So a testnet FOSS debug build installs as `xyz.justzappit.zapp.testnet.foss.debug`, and a mainnet
one as `xyz.justzappit.zapp.foss.debug`.

`ZCASH_NETWORK` in `gradle.properties` is committed **blank**, which registers both networks so the
two apps can be built and installed side by side. That is also what CI expects. Set it to
`mainnet` or `testnet` if you want the short task names to resolve to one network, and put that
override in `local.properties` rather than committing it:

```sh
./gradlew :app:assembleZcashtestnetFossDebug          # spell the variant out
./gradlew -PZCASH_NETWORK=testnet :app:assembleDebug  # or narrow the matrix
```

The selection drives both `BuildConfig.FLAVOR_network` and the runtime `R.bool.zcash_is_testnet`
resource, so the two cannot drift. Testnet builds default to
`lightwalletd.testnet.cipherscan.app:443` (see
`ui-lib/.../common/provider/LightWalletEndpointProvider.kt`).

### Release signing

Release builds are signed with a key held by the maintainers. The build reads
`ZCASH_RELEASE_KEYSTORE_PATH`, `ZCASH_RELEASE_KEYSTORE_PASSWORD`, `ZCASH_RELEASE_KEY_ALIAS` and
`ZCASH_RELEASE_KEY_ALIAS_PASSWORD` from a git-ignored `local.properties`; they are committed blank
in `gradle.properties`, and blank means an unsigned release build. Debug builds need none of this.

## Project layout

| Module | What it is |
| --- | --- |
| `app` | Application shell, flavors, signing, manifest merge |
| `ui-lib` | Screens, view models and navigation, including the chat, swap and offramp flows |
| `ui-design-lib` | `ZappTheme` tokens and the shared Compose component set |
| `sdk-ext-lib` | Extensions over the Zcash Android SDK |
| `feature-migration` | The Orchard to Ironwood migration: scheduling, the background driver, Keystone rounds, and its screens |
| `evm-lib` | Kotlin Multiplatform EVM primitives: ABI coding, secp256k1 signing, HD derivation, JSON-RPC |
| `offramp-lib` | Kotlin Multiplatform fiat-rail clients: the p2p.me order lifecycle, the Peer escrow, the onramp driver, QR parsing, revert decoding |
| `preference-*`, `configuration-*`, `crash-*`, `spackle-*`, `build-info-lib` | Upstream plumbing, kept as upstream has it |
| `test-lib`, `ui-integration-test`, `ui-screenshot-test`, `ui-benchmark-test` | Test and benchmark harnesses |
| `build-conventions-secant` | Gradle convention plugins shared by every module |
| `:zappmessaging`, `:bare-kit` | The two external siblings described above |

Deeper notes on the design of the build and the app live in [docs/Architecture.md](docs/Architecture.md).

## Contributing

Contributions are welcome. Please read the [Contributing Guidelines](docs/CONTRIBUTING.md) first:
they cover the Developer Certificate of Origin sign-off and the commit-message format.

- The default branch is `main`, and all pull requests target it.
- Branch names follow `feat/<name>`, `fix/<name>`, `chore/<name>`, `docs/<name>`.
- Commit titles follow Conventional Commits, for example `fix(chat): stop dropping the last read receipt`.
- Run `./gradlew ktlint detektAll` before opening a pull request, which is what CI runs.

Build setup end to end, including troubleshooting, is in
[DEVELOPER_SETUP.md](DEVELOPER_SETUP.md) and [docs/Setup.md](docs/Setup.md).

## Reporting an issue

File a GitHub issue in this repository for Zapp-specific bugs. For upstream Zcash or Zodl issues,
use [Zodl issues](https://github.com/zodl-inc/zodl-android/issues/new/choose) or the
[Zcash Forum](https://forum.zcashcommunity.com/).

## Security

Do not open a public issue for a vulnerability. The reporting process, scope and disclosure
expectations are in [SECURITY.md](SECURITY.md).

## Code of conduct

Everyone participating in this project is expected to follow the
[Contributor Code of Conduct](docs/CONDUCT.md).

---

# For maintainers

## Build environment | exact versions

Most build failures on a fresh machine come down to a mismatched JDK, a missing NDK, or the sibling
repositories not being checked out next to `zodl-android`. These are the versions this tree is
known to build with.

### Host

| Tool | Version | Notes |
| --- | --- | --- |
| OS | macOS on Apple Silicon | Linux and Windows are supported upstream but are not verified on this fork. |
| Android Studio | Narwhal (`AI-253`) or newer | Anything new enough for AGP 8.13 should work. Set the Gradle JDK to 17. |

### JDK

| Tool | Version | How it is selected |
| --- | --- | --- |
| JDK | 17 | `JVM_TOOLCHAIN=17` in `gradle.properties`, `gradle/daemon-jvm.properties` pins the daemon to Temurin 17, and the CI `setup-java` action reads `JVM_TOOLCHAIN`. Point `JAVA_HOME` at a JDK 17 install for the first `./gradlew` run on a machine where Gradle has not auto-provisioned one. |
| Android JVM target | 1.8 (`ANDROID_JVM_TARGET`), Kotlin JVM target 8 (`KOTLIN_JVM_TARGET`) | Android does not support bytecode targets beyond Java 8. Do not change these. |

`Failed to read key AndroidDebugKey from store ...debug.keystore` or
`Algorithm HmacPBESHA256 not available` means `JAVA_HOME` is pointing at a JDK older than 17.
Upgrade it, or delete `~/.android/debug.keystore` and let it regenerate.

### Build system

| Tool | Version | Notes |
| --- | --- | --- |
| Gradle | 8.14.4 | Pinned by the wrapper. Always use `./gradlew`, never a system `gradle`. |
| Android Gradle Plugin | 8.13.2 (`ANDROID_GRADLE_PLUGIN_VERSION`) | |
| Kotlin | 2.3.10 (`KOTLIN_VERSION`) | |
| Compose compiler | 1.5.15 (`ANDROIDX_COMPOSE_COMPILER_VERSION`) | |
| Detekt | 1.23.8 | |
| ktlint | 1.8.0 | |

### Android SDK components

| Component | Version |
| --- | --- |
| `compileSdk` | 36 (`ANDROID_COMPILE_SDK_VERSION`) |
| `targetSdk` | 35 (`ANDROID_TARGET_SDK_VERSION`) |
| `minSdk` | 27 (`ANDROID_MIN_SDK_VERSION`) |
| NDK, this repository | 27.0.12077973 (`ANDROID_NDK_VERSION`) |
| NDK, `bare-kit` module | 28.2.13676358 (declared in `../bare-kit/android/build.gradle`) |
| CMake | 4.1.2 (`bare-kit` requires 4.0.0+) |

Both NDK versions need to be installed: this repository's modules pin the first, the sibling
`bare-kit` module pins the second for its own native build.

### Key runtime libraries

Pinned in `gradle.properties`, or directly in the version catalog in `settings.gradle.kts`, and
resolved by Gradle. You do not install these, but when a resolution error names one of them, these
are the versions in play.

| Area | Library | Version |
| --- | --- | --- |
| Compose | `androidx.compose.ui / foundation`, `material3`, `material-icons` | UI/Foundation 1.10.4, Material3 1.4.0, Icons 1.7.8 |
| AndroidX | `activity`, `lifecycle`, `navigation-compose`, `fragment` | 1.12.4, 2.10.0, 2.9.7, 1.8.9 |
| AndroidX | `core-ktx`, `splashscreen`, `work-runtime`, `browser` | 1.17.0, 1.2.0, 2.11.1, 1.9.0 |
| AndroidX | `camera-camera2 / lifecycle / view` | 1.5.3 |
| AndroidX | `biometric` | 1.4.0-alpha05 |
| AndroidX | `biometric-ktx` | 1.4.0-alpha02 (pinned literally in `settings.gradle.kts`) |
| AndroidX | `security-crypto` | 1.1.0 |
| DI | `io.insert-koin:koin-android` | 4.1.1 |
| Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-*` | 1.10.2 |
| Serialization | `kotlinx-serialization-json` | 1.10.0 |
| Images | `io.coil-kt:coil-compose` | 2.6.0 |
| Location | `com.google.android.gms:play-services-location` | 21.3.0 |
| QR scan (`store`) | `com.google.mlkit:barcode-scanning` | 17.3.0 |
| QR render | `com.google.zxing:core` | 3.5.4 |
| Zcash | `cash.z.ecc.android:zcash-android-sdk` | 3.0.1-SNAPSHOT (Ironwood / NU6.3, the Slipstream sync engine, and the Orchard migration SDK) |
| Zcash | `cash.z.ecc.android:kotlin-bip39` | 1.0.9 |
| Crypto | `com.google.crypto.tink:tink-android` | 1.20.0 |
| Keystone | `com.github.KeystoneHQ:keystone-sdk-android` | 0.8.3 |
| Flexa | `co.flexa:core / spend` | 1.1.3 |
| Animations | `com.airbnb.android:lottie-compose` | 6.6.4 |
| Desugaring | `com.android.tools:desugar_jdk_libs` | 2.1.5 |

To confirm any of these locally:
`./gradlew :ui-lib:dependencies --configuration zcashmainnetFossDebugRuntimeClasspath`.

The Zcash SDK resolves from Maven snapshots, matching upstream: `SDK_INCLUDED_BUILD_PATH` is blank
in `gradle.properties` and `ZCASH_SDK_VERSION` is requested from the Sonatype snapshot repository.
No sibling SDK clone and no local Rust build are needed. To build against a local SDK checkout, set
`SDK_INCLUDED_BUILD_PATH=../zcash-android-wallet-sdk` in `local.properties`, never in
`gradle.properties`, because the committed default has to match upstream.

## Build gotchas

- **`WhileSubscribed(Duration)` does not resolve.** Using
  `SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT)` with a `kotlin.time.Duration` needs
  `import kotlinx.coroutines.flow.WhileSubscribed`. Without it the compiler picks the `Long`
  overload and fails with "actual type is Duration, but Long was expected".
- **`MaterialColors` is not available in `ui-lib`.** `com.google.android.material` is not a
  declared dependency there. Use a color resource, or parse the hex directly.
- **Android Studio's bundled JDK 21 poisons a CLI JDK 17 build.** If the IDE compiles the build
  logic with JBR 21, a later command-line build on JDK 17 fails with
  `class file version 65.0 ... only recognizes ... up to 61.0`. Set the IDE's Gradle JDK to 17, and
  clean `build-conventions-secant/{build,.gradle,.kotlin}` before rebuilding.
- **A shared Gradle build cache poisons the precompiled-script-plugin compile.**
  `~/.gradle/caches/build-cache-1` is per machine, not per project. This fork's
  `settings.gradle.kts` includes two projects that upstream `zodl-android` does not, so
  `:build-conventions-secant` produces a different plugin-spec hash. Building both repositories on
  one machine without isolation makes kotlinc fail with
  `Source file or directory not found: .../_<hash>/PluginSpecBuilders.kt`. Recover with:

  ```sh
  ./gradlew --stop
  rm -rf ~/.gradle/caches/build-cache-1 \
         build-conventions-secant/{build,.gradle,.kotlin} \
         .gradle
  ./gradlew :app:installZcashtestnetFossDebug --no-build-cache
  ```

  Long term, either pass `--no-build-cache` when switching repositories, or give each repository
  its own `GRADLE_USER_HOME`.

## Tracking upstream

Upstream plumbing parity is deliberate, and drift is expensive. Before porting anything, read
[docs/audits/UPSTREAM-PARITY.md](docs/audits/UPSTREAM-PARITY.md), which is the single living record
of what has been taken from upstream, what is still owed, and what was investigated and dismissed.
Process for a sync lives in [docs/FORK_MAINTENANCE_GUIDE.md](docs/FORK_MAINTENANCE_GUIDE.md), and
security-relevant upstream changes have their own procedure in
[docs/UPSTREAM_SECURITY_SYNC_GUIDE.md](docs/UPSTREAM_SECURITY_SYNC_GUIDE.md).

## Known issues

1. Builds print "Unable to detect AGP versions for included builds. All projects in the build
   should use the same AGP version." This is safe to ignore. The version used under
   `build-conventions-secant` is the same one used everywhere else.
2. With `IS_ANDROID_INSTRUMENTATION_TEST_COVERAGE_ENABLED` set, the debug APK cannot be run. Only
   set it for automated test runs.
3. Compose test coverage reads low, because of
   [known limitations](https://github.com/jacoco/jacoco/issues/1208) in how Jacoco sees Compose.
4. Adding `espresso-contrib` breaks the build through conflicting classes. This is a
   [known issue](https://github.com/zcash/zcash-android-wallet-sdk/issues/306) in the Zcash Android
   SDK.
5. On first launch, `AndroidKeysetManager: keyset not found, will generate a new one` is printed
   twice. It is not an error and the code is not running twice.

## Forking this into a new app

If you are forking to build a different app rather than to contribute back:

1. Change `ZCASH_RELEASE_APP_NAME` in [gradle.properties](gradle.properties).
2. Change `ZCASH_RELEASE_PACKAGE_NAME` in [gradle.properties](gradle.properties), which
   [app/build.gradle.kts](app/build.gradle.kts) reads to build every application id.
3. Change `support_email_address` in
   [strings.xml](ui-lib/src/main/res/ui/non_translatable/values/strings.xml).
4. Remove the copyrighted Zcash and Zapp icons and logos, which live under
   `ui-lib/src/main/res/ui/common/` and `ui-lib/src/main/ic_launcher-playstore.png`.
5. Optionally, configure [Continuous Integration](docs/CI.md) secrets, and Firebase keys at
   `app/src/debug/google-services.json` and `app/src/release/google-services.json` if you want the
   `store` flavor.

# License

Zapp is a fork of the MIT-licensed [Zashi](https://github.com/Electric-Coin-Company/zashi-android)
/ [Zodl](https://github.com/zodl-inc/zodl-android) Zcash wallet, so the project as
a whole is available under the **MIT License** ([LICENSE-MIT](LICENSE-MIT)); all
code inherited or derived from upstream (© 2021-2025 Zcash) stays MIT and keeps
the upstream copyright notice.

**Zapp-original contributions**, including the `evm-lib` and `offramp-lib`
modules and the P2P chat feature, are **dual-licensed under `MIT OR Apache-2.0`**
([LICENSE-APACHE](LICENSE-APACHE)), matching the wider Zcash/Rust ecosystem
convention and adding an explicit patent grant on Zapp's own code. Such files
carry an `SPDX-License-Identifier: MIT OR Apache-2.0` header; treat any
unmarked file as MIT-only. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for the
full breakdown, and [docs/Third party licenses.md](docs/Third%20party%20licenses.md)
for bundled fonts and third-party dependencies.
