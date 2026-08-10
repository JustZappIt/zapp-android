import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("secant.kotlin-multiplatform-build-conventions")
    id("secant.dependency-conventions")
    id("co.touchlab.skie")

    id("org.jetbrains.kotlinx.kover")
    id("secant.kover-conventions")
}

kotlin {
    jvm()
    val xcf = XCFramework("ZappOfframp")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "ZappOfframp"
            isStatic = true
            binaryOption("bundleId", "xyz.justzappit.ZappOfframp")
            export(projects.evmLib)
            xcf.add(this)
        }
    }
    applyDefaultHierarchyTemplate()
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serializable.json)
                api(projects.evmLib)
                implementation(project.dependencies.enforcedPlatform(libs.ktor.bom))
                implementation(libs.ktor.core)
                implementation(libs.ktor.negotiation)
                implementation(libs.ktor.json)
                implementation(libs.kmp.cryptography.core)
                implementation(libs.kmp.cryptography.provider)
                implementation(libs.kmp.cryptography.random)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.mock)
            }
        }
        getByName("jvmMain") {
            dependencies {
                implementation(project.dependencies.enforcedPlatform(libs.ktor.bom))
                implementation(libs.ktor.okhttp)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(project.dependencies.enforcedPlatform(libs.ktor.bom))
                implementation(libs.ktor.okhttp)
            }
        }
        getByName("iosMain") {
            dependencies {
                implementation(project.dependencies.enforcedPlatform(libs.ktor.bom))
                implementation(libs.ktor.darwin)
            }
        }
    }
}
