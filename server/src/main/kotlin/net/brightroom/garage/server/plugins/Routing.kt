package net.brightroom.garage.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.brightroom.garage.server.api.OverviewService
import net.brightroom.garage.server.api.bucketRoutes
import net.brightroom.garage.server.api.overviewRoutes
import net.brightroom.garage.server.api.sessionRoutes
import net.brightroom.garage.server.garage.GarageAdminClient

fun Application.configureRouting() {
    val client: GarageAdminClient by dependencies
    val overviewService: OverviewService by dependencies

    routing {
        route("/api") {
            sessionRoutes(client)
            overviewRoutes(overviewService)
            bucketRoutes(client)
        }
    }
}
