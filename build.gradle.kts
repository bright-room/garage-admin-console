plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ktor) apply false
}

// ktor-client-core が npm 依存として要求する ws 8.18.3 に既知脆弱性
// (GHSA-96hv-2xvq-fx4p / GHSA-58qx-3vcg-4xpx) があるため修正版へ強制解決する。
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension>().resolution("ws", "8.21.0")
}
