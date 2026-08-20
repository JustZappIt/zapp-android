import com.android.build.api.variant.BuildConfigField
import model.DistributionDimension
import model.NetworkDimension

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("secant.android-build-conventions")
    id("wtf.emulator.gradle")
    id("secant.emulator-wtf-conventions")
    id("secant.jacoco-conventions")
}

android {
    namespace = "co.electriccoin.zcash.ui"

    defaultConfig {
        testInstrumentationRunner = "co.electriccoin.zcash.test.ZcashUiTestRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Android SDK stubs (e.g. android.util.Log, used by Twig) throw by default under plain
        // JVM unit tests instead of no-oping — needed so ViewModel logging doesn't crash tests
        // that don't otherwise touch Android framework classes.
        unitTests.isReturnDefaultValues = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.androidx.compose.compiler.get().versionConstraint.displayName
    }

    sourceSets {
        getByName("main").apply {
            res.setSrcDirs(
                setOf(
                    // This is a special case as these texts are not translated, they are replaced in build time via
                    // app/build.gradle.kts instead
                    "src/main/res/ui/non_translatable",

                    "src/main/res/ui/about",
                    "src/main/res/ui/account_list",
                    "src/main/res/ui/address_book",
                    "src/main/res/ui/add_contact",
                    "src/main/res/ui/advanced_settings",
                    "src/main/res/ui/authentication",
                    "src/main/res/ui/balances",
                    "src/main/res/ui/chat",
                    "src/main/res/ui/common",
                    "src/main/res/ui/contact",
                    "src/main/res/ui/connect_keystone",
                    "src/main/res/ui/crash_reporting_opt_in",
                    "src/main/res/ui/delete_wallet",
                    "src/main/res/ui/export_data",
                    "src/main/res/ui/error",
                    "src/main/res/ui/home",
                    "src/main/res/ui/insufficient_funds",
                    "src/main/res/ui/choose_server",
                    "src/main/res/ui/integrations",
                    "src/main/res/ui/ironwood",
                    "src/main/res/ui/keep_open",
                    "src/main/res/ui/onboarding",
                    "src/main/res/ui/onramp",
                    "src/main/res/ui/pay",
                    "src/main/res/ui/payment_request",
                    "src/main/res/ui/qr_code",
                    "src/main/res/ui/request",
                    "src/main/res/ui/receive",
                    "src/main/res/ui/review_keystone_transaction",
                    "src/main/res/ui/restore",
                    "src/main/res/ui/restore_flow",
                    "src/main/res/ui/restore_success",
                    "src/main/res/ui/scan",
                    "src/main/res/ui/scan_keystone",
                    "src/main/res/ui/security_warning",
                    "src/main/res/ui/securitysettings",
                    "src/main/res/ui/seed_recovery",
                    "src/main/res/ui/select_keystone_account",
                    "src/main/res/ui/send",
                    "src/main/res/ui/send_confirmation",
                    "src/main/res/ui/settings",
                    "src/main/res/ui/sign_keystone_transaction",
                    "src/main/res/ui/splash",
                    "src/main/res/ui/swap",
                    "src/main/res/ui/transaction_detail",
                    "src/main/res/ui/transaction_filters",
                    "src/main/res/ui/transaction_history",
                    "src/main/res/ui/transaction_note",
                    "src/main/res/ui/tax_export",
                    "src/main/res/ui/tex_unsupported",
                    "src/main/res/ui/feedback",
                    "src/main/res/ui/update",
                    "src/main/res/ui/update_contact",
                    "src/main/res/ui/wallet_address",
                    "src/main/res/ui/warning",
                    "src/main/res/ui/whats_new",
                    "src/main/res/ui/exchange_rate",
                    "src/main/res/ui/tor",
                    "src/main/res/ui/top_up",
                    "src/main/res/ui/offramp",
                    "src/main/res/ui/peer_offramp",
                    "src/main/res/ui/unified_send",
                    "src/main/res/ui/viewing_key_export",
                )
            )
        }
    }

    flavorDimensions += listOf(NetworkDimension.DIMENSION_NAME, DistributionDimension.DIMENSION_NAME)

    productFlavors {
        create(NetworkDimension.TESTNET.value) {
            dimension = NetworkDimension.DIMENSION_NAME
        }

        create(NetworkDimension.MAINNET.value) {
            dimension = NetworkDimension.DIMENSION_NAME
        }

        create(DistributionDimension.STORE.value) {
            dimension = DistributionDimension.DIMENSION_NAME
        }

        create(DistributionDimension.FOSS.value) {
            dimension = DistributionDimension.DIMENSION_NAME
        }

        create(DistributionDimension.INTERNAL.value) {
            dimension = DistributionDimension.DIMENSION_NAME
        }
    }

    sourceSets {
        getByName("internal").apply {
            java.srcDirs("src/internal/java")
        }
        listOf("store", "internal").forEach { distribution ->
            getByName(distribution).apply {
                java.srcDir("src/google/java")
                res.srcDir("src/google/res")
                manifest.srcFile("src/google/AndroidManifest.xml")
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        // Configure SecureScreen for protecting screens with sensitive data in runtime
        variant.buildConfigFields?.put(
            "IS_SECURE_SCREEN_ENABLED",
            BuildConfigField(
                type = "boolean",
                value = project.property("IS_SECURE_SCREEN_PROTECTION_ACTIVE").toString(),
                comment = "Whether is the SecureScreen sensitive data protection enabled"
            )
        )
        variant.buildConfigFields?.put(
            "ZCASH_FLEXA_KEY",
            BuildConfigField(
                type = "String",
                value = "\"${project.property("ZCASH_FLEXA_KEY")?.toString().orEmpty()}\"",
                comment = "Publishable key of the Flexa integration"
            )
        )
        variant.buildConfigFields?.put(
            "ZCASH_CMC_KEY",
            BuildConfigField(
                type = "String",
                value = "\"${project.property("ZCASH_CMC_KEY")?.toString().orEmpty()}\"",
                comment = "Publishable key of the CMC integration"
            )
        )
        // To configure screen orientation in runtime
        variant.buildConfigFields?.put(
            "IS_SCREEN_ROTATION_ENABLED",
            BuildConfigField(
                type = "boolean",
                value = project.property("IS_SCREEN_ROTATION_ENABLED").toString(),
                comment = "Whether is the screen rotation enabled, otherwise, it's locked in the portrait mode"
            )
        )
        // UPI offramp config — selects the on-chain p2p.me network + endpoints.
        variant.buildConfigFields?.put(
            "P2P_NETWORK",
            BuildConfigField(
                type = "String",
                value = "\"${project.property("P2P_NETWORK")?.toString().orEmpty()}\"",
                comment = "UPI offramp p2p.me network: 'sepolia' or 'mainnet'"
            )
        )
        variant.buildConfigFields?.put(
            "P2P_ONRAMP_BASE_URL",
            BuildConfigField(
                type = "String",
                value = "\"${project.property("P2P_ONRAMP_BASE_URL")?.toString().orEmpty()}\"",
                comment = "Zapp onramp service base URL; the operator account places every BUY there"
            )
        )
        variant.buildConfigFields?.put(
            "P2P_ONRAMP_USE_FAKE_DRIVER",
            BuildConfigField(
                type = "boolean",
                value = project.property("P2P_ONRAMP_USE_FAKE_DRIVER").toString().toBoolean().toString(),
                comment = "Debug-only: drive the onramp screens off FakeOnrampDriver instead of the network"
            )
        )
        variant.buildConfigFields?.put(
            "P2P_ONRAMP_AUTO_ZEC_ENABLED",
            BuildConfigField(
                type = "boolean",
                value = project.property("P2P_ONRAMP_AUTO_ZEC_ENABLED").toString().toBoolean().toString(),
                comment = "Whether new P2P onramps may automatically deliver ZEC"
            )
        )
        variant.buildConfigFields?.put(
            "P2P_RPC_URL_BASE_SEPOLIA",
            BuildConfigField(
                type = "String",
                value = "\"${project.property("P2P_RPC_URL_BASE_SEPOLIA")?.toString().orEmpty()}\"",
                comment = "Base Sepolia JSON-RPC endpoint used when P2P_NETWORK=sepolia"
            )
        )
        variant.buildConfigFields?.put(
            "P2P_RPC_URL_BASE_MAINNET",
            BuildConfigField(
                type = "String",
                value = "\"${project.property("P2P_RPC_URL_BASE_MAINNET")?.toString().orEmpty()}\"",
                comment = "Base mainnet JSON-RPC endpoint used when P2P_NETWORK=mainnet"
            )
        )
        variant.buildConfigFields?.put(
            "P2P_SUBGRAPH_URL_SEPOLIA",
            BuildConfigField(
                type = "String",
                value = "\"${project.property("P2P_SUBGRAPH_URL_SEPOLIA")?.toString().orEmpty()}\"",
                comment = "Sepolia subgraph URL used when P2P_NETWORK=sepolia"
            )
        )
        variant.buildConfigFields?.put(
            "P2P_SUBGRAPH_URL_MAINNET",
            BuildConfigField(
                type = "String",
                value = "\"${project.property("P2P_SUBGRAPH_URL_MAINNET")?.toString().orEmpty()}\"",
                comment = "Mainnet subgraph URL used when P2P_NETWORK=mainnet"
            )
        )
        variant.buildConfigFields?.put(
            "NTFY_BASE_URL",
            BuildConfigField(
                type = "String",
                value = "\"${project.property("NTFY_BASE_URL")?.toString().orEmpty()}\"",
                comment = "Self-hosted ntfy base URL for the embedded push doorbell"
            )
        )
        variant.buildConfigFields?.put(
            "PIMLICO_API_KEY",
            BuildConfigField(
                type = "String",
                value = "\"${project.property("PIMLICO_API_KEY")?.toString().orEmpty()}\"",
                comment = "Pimlico API key for the offramp ERC-4337 bundler + verifying paymaster"
            )
        )
        variant.buildConfigFields?.put(
            "PIMLICO_SPONSORSHIP_POLICY_ID",
            BuildConfigField(
                type = "String",
                value = "\"${project.property("PIMLICO_SPONSORSHIP_POLICY_ID")?.toString().orEmpty()}\"",
                comment = "Optional Pimlico sponsorship-policy id; when non-blank, scopes " +
                    "pm_sponsorUserOperation to the policy's (target, selector) constraints."
            )
        )
        variant.buildConfigFields?.put(
            "OFFRAMP_USE_DEV_KEY",
            BuildConfigField(
                type = "boolean",
                value = project.property("OFFRAMP_USE_DEV_KEY").toString().toBoolean().toString(),
                comment = "When true, the offramp uses a hardcoded dev EVM key (shared smart account); " +
                    "when false, derives the owner from the user's wallet seed (per-user smart account)."
            )
        )
    }
}

dependencies {
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.splash)
    implementation(libs.androidx.workmanager)
    implementation(libs.androidx.browser)
    implementation(libs.bundles.androidx.camera)
    implementation(libs.bundles.androidx.compose.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.play.services.location)
    implementation(libs.bundles.androidx.compose.extended)
    api(libs.bundles.koin)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.immutable)
    implementation(libs.kotlinx.serializable.json)
    add("storeImplementation", platform(libs.firebase.bom))
    add("storeImplementation", libs.firebase.messaging)
    add("internalImplementation", platform(libs.firebase.bom))
    add("internalImplementation", libs.firebase.messaging)
    "storeImplementation"(libs.mlkit.scanning)
    "internalImplementation"(libs.mlkit.scanning)
    // All flavors: the gallery-upload QR decode (ImageUriToQrCodeConverter) shares the live
    // analyzer's zxing-cpp reader; foss additionally uses it for the camera analyzer.
    implementation(libs.zxingcpp)
    api(libs.zcash.sdk)
    implementation(libs.zcash.sdk.incubator)
    implementation(libs.zcash.bip39)
    implementation(libs.tink)
    implementation(libs.zxing)

    api(libs.flexa.core)
    api(libs.flexa.spend)

    implementation(projects.buildInfoLib)
    implementation(projects.configurationApiLib)
    implementation(projects.crashAndroidLib)
    implementation(projects.preferenceApiLib)
    implementation(projects.preferenceImplAndroidLib)
    implementation(projects.spackleAndroidLib)
    api(projects.configurationImplAndroidLib)
    api(projects.sdkExtLib)
    api(projects.uiDesignLib)
    implementation(projects.offrampLib)
    api(libs.androidx.fragment)
    api(libs.androidx.fragment.compose)
    api(libs.androidx.activity)
    api(libs.bundles.androidx.biometric)

    api(libs.keystone)

    // ZappMessaging P2P SDK
    implementation(project(":zappmessaging"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.mock)

    androidTestImplementation(projects.testLib)
    androidTestImplementation(libs.bundles.androidx.test)
    androidTestImplementation(libs.androidx.compose.test.junit)
    androidTestImplementation(libs.androidx.compose.test.manifest)
    androidTestImplementation(libs.kotlin.reflect)
    androidTestImplementation(libs.kotlin.test)

    androidTestUtil(libs.androidx.test.services) {
        artifact {
            type = "apk"
        }
    }

    if (project.property("IS_USE_TEST_ORCHESTRATOR").toString().toBoolean()) {
        androidTestUtil(libs.androidx.test.services)
        androidTestUtil(libs.androidx.test.orchestrator) {
            artifact {
                type = "apk"
            }
        }
    }
}
