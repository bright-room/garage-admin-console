package net.brightroom.garage.server.api

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.overviewRoutes(service: OverviewService) {
    get("/overview") {
        call.respond(service.build(call.adminToken()))
    }
}
