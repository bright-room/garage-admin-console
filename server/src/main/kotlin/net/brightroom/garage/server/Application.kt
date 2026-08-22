package net.brightroom.garage.server

import io.ktor.server.application.Application
import io.ktor.server.cio.EngineMain
import net.brightroom.garage.server.plugins.configureCallLogging
import net.brightroom.garage.server.plugins.configureDi
import net.brightroom.garage.server.plugins.configureRouting
import net.brightroom.garage.server.plugins.configureSerialization
import net.brightroom.garage.server.plugins.configureStatusPages

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureCallLogging()
    configureSerialization()
    configureDi()
    configureStatusPages()
    configureRouting()
}
