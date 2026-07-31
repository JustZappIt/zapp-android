plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("secant.kotlin-multiplatform-build-conventions")
    id("secant.dependency-conventions")

    id("org.jetbrains.kotlinx.kover")
    id("secant.kover-conventions")
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }
    applyDefaultHierarchyTemplate()
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serializable.json)
                implementation(project.dependencies.enforcedPlatform(libs.ktor.bom))
                implementation(libs.ktor.core)
                implementation(libs.ktor.negotiation)
                implementation(libs.ktor.json)
                implementation(libs.ktor.logging)
                implementation(libs.kmp.bignum)
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
                implementation(libs.bouncycastle.bcprov)
                implementation(project.dependencies.enforcedPlatform(libs.ktor.bom))
                implementation(libs.ktor.okhttp)
            }
        }
        getByName("iosMain") {
            dependencies {
                implementation(project.dependencies.enforcedPlatform(libs.ktor.bom))
                implementation(libs.ktor.darwin)
                implementation(libs.kmp.secp256k1)
            }
        }
    }
}
