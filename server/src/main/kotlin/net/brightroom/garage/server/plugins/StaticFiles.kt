package net.brightroom.garage.server.plugins

import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*

fun Application.configureStaticFiles() {
    routing {
        staticResources("/", "web") {
            default("index.html")
        }
    }
}
