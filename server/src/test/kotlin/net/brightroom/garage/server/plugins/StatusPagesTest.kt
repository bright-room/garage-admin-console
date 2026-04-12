package net.brightroom.garage.server.plugins

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class StatusPagesTest {

    @Test
    fun handleNotFound() = testApplication {
        install(ContentNegotiation) { json() }
        application { configureStatusPages() }

        client.get("/nonexistent").apply {
            assertEquals(HttpStatusCode.NotFound, status)
            val body = Json.decodeFromString<ErrorResponse>(bodyAsText())
            assertEquals("Not found", body.error)
        }
    }

    @Test
    fun handleIllegalArgumentException() = testApplication {
        install(ContentNegotiation) { json() }
        application { configureStatusPages() }
        routing {
            get("/bad") {
                throw IllegalArgumentException("Invalid input")
            }
        }

        client.get("/bad").apply {
            assertEquals(HttpStatusCode.BadRequest, status)
            val body = Json.decodeFromString<ErrorResponse>(bodyAsText())
            assertEquals("Invalid input", body.error)
        }
    }

    @Test
    fun handleIllegalStateException() = testApplication {
        install(ContentNegotiation) { json() }
        application { configureStatusPages() }
        routing {
            get("/conflict") {
                throw IllegalStateException("Resource conflict")
            }
        }

        client.get("/conflict").apply {
            assertEquals(HttpStatusCode.Conflict, status)
            val body = Json.decodeFromString<ErrorResponse>(bodyAsText())
            assertEquals("Resource conflict", body.error)
        }
    }

    @Test
    fun handleGenericException() = testApplication {
        install(ContentNegotiation) { json() }
        application { configureStatusPages() }
        routing {
            get("/error") {
                throw RuntimeException("Something broke")
            }
        }

        client.get("/error").apply {
            assertEquals(HttpStatusCode.InternalServerError, status)
            val body = Json.decodeFromString<ErrorResponse>(bodyAsText())
            assertEquals("Something broke", body.error)
        }
    }

    @Test
    fun errorResponseSerialization() {
        val error = ErrorResponse("test error")
        val encoded = Json.encodeToString(error)
        val decoded = Json.decodeFromString<ErrorResponse>(encoded)
        assertEquals("test error", decoded.error)
    }
}
