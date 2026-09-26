plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
    id("secant.android-build-conventions")
    id("secant.jacoco-conventions")
}

android {
    namespace = "xyz.justzappit.railgun"
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serializable.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
