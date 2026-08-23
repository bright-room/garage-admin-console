package net.brightroom.garage.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.brightroom.garage.server.api.OverviewService
import net.brightroom.garage.server.api.bucketRoutes
import net.brightroom.garage.server.api.keyRoutes
import net.brightroom.garage.server.api.objectRoutes
import net.brightroom.garage.server.api.overviewRoutes
import net.brightroom.garage.server.api.sessionRoutes
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.s3.S3CredentialResolver
import net.brightroom.garage.server.s3.S3ObjectStore
import net.brightroom.garage.server.s3.SecretCache

fun Application.configureRouting() {
    val client: GarageAdminClient by dependencies
    val overviewService: OverviewService by dependencies
    val cache: SecretCache by dependencies
    val resolver: S3CredentialResolver by dependencies
    val objectStore: S3ObjectStore by dependencies

    routing {
        route("/api") {
            sessionRoutes(client, cache)
            overviewRoutes(overviewService)
            bucketRoutes(client)
            keyRoutes(client)
            objectRoutes(client, resolver, objectStore)
        }
    }
}
