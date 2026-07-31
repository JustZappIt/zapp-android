# Zapp for Android | Developer setup

This is a fork of Zodl (Zcash wallet) with Zapp's P2P messaging integrated. The build pulls in the
native bare-kit module (the Bare/Hyperswarm P2P runtime) as a sibling Gradle project, and bare-kit
pins its own toolchain versions that differ from the app's. Read the table below carefully: **two
NDK versions are required, not one.**

Every version here is read back from the build files, not from memory. The authoritative sources are
`gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`, and `../bare-kit/android/build.gradle`.

## Required Versions

| Tool | Version | Where it is pinned |
|---|---|---|
| **JDK** | **17** | `JVM_TOOLCHAIN=17` in `gradle.properties`. CI reads that same line. |
| **Node.js** | any current LTS | Needed once, to install bare-kit's npm dependencies (see step 3a). |
| **Android Studio** | any release that supports **AGP 8.13.x** | `ANDROID_GRADLE_PLUGIN_VERSION=8.13.2`. Only needed for the IDE; `./gradlew` does not require it. |
| **Gradle** | 8.14.4 | Bundled via the wrapper (`./gradlew`) - don't install separately. |
| **Kotlin** | 2.3.10 | `KOTLIN_VERSION`. Managed by Gradle, no manual install needed. |
| **Android SDK Platform** | **36** and **34** | 36 for this repo (`ANDROID_COMPILE_SDK_VERSION`) and zappMessaging; 34 for bare-kit. Install both. |
| min / target SDK | 27 / 35 | `ANDROID_MIN_SDK_VERSION`, `ANDROID_TARGET_SDK_VERSION`. |
| **Android NDK** | **27.0.12077973** | `ANDROID_NDK_VERSION`. Used by every module in this repo. |
| **Android NDK** | **28.2.13676358** | `ndkVersion` in `../bare-kit/android/build.gradle`. Used by the bare-kit native build only. |
| **CMake** | **4.0.0+** (4.1.2 is known good) | `cmake_minimum_required(VERSION 4.0)` in `../bare-kit/CMakeLists.txt`. |

API 36 is Android 16. Installing only one of the two NDKs is the most common first-build failure.

## Step-by-Step Setup

### 1. Install JDK 17

Run Gradle on JDK 17. `gradle.properties` sets `JVM_TOOLCHAIN=17`, and the build-logic in
`build-conventions-secant/` is compiled by whichever JDK launches the Gradle daemon.

```bash
# macOS (Homebrew)
brew install --cask zulu17

# Verify
java -version
# Should show: openjdk version "17.x.x"
```

If you have multiple JDKs, set `JAVA_HOME`:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

Then in Android Studio: **Settings > Build > Gradle > Gradle JDK** -> select JDK 17.

Do this even if you only ever build from the command line. Android Studio defaults to its bundled
JDK 21, and if the IDE and the CLI disagree they recompile the same build-logic to different
class-file versions and thrash each other's caches. The symptom is a cascade of accessor-hash
mismatches and Kotlin daemon crashes that looks like a source problem but is not.

### 2. Install Android SDK Components

Open Android Studio **SDK Manager** (Settings > Languages & Frameworks > Android SDK) and install:

**SDK Platforms tab** (check "Show Package Details"):
- `Android SDK Platform 36` - this repo and zappMessaging compile against it
- `Android SDK Platform 34` - bare-kit compiles against it

