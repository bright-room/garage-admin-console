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
import net.brightroom.garage.server.s3.S3Credentials
import net.brightroom.garage.server.s3.SecretCache
import net.brightroom.garage.server.s3.hashToken
import net.brightroom.garage.shared.api.ProblemDetails
import net.brightroom.garage.shared.api.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun normalisesGarageForbiddenIntoUnauthorized() = testApplication {
        // Garage v2.3.0 は無効な bearer token に 401 ではなく 403 を返す。
        // GetCurrentAdminTokenInfo は scope 限定のトークンでも許可されるため、
        // この 403 は「トークンが無効」を意味する。401 に正規化して
        // web がログイン画面へ戻せるようにする。
        garageApp(
            MockEngine {
                respond(
                    """{"code":"AccessDenied","message":"Forbidden: Invalid bearer token"}""",
                    HttpStatusCode.Forbidden,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/session") {
            header(HttpHeaders.Authorization, "Bearer wrong")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val problem: ProblemDetails = GarageJson.decodeFromString(response.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized.value, problem.status)
        assertEquals("GetCurrentAdminTokenInfo", problem.operation)
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

    @Test
    fun logoutPurgesCachedCredentialsOfThatTokenOnly() = testApplication {
        val credentials = S3Credentials(
            accessKeyId = "GK01",
            secretAccessKey = "s3cr3t",
            keyName = "dev-key",
            bucketName = "dev-bucket",
        )
        val cache = SecretCache()
        cache.put(hashToken("tok-abc"), "b1", credentials)
        cache.put(hashToken("tok-other"), "b1", credentials)
        garageApp(MockEngine { respond("", HttpStatusCode.OK) }, cache)

        val response = client.post("/api/session/logout") {
            header(HttpHeaders.Authorization, "Bearer tok-abc")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertNull(cache.get(hashToken("tok-abc"), "b1"))
        // 他のトークンのエントリは残る（purge がトークン単位であることの確認）
        assertEquals(credentials, cache.get(hashToken("tok-other"), "b1"))
    }
}
