import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.ditarector.diligent"
version = "0.1.0"

kotlin {
    // Three apps consume this API, so every public declaration has to be
    // marked public on purpose; an accidental export becomes a compile error.
    explicitApi()

    // Android and the desktop app both run on the JVM. Bytecode 17 matches
    // the Android app's own jvmTarget.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // The iPhone app. Linux CI runners cannot build these; gradle.properties
    // sets kotlin.native.ignoreDisabledTargets so they are skipped there.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
