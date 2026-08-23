plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.spotless)
}

// ktlint 既定の ktlint_official は式本体や単一引数コンストラクタを強制改行し、
// gradle.properties の kotlin.code.style=official (= IntelliJ official) で書かれた
// 既存コードと衝突する。宣言済みのスタイルに合わせる。
val ktlintRules =
    mapOf(
        "ktlint_code_style" to "intellij_idea",
        // 未設定だと ktlint は行長無制限とみなし、意図的に折られた式を
        // 150 文字超の 1 行に連結してしまう。既存コードは全行 105 文字以内。
        "max_line_length" to "120",
        // @Composable は PascalCase が Compose 公式の慣習。
        "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
    )

// ルートに一括適用する。モジュールごとの設定差は無いため、各サブプロジェクトへ
// プラグインを配るより glob 一つで対象を指定するほうが単純。
spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRules)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRules)
    }
}

// ktor-client-core が npm 依存として要求する ws 8.18.3 に既知脆弱性
// (GHSA-96hv-2xvq-fx4p / GHSA-58qx-3vcg-4xpx) があるため修正版へ強制解決する。
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension>().resolution("ws", "8.21.0")
}
