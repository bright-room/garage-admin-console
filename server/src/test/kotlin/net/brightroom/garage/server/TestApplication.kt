package net.brightroom.garage.server

import io.ktor.client.engine.mock.MockEngine
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.plugins.configureSerialization
import net.brightroom.garage.server.plugins.configureStatusPages
import io.ktor.server.routing.routing
import io.ktor.server.routing.route
import net.brightroom.garage.server.api.sessionRoutes

/**
 * Garage をモックしたアプリケーションを組み立てる。
 *
 * 静的ファイル配信と CallLogging は各ルートのテストに不要なため含めない。
 */
fun ApplicationTestBuilder.garageApp(engine: MockEngine) {
    environment {
        config = MapApplicationConfig("garage.admin.endpoint" to "http://garage.test:3903")
    }
    application {
        val client = GarageAdminClient("http://garage.test:3903", engine)
        configureSerialization()
        configureStatusPages()
        routing {
            route("/api") {
                sessionRoutes(client)
            }
        }
    }
}
