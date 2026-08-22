package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.ProblemDetails
import net.brightroom.garage.shared.api.Session
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun returnsSessionForValidToken() = testApplication {
        var forwardedAuth: String? = null
        garageApp(
            MockEngine { request ->
                forwardedAuth = request.headers[HttpHeaders.Authorization]
                respond(
                    """{"id":"tok1","name":"alice","scope":["ListBuckets"],"expired":false}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/session") {
            header(HttpHeaders.Authorization, "Bearer tok-abc")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val session: Session = GarageJson.decodeFromString(response.bodyAsText())
        assertEquals("alice", session.name)
        assertEquals(listOf("ListBuckets"), session.scope)
        assertEquals("Bearer tok-abc", forwardedAuth)
    }

    @Test
    fun rejectsRequestWithoutToken() = testApplication {
        garageApp(MockEngine { respond("", HttpStatusCode.OK) })

        val response = client.get("/api/session")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val problem: ProblemDetails = GarageJson.decodeFromString(response.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized.value, problem.status)
    }

    @Test
    fun rejectsMalformedAuthorizationHeader() = testApplication {
        garageApp(MockEngine { respond("", HttpStatusCode.OK) })

        val response = client.get("/api/session") {
            header(HttpHeaders.Authorization, "tok-abc")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun propagatesUnauthorizedFromGarage() = testApplication {
        garageApp(MockEngine { respond("invalid token", HttpStatusCode.Unauthorized) })

        val response = client.get("/api/session") {
            header(HttpHeaders.Authorization, "Bearer wrong")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val problem: ProblemDetails = GarageJson.decodeFromString(response.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized.value, problem.status)
        assertEquals("GetCurrentAdminTokenInfo", problem.operation)
        assertEquals("invalid token", problem.detail)
    }

    @Test
    fun logoutSucceedsWithoutCallingGarage() = testApplication {
        var garageCalled = false
        garageApp(
            MockEngine {
                garageCalled = true
                respond("", HttpStatusCode.OK)
            },
        )

        val response = client.post("/api/session/logout") {
            header(HttpHeaders.Authorization, "Bearer tok-abc")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(false, garageCalled)
    }
}
