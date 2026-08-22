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

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.di)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.config.yaml)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.named("processResources") {
    dependsOn(":web:wasmJsBrowserDistribution")
}

tasks.named<Copy>("processResources") {
    from(project(":web").layout.buildDirectory.dir("dist/wasmJs/productionExecutable")) {
        into("web")
    }
}
