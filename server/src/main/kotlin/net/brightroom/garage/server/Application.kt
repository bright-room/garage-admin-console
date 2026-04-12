package net.brightroom.garage.server

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import net.brightroom.garage.server.plugins.*

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(CIO, port = port) {
        configureDi()
        configureSerialization()
        configureStatusPages()
        configureRouting()
        configureStaticFiles()
    }.start(wait = true)
}
