# Phase 3 media integration

The app changes in this workspace require the corrected public SDK at
`../zappmessaging-sdk`. The normal app build still consumes `../zappMessaging`;
that checkout, the dependency path and `.zapp-deps` were deliberately unchanged.

SDK prerequisite: [zappmessaging-sdk PR #4](https://github.com/JustZappIt/zappmessaging-sdk/pull/4),
review head `eeeaa88543f7d5c85058b5141e90c41d92bf69de`.
The full implementation, measurements, diagnostics instructions and remaining
launch checks are in [the phase 3 report](https://github.com/JustZappIt/zappmessaging-sdk/blob/fix/media-performance/PHASE3_MEDIA_PERFORMANCE.md).

The coordinated SDK integration must bring all phase 1–3 core fixes, Kotlin
wrappers, new media state/retry/client-ID APIs and rebuilt Android worklet into
the consumed SDK while preserving its independent feature work. Then pin that
reviewed consumed commit and run the normal app compile/install/device checks.
Do not copy only the JS bundle: the app requires the matching native APIs.

For review only, the app was compiled and tested against the public SDK using
this temporary Gradle init script (no settings file or dependency pin edit):

```groovy
settingsEvaluated { settings ->
    def messaging = settings.findProject(':zappmessaging')
    if (messaging != null) {
        messaging.projectDir = new File(settings.rootDir, '../zappmessaging-sdk/android')
    }
}
```

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew -I /tmp/zapp-phase3-public-sdk.init.gradle \
  :ui-lib:compileZcashmainnetFossDebugKotlin \
  :ui-lib:testZcashmainnetFossDebugUnitTest \
  --tests 'co.electriccoin.zcash.ui.screen.chat.model.*' ktlint detektAll
```

Compilation, 26 model/list tests, ktlint and detekt passed with that override.
`checkProperties` separately failed because existing local signing/API overrides
are loaded; checked-in credential defaults remain blank. This is not verification
of the old consumed SDK. Mainnet FOSS debug APK assembly also passed with JDK 17
and the same override. That APK was installed and launched on two physical
Android phones; its packaged worklet matches the public SDK bundle byte for byte.
Numeric-only logs recorded image transfers in both directions on Wi-Fi, including
receiver hash verification, persistence and UI availability. See the public report
for samples and the subsequent small-image race fix. Cellular, verified relay
paths and Android/iOS delivery remain unverified.

PR preparation repeated compilation, all 26 model/list tests, ktlint and detekt
in isolated worktrees based on current `main`, using JDK 17 and a temporary
override to the SDK PR checkout. Those checks passed. The ordinary
`checkProperties` invocation detected four inherited local release-signing
properties. Re-running that task with only those four properties set to their
checked-in empty defaults via command-line arguments passed; no configuration
file or signing material was changed.
