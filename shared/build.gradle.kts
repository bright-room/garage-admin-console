@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // :server の jvmToolchain(21) と揃える。揃えないと Gradle デーモンの JVM が
    // 21 より新しい環境で :server:test が UnsupportedClassVersionError になる。
    jvmToolchain(21)

    jvm()
    wasmJs {
        browser()
    }

    sourceSets {
        // api は使わない。:server と :web には必要な依存を明示的に宣言させる。
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
