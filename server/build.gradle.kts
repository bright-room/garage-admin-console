plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("net.brightroom.garage.server.ApplicationKt")
}

ktor {
    fatJar {
        archiveFileName.set("garage-admin-console-all.jar")
    }
}

dependencies {
    implementation(project(":shared"))

    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.di)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.config.yaml)

    // Ktor Client (for proxying to Garage)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Serialization
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    // AWS SDK S3
    implementation(libs.aws.sdk.s3)

    // Logging
    implementation(libs.logback.classic)
}

tasks.named("processResources") {
    dependsOn(":web:wasmJsBrowserDistribution")
}

tasks.named<Copy>("processResources") {
    from(project(":web").layout.buildDirectory.dir("dist/wasmJs/productionExecutable")) {
        into("web")
    }
}
