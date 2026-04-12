package net.brightroom.garage.server.service

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import net.brightroom.garage.server.config.AppConfig

class GarageAdminClient(
    private val config: AppConfig,
    engine: HttpClientEngine = CIO.create(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(engine) {
        install(ContentNegotiation) {
            json(json)
        }
        defaultRequest {
            url(config.garageAdminEndpoint)
            header(HttpHeaders.Authorization, "Bearer ${config.garageAdminToken}")
            contentType(ContentType.Application.Json)
        }
    }

    suspend fun get(path: String): HttpResponse =
        client.get(path)

    suspend fun post(path: String, body: String? = null): HttpResponse =
        client.post(path) {
            if (body != null) {
                setBody(body)
            }
        }

    fun close() {
        client.close()
    }
}
