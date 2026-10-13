plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
    id("secant.android-build-conventions")
    id("secant.jacoco-conventions")
}

android {
    namespace = "xyz.justzappit.railgun"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "ETHEREUM_RPC_URL_MAINNET", "\"${project.property("ETHEREUM_RPC_URL_MAINNET")}\"")
        buildConfigField("String", "ETHEREUM_RPC_URL_SEPOLIA", "\"${project.property("ETHEREUM_RPC_URL_SEPOLIA")}\"")
    }
}

dependencies {
    api(projects.evmLib)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serializable.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
