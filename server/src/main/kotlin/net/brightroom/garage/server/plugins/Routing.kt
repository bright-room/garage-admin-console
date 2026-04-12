package net.brightroom.garage.server.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import net.brightroom.garage.server.routes.*
import net.brightroom.garage.server.service.GarageAdminClient
import net.brightroom.garage.server.service.GarageS3Service

fun Application.configureRouting() {
    val adminClient: GarageAdminClient by dependencies
    val s3Service: GarageS3Service by dependencies

    routing {
        route("/api") {
            clusterRoutes(adminClient)
            layoutRoutes(adminClient)
            bucketRoutes(adminClient)
            keyRoutes(adminClient)
            adminTokenRoutes(adminClient)
            nodeRoutes(adminClient)
            workerRoutes(adminClient)
            blockRoutes(adminClient)
            s3Routes(s3Service)
        }
    }
}
