package net.brightroom.garage.web.api

import io.ktor.client.*
import io.ktor.client.engine.js.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

object ApiClient {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(Js) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun get(path: String): String =
        client.get("/api$path").bodyAsText()

    suspend fun post(path: String, body: String? = null): String =
        client.post("/api$path") {
            contentType(ContentType.Application.Json)
            if (body != null) setBody(body)
        }.bodyAsText()

    suspend fun delete(path: String, body: String? = null): String =
        client.delete("/api$path") {
            contentType(ContentType.Application.Json)
            if (body != null) setBody(body)
        }.bodyAsText()
}
