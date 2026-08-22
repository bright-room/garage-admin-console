package net.brightroom.garage.server.plugins

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import net.brightroom.garage.server.api.MissingTokenException
import net.brightroom.garage.server.garage.GarageException
import net.brightroom.garage.shared.api.ProblemDetails
import kotlin.test.Test
import kotlin.test.assertEquals

class StatusPagesTest {

    private fun problemOf(body: String): ProblemDetails =
        GarageJson.decodeFromString(body)

    @Test
    fun normalisesForbiddenFromGarage() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") {
                    throw GarageException(
                        status = HttpStatusCode.Forbidden,
                        operation = "GetKeyInfo",
                        message = "insufficient scope",
                    )
                }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val problem = problemOf(response.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, problem.status)
        assertEquals("Forbidden", problem.title)
        assertEquals("insufficient scope", problem.detail)
        assertEquals("GetKeyInfo", problem.operation)
        assertEquals("/boom", problem.instance)
    }

    @Test
    fun usesProblemJsonMediaType() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") { throw MissingTokenException() }
            }
        }

        val response = client.get("/boom")

        // RFC 9457 の定める media type
        assertEquals("application/problem+json", response.contentType()?.withoutParameters()?.toString())
    }

    @Test
    fun missingTokenBecomesUnauthorized() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") { throw MissingTokenException() }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(HttpStatusCode.Unauthorized, problemOf(response.bodyAsText()).status)
    }

    @Test
    fun unexpectedExceptionBecomesInternalError() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") { throw IllegalStateException("unexpected") }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val problem = problemOf(response.bodyAsText())
        assertEquals(HttpStatusCode.InternalServerError, problem.status)
        assertEquals("Internal Server Error", problem.title)
        // 内部エラーの詳細は外に出さない
        assertEquals(null, problem.operation)
    }

    @Test
    fun unknownApiPathReturnsNormalisedNotFound() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/ok") { call.respondText("ok") }
            }
        }

        val response = client.get("/api/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(HttpStatusCode.NotFound, problemOf(response.bodyAsText()).status)
    }

    @Test
    fun omitsTypeMemberSoItDefaultsToAboutBlank() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/boom") { throw MissingTokenException() }
            }
        }

        val body = client.get("/boom").bodyAsText()

        assertEquals(false, body.contains("\"type\""))
    }
}
