package net.brightroom.garage.server

import io.ktor.server.cio.EngineMain
import net.brightroom.garage.server.plugins.*

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun io.ktor.server.application.Application.module() {
    configureDi()
    configureSerialization()
    configureStatusPages()
    configureRouting()
    configureStaticFiles()
}
