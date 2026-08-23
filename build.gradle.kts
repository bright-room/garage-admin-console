plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.spotless)
}

val ktlintRules =
    mapOf(
        "ktlint_code_style" to "intellij_idea",
        "max_line_length" to "120",
        "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
    )

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint().editorConfigOverride(ktlintRules)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint().editorConfigOverride(ktlintRules)
    }
}

// ktor-client-core が npm 依存として要求する ws 8.18.3 に既知脆弱性
// (GHSA-96hv-2xvq-fx4p / GHSA-58qx-3vcg-4xpx) があるため修正版へ強制解決する。
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension>().resolution("ws", "8.21.0")
}
