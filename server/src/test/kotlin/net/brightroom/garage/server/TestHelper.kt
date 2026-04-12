package net.brightroom.garage.server

import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import net.brightroom.garage.server.config.AppConfig
import net.brightroom.garage.server.service.GarageAdminClient

val testAppConfig = AppConfig(
    garageAdminEndpoint = "http://localhost:9999",
    garageAdminToken = "test-token",
    garageS3Endpoint = "http://localhost:9998",
    garageS3AccessKeyId = "test-access-key",
    garageS3SecretAccessKey = "test-secret-key",
    garageS3Region = "test-region",
)

fun createMockAdminClient(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): GarageAdminClient {
    val mockEngine = MockEngine(handler)
    return GarageAdminClient(testAppConfig, mockEngine)
}