**SDK Tools tab (check "Show Package Details"):**
- Android SDK Build-Tools 36.x
- **NDK (Side by side) > 27.0.12077973** (this repo's modules)
- **NDK (Side by side) > 28.2.13676358** (bare-kit's native build)
- **CMake > 4.1.2** (bare-kit requires CMake 4.0+)
- Android SDK Command-line Tools
- Android Emulator

Or install via command line:
```bash
sdkmanager "ndk;27.0.12077973" "ndk;28.2.13676358" "cmake;4.1.2" \
  "platforms;android-36" "platforms;android-34" "build-tools;36.0.0"
```

Both NDKs are genuinely needed at the same time. `secant.android-build-conventions.gradle.kts` sets
`ndkVersion` from `ANDROID_NDK_VERSION` for every module in this repo, while bare-kit ships its own
`build.gradle` that pins a different one. Neither can be changed unilaterally.

### 3. Clone the Repo

`settings.gradle.kts` resolves `:zappmessaging` and `:bare-kit` from **sibling** directories, so the
three checkouts must share a parent. The Zcash Android SDK comes from Maven and is **not** required
as a sibling (see step 3b):

```
<workspace>/
  <this repo>/                  # any directory name; nothing depends on it
  zappMessaging/                # P2P messaging SDK (android/ subdirectory)
  bare-kit/                     # Bare JS runtime (android/ subdirectory)
  zcash-android-wallet-sdk/     # optional, only for building the SDK from source
```

The two sibling names are load-bearing and the SHAs are pinned in `.zapp-deps`. CI verifies the
zappMessaging SHA before building, so check out the pinned commits rather than the branch tips:

```bash
# Run these from this repo's root.
git clone https://github.com/JustZappIt/zappMessaging.git ../zappMessaging
git clone https://github.com/holepunchto/bare-kit.git ../bare-kit

# Check out the pinned commits recorded in .zapp-deps.
git -C ../zappMessaging checkout "$(grep '^zappMessaging=' .zapp-deps | cut -d= -f2)"
git -C ../bare-kit      checkout "$(grep '^bare-kit='      .zapp-deps | cut -d= -f2)"
```

`settings.gradle.kts` also accepts the siblings *inside* the workspace root (`zappMessaging/android`
relative to this repo), which is the layout CI checks out. Either works.

> **`JustZappIt/zappMessaging` is not currently a public repository.** Without access to it, the
> Gradle build cannot configure and no variant will assemble. This is a real limitation of the
> published tree, not a misconfiguration on your side. Everything else in this repo is complete and
> readable, and `evm-lib` / `offramp-lib` have host-JVM tests you can run once the build configures.

bare-kit is an unmodified clone of `holepunchto/bare-kit`; the fork adds nothing to it. zappMessaging
commits its prebuilt worklet bundle and `jniLibs`, so a plain checkout is enough to build. You only
need to regenerate them after changing its JavaScript:

```bash
(cd ../zappMessaging && npm run build:android)
```

### 3a. Install bare-kit's npm dependencies

Required once, and after any bare-kit bump. bare-kit's `CMakeLists.txt` does
`find_package(cmake-bare REQUIRED PATHS node_modules/cmake-bare)`, so the native build fails at
configure time without it:

```bash
(cd ../bare-kit && npm ci)
```

### 3b. The Zcash SDK (optional local build)

**You do not need the SDK clone to build this repo.** Like upstream
`zodl-inc/zodl-android`, `gradle.properties` leaves `SDK_INCLUDED_BUILD_PATH`
blank and resolves the SDK from the Sonatype snapshot repo:

```
SDK_INCLUDED_BUILD_PATH=
ZCASH_SDK_VERSION=2.8.0-rc.1-SNAPSHOT
```

`2.8.0-rc.1` is the first SDK release with Ironwood (NU6.3) support and is what
upstream zodl 3.8.0 ships. Anything older stops syncing past the Ironwood
activation height (mainnet 3,428,143) with `MismatchedConsensusBranch`.

`.zapp-deps` records the corresponding SDK tag SHA for provenance. Unlike the
zappMessaging and bare-kit SHAs, CI does not verify it.

To build the SDK from source instead, for debugging into it or to test an
unreleased SDK commit, clone it as a sibling, check out the pinned SHA, and set
the path in `local.properties` (**not** `gradle.properties`, whose committed
default must keep matching upstream). Run these from this repo's root:

```bash
git clone https://github.com/zcash/zcash-android-wallet-sdk.git ../zcash-android-wallet-sdk
git -C ../zcash-android-wallet-sdk checkout \
  "$(grep '^zcashAndroidWalletSdk=' .zapp-deps | cut -d= -f2)"
echo 'SDK_INCLUDED_BUILD_PATH=../zcash-android-wallet-sdk' >> local.properties
```

`settings.gradle.kts` activates the included build only when that property is
non-empty *and* the directory exists, so a stale path silently falls back to
Maven. **APK size is unchanged**, only the *origin* of the SDK's compiled
`.class` files changes. First build is much slower (the Rust toolchain compiles
the native backend); subsequent builds are cached.

Requirements added by this step:
- **Rust toolchain** (`cargo`, `rustc`). `brew install rust` if missing.
- **Disk** ~1.7 GB for the SDK clone (native blockchain checkpoints).

### 4. Build

From this repo's root:

```bash
# First build (downloads dependencies, compiles native code - ~15 min)
./gradlew :app:assembleZcashtestnetFossDebug

# Install on connected device/emulator
./gradlew :app:installZcashtestnetFossDebug
```

`foss` is the recommended default: it pulls in no Google Play services, so it builds and runs the
same way for everyone. `store` and `internal` also build without any extra setup; the Firebase
plugins are only applied when a `google-services.json` is present, and there is none in the repo.

Two flavor dimensions multiply out: `zcashtestnet` / `zcashmainnet` × `store` / `foss` / `internal`.
The two networks install side by side, as testnet gets a `.testnet` application-id suffix.

`ZCASH_NETWORK` is **blank** in the committed `gradle.properties`, so **all** network variants are
registered. That is what CI and side-by-side installs want, and the committed value must stay blank.
Filter it only to speed up local configuration, either per invocation or in `local.properties`:

```bash
./gradlew -PZCASH_NETWORK=mainnet :app:installZcashmainnetFossDebug
```

If a testnet build sits on "Offline, reconnecting", check the lightwalletd endpoint before assuming a
build problem. `LightWalletEndpointProvider` lists seven mainnet hosts but only one for testnet, so a
single community server being down takes testnet sync with it. Mainnet is unaffected.

### 5. Build Variant

In Android Studio, use:
- **Module:** app
- **Build Variant:** `zcashtestnetFossDebug`

Set via: **Build > Select Build Variant** (or the Build Variants panel on the left sidebar).

If a variant you expect is missing from the list, `ZCASH_NETWORK` is set to the other network
somewhere in your `local.properties`, your `gradle.properties`, or an `ORG_GRADLE_PROJECT_ZCASH_NETWORK`
environment variable.

## Common Build Errors

### `jvmTarget` / JVM compilation error
```
Using 'jvmTarget: String' is an error. Please migrate to the compilerOptions DSL.
```
**Fix:** Gradle is running on the wrong JDK. Use JDK 17. Check `java -version` and Android Studio's
Gradle JDK setting, and make sure they agree (step 1).

### `NDK not found`
```
No version of NDK matched the requested version 27.0.12077973
No version of NDK matched the requested version 28.2.13676358
```
**Fix:** Install whichever is missing. You need both:
```bash
sdkmanager "ndk;27.0.12077973" "ndk;28.2.13676358"
```

### `CMake 4.0.0+ required`
```
CMake 4.0.0 or higher is required
```
**Fix:** Install CMake 4.1.2 via SDK Manager:
```bash
sdkmanager "cmake;4.1.2"
```

### `Could not find a package configuration file provided by "cmake-bare"`
**Fix:** bare-kit's npm dependencies are not installed (step 3a):
```bash
cd ../bare-kit && npm ci
```

### `Project with path ':zappmessaging' could not be found`
**Fix:** the sibling checkouts are missing or misnamed. `settings.gradle.kts` looks for
`../zappMessaging/android` and `../bare-kit/android` first, then the same paths relative to this
repo. See step 3. Note that `JustZappIt/zappMessaging` is not currently public.

### Manifest merger: minSdkVersion 27 < 29
```
uses-sdk:minSdkVersion 27 cannot be smaller than version 29 declared in library [:zappmessaging]
```
**Fix:** Already handled in `app/src/main/AndroidManifest.xml`, which carries
`tools:overrideLibrary="xyz.justzappit.zappmessaging, to.holepunch.bare.kit"`. Both libraries
declare `minSdk 29` against the app's 27. If you see this, make sure you have the latest code.

### `Could not resolve` / dependency resolution failures
```
Could not resolve all files for configuration ':app:coreLibraryDesugaring'
```
**Fix:** Corrupted Gradle cache. Run:
```bash
./gradlew --stop
rm -rf ~/.gradle/caches/transforms-*/ ~/.gradle/caches/8.14.4/
./gradlew :app:assembleZcashtestnetFossDebug
```

### `Plugin 'secant.detekt-conventions' not found`
**Fix:** Stale build-logic cache. Clear all four at once, from this repo's root, or the surviving
one will re-poison the others:
```bash
./gradlew --stop
rm -rf .gradle/ buildSrc/build/ build-conventions-secant/build/ build-conventions-secant/.gradle/
./gradlew :app:assembleZcashtestnetFossDebug
```
If it comes back, also clear `~/.gradle/caches/transforms-*`, `~/.gradle/caches/build-cache-1` and
`~/.gradle/caches/*/kotlin-dsl`. That combination is almost always a JDK mismatch (step 1) rather
than genuine corruption, so fix the JDK first or it will recur.

## Project Structure

```
<this repo>/
  app/                          # Application module, flavors, signing, packaging
  ui-lib/                       # All UI screens, view models, repositories
  ui-design-lib/                # Design system: ZcashTheme + Zashi* (upstream), ZappTheme (fork)
  sdk-ext-lib/                  # Zcash SDK extensions
  evm-lib/                      # KMP EVM primitives for the offramp (xyz.justzappit.evm)
  offramp-lib/                  # KMP p2p.me protocol (xyz.justzappit.offramp)
  build-conventions-secant/     # Gradle build-logic plugins (secant.*)
  ../zappMessaging/android/     # P2P messaging SDK (sibling Gradle project)
  ../bare-kit/android/          # Bare JS runtime (sibling Gradle project)
```

The chat product lives under
`ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/`, one package per surface. Each follows
the repo's file-triad convention (`FooState.kt`, `FooVM.kt`, `FooView.kt`, with `FooScreen.kt` as
the route entry). Chat is the one area that also uses a `view/` subdirectory, because it has many
small composables:

```
AndroidChat.kt         # navigation routes and entry points
common/                # ChatBootstrap, ChatError/ChatResult helpers, relative time
list/                  # conversation list
room/                  # message room
contacts/              # chat contacts, backed by the wallet address book
newconv/               # start a new conversation
profile/  identity/    # your profile, identity setup fallback
settings/              # chat settings
onlinestatus/  readreceipts/  backgrounddelivery/   # per-feature settings screens
media/                 # attachments, camera capture, image processing
scan/                  # scan a public key to add a contact
support/               # in-app support conversation
model/  repository/  view/     # models, repositories, composables (incl. view/bubbles/)
```

## Architecture Notes

- **DI:** Koin (not Hilt). Chat is wired in
  `ui-lib/src/main/java/co/electriccoin/zcash/di/ZappMessagingModule.kt` and `ChatViewModelModule.kt`
- **Navigation:** Jetpack Compose Navigation with `@Serializable` route args
- **P2P engine:** Hyperswarm DHT via a Bare JS worklet (runs JavaScript in a native VM)
- **Identity:** Ed25519 keypair derived from the BIP-39 seed phrase
- **Chat identity** is derived automatically once a wallet exists. `ChatBootstrap` observes the
  wallet and calls `sdk.restoreFromSeedPhrase(...)`, so there is no separate chat signup. See
  `ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/common/ChatBootstrap.kt`
- **bare-kit IPC has main-thread affinity.** Initialising it off the main thread null-derefs inside
  the native runtime. `ChatBootstrap` uses `MainScope()` for this reason; keep it that way

For which module owns what, and how the fork's surfaces map onto upstream's, see
[`ZAPP_CHANGES.md`](ZAPP_CHANGES.md) and
[`docs/FORK_MAINTENANCE_GUIDE.md`](docs/FORK_MAINTENANCE_GUIDE.md).
