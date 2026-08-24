package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.CreatedAdminToken
import net.brightroom.garage.shared.model.garage.AdminToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdminTokenRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun listsTokensIncludingConfigurationDerivedOnes() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(
                    """[{"id":null,"created":null,"name":"admin_token (from daemon configuration)",
                         "expiration":null,"expired":false,"scope":["*"]},
                        {"id":"29251efb","created":"2026-08-24T08:38:16.773Z","name":"dev-limited",
                         "expiration":null,"expired":false,"scope":["ListBuckets"]}]""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/admin-tokens") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ListAdminTokens", operation)
        val tokens = GarageJson.decodeFromString<List<AdminToken>>(response.bodyAsText())
        // 設定ファイル由来のトークンは id を持たない。隠さずに返す（P3-6）
        assertNull(tokens[0].id)
        assertEquals("29251efb", tokens[1].id)
    }

    @Test
    fun getsTokenById() = testApplication {
        var operation = ""
        var id: String? = null
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                id = request.url.parameters["id"]
                respond(
                    """{"id":"29251efb","created":"2026-08-24T08:38:16.773Z","name":"dev-limited",
                        "expiration":null,"expired":false,"scope":["ListBuckets"]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/admin-tokens/29251efb") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("GetAdminTokenInfo", operation)
        assertEquals("29251efb", id)
        val token = GarageJson.decodeFromString<AdminToken>(response.bodyAsText())
        assertEquals("dev-limited", token.name)
        assertEquals(listOf("ListBuckets"), token.scope)
    }

    @Test
    fun createsTokenAndReturnsSecretOnce() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(
                    """{"id":"new1","created":"2026-08-24T09:00:00Z","name":"alice",
                        "expiration":null,"expired":false,"scope":["ListBuckets"],
                        "secretToken":"secret-value"}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.post("/api/admin-tokens") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"alice","scope":["ListBuckets"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        // expiration を省略したら無期限として送る
        assertEquals("""{"name":"alice","scope":["ListBuckets"],"neverExpires":true}""", sent)
        val created = GarageJson.decodeFromString<CreatedAdminToken>(response.bodyAsText())
        assertEquals("secret-value", created.secretToken)
        assertEquals("alice", created.token.name)
    }

    @Test
    fun createsTokenWithExpiration() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(
                    """{"id":"new2","name":"bob","expiration":"2026-12-31T00:00:00Z",
                        "expired":false,"scope":["*"],"secretToken":"s"}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        client.post("/api/admin-tokens") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"bob","scope":["*"],"expiration":"2026-12-31T00:00:00Z"}""")
        }

        assertEquals("""{"name":"bob","scope":["*"],"expiration":"2026-12-31T00:00:00Z"}""", sent)
    }

    @Test
    fun rejectsBlankTokenName() = testApplication {
        garageApp(MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/admin-tokens") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"  ","scope":["*"]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun rejectsEmptyScope() = testApplication {
        garageApp(MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/admin-tokens") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"alice","scope":[]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun updatesOnlyGivenFields() = testApplication {
        var sent = ""
        var id: String? = null
        garageApp(
            MockEngine { request ->
                id = request.url.parameters["id"]
                sent = (request.body as TextContent).text
                respond(
                    """{"id":"t1","name":"alice2","expiration":null,"expired":false,"scope":["*"]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.patch("/api/admin-tokens/t1") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"alice2"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("t1", id)
        // 省略したフィールドは送らない。Garage の「省略 = 変更しない」に合わせる
        assertEquals("""{"name":"alice2"}""", sent)
    }

    @Test
    fun updatesToNeverExpire() = testApplication {
        var sent = ""
        garageApp(
            MockEngine { request ->
                sent = (request.body as TextContent).text
                respond(
                    """{"id":"t1","name":"alice","expiration":null,"expired":false,"scope":["*"]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        client.patch("/api/admin-tokens/t1") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"neverExpires":true,"expiration":"2026-12-31T00:00:00Z"}""")
        }

        // neverExpires が真なら expiration は送らない
        assertEquals("""{"neverExpires":true}""", sent)
    }

    @Test
    fun deletesToken() = testApplication {
        var operation = ""
        var id: String? = null
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                id = request.url.parameters["id"]
                respond("", HttpStatusCode.OK)
            },
        )

        val response = client.delete("/api/admin-tokens/t1") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("DeleteAdminToken", operation)
        assertEquals("t1", id)
    }
}
