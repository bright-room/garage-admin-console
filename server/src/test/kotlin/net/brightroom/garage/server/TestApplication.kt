package net.brightroom.garage.server

import io.ktor.client.engine.mock.MockEngine
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import net.brightroom.garage.server.api.bucketRoutes
import net.brightroom.garage.server.api.keyRoutes
import net.brightroom.garage.server.api.objectRoutes
import net.brightroom.garage.server.api.sessionRoutes
import net.brightroom.garage.server.config.AppConfig
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.plugins.configureSerialization
import net.brightroom.garage.server.plugins.configureStatusPages
import net.brightroom.garage.server.s3.S3CredentialResolver
import net.brightroom.garage.server.s3.S3ObjectStore
import net.brightroom.garage.server.s3.SecretCache

/**
 * Garage をモックしたアプリケーションを組み立てる。
 *
 * 静的ファイル配信と CallLogging は各ルートのテストに不要なため含めない。
 */
fun ApplicationTestBuilder.garageApp(engine: MockEngine, cache: SecretCache = SecretCache()) {
    environment {
        config = MapApplicationConfig(
            "garage.admin.endpoint" to "http://garage.test:3903",
            "garage.s3.endpoint" to "http://garage.test:3900",
            "garage.s3.region" to "garage",
            "garage.s3.pathStyle" to "true",
        )
    }
    application {
        val client = GarageAdminClient("http://garage.test:3903", engine)
        val s3Config = AppConfig.S3(
            endpoint = "http://garage.test:3900",
            region = "garage",
            pathStyle = true,
        )
        configureSerialization()
        configureStatusPages()
        routing {
            route("/api") {
                sessionRoutes(client, cache)
                bucketRoutes(client)
                keyRoutes(client)
                objectRoutes(client, S3CredentialResolver(client, cache), S3ObjectStore(s3Config))
            }
        }
    }
}
