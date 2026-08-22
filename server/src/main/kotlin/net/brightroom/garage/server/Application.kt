package net.brightroom.garage.server

import io.ktor.server.application.Application
import io.ktor.server.cio.EngineMain
import net.brightroom.garage.server.plugins.configureSerialization

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureSerialization()
}
