# AGENTS.md | Zapp Android (fork of zodl-android)

Zcash wallet forked from zodl-inc/zodl-android (itself forked from Zashi), with P2P chat
(Hyperswarm via a BareKit JS worklet), a UPI/PIX offramp, and a Swiss-design reskin.
Kotlin + Jetpack Compose, Koin DI (not Hilt), Gradle 8.14.4 via wrapper.

## Environment

- `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any `./gradlew`. The project
  requires JDK 17 (`JVM_TOOLCHAIN=17`; `gradle/daemon-jvm.properties` pins the daemon).
  The system default `java` (25) fails at startup with `IllegalArgumentException: 25`.
- NDK `27.0.12077973` and CMake `4.1.2` (bare-kit native build). `ANDROID_JVM_TARGET=1.8`
  is intentional, never change.
- Sibling checkouts are required next to this repo: `../zappMessaging` and `../bare-kit`.
  `../zcash-android-wallet-sdk` is optional (absent → SDK comes from Maven snapshots).
  `../zodl-android` is the upstream reference clone, READ-ONLY, never edit it.

## Build / run / verify

```bash
./gradlew :ui-lib:compileZcashmainnetFossDebugKotlin   # fast compile check after edits
./gradlew :app:installZcashtestnetFossDebug            # dev install (testnet)
./gradlew :app:installZcashmainnetFossDebug            # mainnet flavor
adb shell monkey -p xyz.justzappit.zapp.testnet.foss.debug -c android.intent.category.LAUNCHER 1
```

Flavors: `network` (`zcashtestnet`|`zcashmainnet`) × `distribution` (`store`|`foss`|`internal`).
Package ids: base `xyz.justzappit.zapp` + `.testnet` + `.foss`/`.internal` + `.debug`
(store adds nothing → store testnet debug = `xyz.justzappit.zapp.testnet.debug`).
`-PZCASH_NETWORK=testnet|mainnet` filters variants per-invocation; keep the committed
`ZCASH_NETWORK=` blank in `gradle.properties`.

Emulator facts: testnet lightwalletd is currently offline. Home showing
"Offline, reconnecting" is expected, not a regression. Mainnet builds are FLAG_SECURE
(screen capture is black; use testnet to verify UI). Cold start with a persisted wallet
crashes in the biometric gate on emulators. `adb shell pm clear <package>` recovers.

## Lint & tests (what PR CI runs)

```bash
./gradlew ktlint detektAll checkProperties
./gradlew :offramp-lib:jvmTest :evm-lib:jvmTest        # pure-JVM, no device needed
./gradlew :configuration-api-lib:check :crash-lib:check :preference-api-lib:check :spackle-lib:check
```

- `ktlintFormat` is repo-wide: it rewrites pre-existing violations in files you didn't
  touch. Revert that collateral before committing. Some main-tree files already fail `ktlint`.
- Detekt (`tools/detekt.yml`): `LongMethod`/`LongParameterList` are DISABLED, never add
  `@Suppress` for them. Bare `TODO`/`FIXME` comments fail `ForbiddenComment`; write
  `TODO [#123]: …`. Kotlin warnings are errors (`ZCASH_IS_TREAT_WARNINGS_AS_ERRORS=true`).
- Instrumented/UI tests run only in CI (emulator.wtf / Firebase Test Lab, secret-gated).

## Do not commit

- Anything in `local.properties` (signing keystore paths/passwords, `PIMLICO_API_KEY`,
  `P2P_RPC_URL_*`, `P2P_SUBGRAPH_URL_*`, `ZCASH_CMC_KEY`). Committed `gradle.properties`
  defaults for these stay blank. `./gradlew checkProperties` (run by CI) fails otherwise.
- `ZAPP_MESSAGING_LOG_LEVEL=debug` or a non-blank `ZCASH_NETWORK` in `gradle.properties`
  (developer-only values belong in `local.properties`).
- `BLIND_PEER_KEYS` / `BLIND_PEER_BOOTSTRAP` / `NTFY_BASE_URL` are public, safe to commit.

## Upstream & sibling coupling (landmines)

- `.zapp-deps` pins the `zappMessaging`, `bare-kit`, and `zcashAndroidWalletSdk` SHAs.
  CI verifies siblings against it. Bumping requires updating BOTH `.zapp-deps` and the
  `ZAPP_MESSAGING_REF`/`BARE_KIT_REF` env values in `.github/workflows/pull-request.yml`.
- The local `../zappMessaging` may deliberately sit on a feature branch ahead of the pin.
  Do not reset it or touch `.zapp-deps` as a side effect of unrelated work.
- All P2P logic lives in `zappMessaging/core/` (JS). After any JS change there, run
  `npm run build:android` in `../zappMessaging` (regenerates the committed
  `android/src/main/assets/worklet.bundle`). Never rebuild the bundle for Kotlin-only changes.
- New typed chat messages = new `contentType` + JSON payload on the Kotlin side only
  (`ui-lib/.../screen/chat/model/ChatModels.kt`); no JS/worklet change needed. Only new IPC
  message types in `ipc-handler.js` need mirroring in the Kotlin AND Swift SDK wrappers.
- Don't bump `SDK_INCLUDED_BUILD_PATH` / `ZCASH_SDK_VERSION` outside coordinated
  upstream-sync work. Upstream merges are cherry-pick (security/crypto), never full-merge.
  See `ZAPP_CHANGES.md` and `docs/FORK_MAINTENANCE_GUIDE.md` for conflict hotspots.

## Architecture map

- `app`: thin shell (`ZcashApplication` starts Koin, `MainActivity`).
- `ui-lib`: nearly all feature code: screens in `src/main/java/co/electriccoin/zcash/ui/screen/`
  (chat, tabs, offramp, swap, connectkeystone, …); Koin modules in `co/electriccoin/zcash/di/`
  (add bindings to the matching topic module: `ViewModelModule`, `UseCaseModule`,
  `RepositoryModule`, `ProviderModule`, `MapperModule`. Not a new module file).
- `ui-design-lib`: design system; new Zapp components go in `component/zapp/`, one per file.
- `evm-lib`, `offramp-lib`: pure-JVM KMP (host-testable); keep Android deps out.
- `:zappmessaging`, `:bare-kit`: external sibling Gradle projects.

## Style essentials (fork-specific, enforced in review)

- Screen triad: `FooState.kt` (data class) + `FooVM.kt` (class name ends `VM`, never
  `ViewModel`) + `FooView.kt` (`internal fun FooView(...)`, internal, not public).
- Theming: `ZappTheme.colors/typography/spacing` tokens only, never `ZashiColors.*`,
  `MaterialTheme.*`, or hardcoded hex. Sharp corners (`RectangleShape`) everywhere; no
  `Button`/`RoundedCornerShape`/`CircleShape`; back button is bottom-left, never in a top bar.
- Every user-facing string goes through `strings.xml` (per-feature file under
  `ui-lib/src/main/res/ui/<feature>/values/`), mirrored to `values-es` in the same commit.
- Chat error handling: use `runChatCall(...)` from `ui/screen/chat/common/ChatErrorHandling.kt`,
  not per-site try/catch.
- Manifest: adding a hardware-implying permission to `ui-lib/src/main/AndroidManifest.xml`
  requires a matching `<uses-feature android:required="false">` or Play filters devices.

## Git

- Branches: `feature/<ticket>`, `fix/<ticket>`, `chore/<description>`. PRs target `main`.
- `docs/`-only and README changes skip PR CI (`paths-ignore`).
