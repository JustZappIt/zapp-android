# Release builds

What changes when you produce a publishable artifact rather than a debug build.
The two rules below are easy to get wrong silently, and neither shows up as a
build failure. See [Setup.md](Setup.md) for ordinary local builds.

## 1. versionCode

`app/build.gradle.kts` derives the version code like this:

```kotlin
val versionCodeOverride = project.property("ZCASH_VERSION_CODE").toString().toInt()
val gitInfo = Git.newInfo(Git.HEAD, rootDir)
output.versionCode.set(if (versionCodeOverride == 1) gitInfo.commitCount else versionCodeOverride)
```

`gradle.properties` commits `ZCASH_VERSION_CODE=1`, so an unmodified build takes
the fallback branch and uses the git commit count as its version code.

A store rejects an upload whose version code is not strictly greater than the
highest one already published for that application. So a release build must
pass an explicit value:

```
./gradlew clean :app:bundleZcashmainnetStoreRelease -PZCASH_VERSION_CODE=<N>
```

with `N` above the highest already-published code. Any value other than `1`
takes the override branch and the git fallback is skipped entirely.

**The fallback is now useless.** This repository is published as a single
squashed commit, so `gitInfo.commitCount` evaluates to 1. A default build
therefore produces version code 1, which is below anything ever published and
will be rejected. Do not rely on it as a safety net.

Recommended: set a real floor in `gradle.properties`, some number comfortably
above the highest published code, instead of leaving `ZCASH_VERSION_CODE=1`
there. Passing `-P` per build still works and still wins, but the committed
default should not be a value that silently means "count the commits".

## 2. local.properties preflight

`settings.gradle.kts` loads `local.properties` into every project's `extra`
before configuration, so any property set there **shadows the committed
`gradle.properties` default**. That file is git-ignored, which means a
developer override you forgot about bakes into a release artifact with no
warning. Read it before every release build.

The following are fund-safety and privacy checks, not style preferences:

- **`P2P_NETWORK=mainnet`**, spelled out. Blank and `sepolia` both resolve to
  Base Sepolia, so "not set" does not mean production. A production build that
  omits this runs the offramp against a testnet.
- **`P2P_RPC_URL_BASE_MAINNET` and `P2P_SUBGRAPH_URL_MAINNET` non-blank** when
  `P2P_NETWORK=mainnet`. The config provider has no mainnet fallback and throws
  at startup if either is missing. (Both embed API keys, which is why the
  committed defaults are blank.)
- **`OFFRAMP_USE_DEV_KEY=false`.** When `true`, the offramp signs with a
  hardcoded EVM key that is checked into source, so every install shares one
  smart account and anyone reading the repo can spend what sits in it. It is a
  testnet QA convenience and must never reach a release.
- **`PIMLICO_API_KEY` non-blank.** Blank fails fast by design: the bundler URL
  builder requires it, so the offramp screens crash at runtime rather than
  quietly using a broken endpoint.
- **`ZAPP_MESSAGING_LOG_LEVEL` blank or `info`.** `debug` turns on verbose
  stream, probe and keypair diagnostics in the messaging worklet and bakes that
  level into the shipped `BuildConfig` of the `:zappmessaging` module.
- **No stray `ZCASH_NETWORK` override.** A non-blank value disables the other
  network's variants, so a build you expected to cover both flavors quietly
  becomes single-flavor.

After the build, confirm the preflight actually took by reading the generated
`BuildConfig` for the release variant under `ui-lib/build/generated/` and
checking that `P2P_NETWORK` and `OFFRAMP_USE_DEV_KEY` say what you intended.
Configuration mistakes here are invisible in the APK/AAB otherwise.

## 3. Refresh the bundled checkpoints

```
./gradlew :app:assembleZcashmainnetFossDebug   # once, to merge the SDK's assets
scripts/refresh-checkpoints.sh mainnet
```

Checkpoints are the height a scan is allowed to start from, and they ship frozen
inside the SDK AAR at whatever height that SDK was cut. Every week that passes
between the SDK's cut and this release is a stretch of chain that a restored
wallet has to scan from scratch — and a Zpacket minted above the newest bundled
checkpoint pays that whole gap on a phone before it can be claimed at all. On a
slow device that is the difference between a claim that finishes and one that
does not.

The script writes into `app/src/main/assets/`, where the app's own assets merge
over the library's, so nothing in the SDK is replaced. It refuses to write
anything unless it can first regenerate the newest checkpoint the SDK already
ships, byte for byte, and it cross-checks every new file against a second,
independent server.

Commit whatever it writes. Testnet takes the same command with `testnet`, but
its endpoint has never been verified — check the output before trusting it.

## Signing

Release signing reads four git-ignored properties from `local.properties`
(`ZCASH_RELEASE_KEYSTORE_PATH`, `ZCASH_RELEASE_KEYSTORE_PASSWORD`,
`ZCASH_RELEASE_KEY_ALIAS`, `ZCASH_RELEASE_KEY_ALIAS_PASSWORD`). They are
committed blank in `gradle.properties`, and blank means the release build is
left unsigned. Keystores, passwords and fingerprints never belong in this
repository.
