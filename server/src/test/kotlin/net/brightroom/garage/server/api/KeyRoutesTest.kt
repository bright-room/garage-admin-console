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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.model.garage.KeyInfo
import net.brightroom.garage.shared.model.garage.KeySummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val json = Json { ignoreUnknownKeys = true }

    private val keyInfoBody = """
        {"accessKeyId":"GK01","name":"dev-key","expired":false,
         "permissions":{"createBucket":false},"buckets":[]}
    """.trimIndent()

    @Test
    fun listsKeys() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond("""[{"id":"GK01","name":"dev-key","expired":false}]""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.get("/api/keys") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals("ListKeys", operation)
        val keys = GarageJson.decodeFromString<List<KeySummary>>(response.bodyAsText())
        assertEquals("dev-key", keys.single().name)
    }

    @Test
    fun getsKeyWithoutSecretByDefault() = testApplication {
        var operation = ""
        var showSecret: String? = "unset"
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                showSecret = request.url.parameters["showSecretKey"]
                respond(keyInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.get("/api/keys/GK01") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("GetKeyInfo", operation)
        assertNull(showSecret)
    }

    @Test
    fun getsKeyWithSecretWhenAsked() = testApplication {
        var operation = ""
        var showSecret: String? = null
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                showSecret = request.url.parameters["showSecretKey"]
                respond(
                    """{"accessKeyId":"GK01","name":"dev-key","expired":false,
                        "secretAccessKey":"s3cr3t","permissions":{},"buckets":[]}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/keys/GK01?showSecret=true") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals("GetKeyInfo", operation)
        assertEquals("true", showSecret)
        val info = GarageJson.decodeFromString<KeyInfo>(response.bodyAsText())
        assertEquals("s3cr3t", info.secretAccessKey)
    }

    @Test
    fun createsKeyWithCreateBucketPermission() = testApplication {
        var sentBody = ""
        var operation = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(keyInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.post("/api/keys") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"ci","allowCreateBucket":true}""")
        }

        assertEquals("CreateKey", operation)
        val sent = json.decodeFromString<JsonObject>(sentBody)
        assertEquals("ci", sent["name"]?.toString()?.trim('"'))
        assertTrue(sent["allow"].toString().contains("\"createBucket\":true"))
        assertNull(sent["deny"])
        // 期限を指定しなければ無期限
        assertEquals("true", sent["neverExpires"]?.toString())
    }

    @Test
    fun createsKeyWithExplicitExpiration() = testApplication {
        var sentBody = ""
        var operation = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(keyInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.post("/api/keys") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"ci","expiration":"2027-01-01T00:00:00Z"}""")
        }

        assertEquals("CreateKey", operation)
        val sent = json.decodeFromString<JsonObject>(sentBody)
        // RFC 3339 のまま Garage に渡ること
        assertEquals("2027-01-01T00:00:00Z", sent["expiration"]?.toString()?.trim('"'))
        assertNull(sent["neverExpires"])
    }

    @Test
    fun importsKey() = testApplication {
        var sentBody = ""
        var operation = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(keyInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/keys/import") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"restored","accessKeyId":"GK99","secretAccessKey":"old-secret"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ImportKey", operation)
        val sent = json.decodeFromString<JsonObject>(sentBody)
        assertEquals("GK99", sent["accessKeyId"]?.toString()?.trim('"'))
        assertEquals("old-secret", sent["secretAccessKey"]?.toString()?.trim('"'))
    }

    @Test
    fun deniesCreateBucketWhenTurnedOff() = testApplication {
        var sentBody = ""
        var operation = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(keyInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.patch("/api/keys/GK01") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"allowCreateBucket":false}""")
        }

        assertEquals("UpdateKey", operation)
        val sent = json.decodeFromString<JsonObject>(sentBody)
        assertTrue(sent["deny"].toString().contains("\"createBucket\":true"))
        assertNull(sent["allow"])
    }

    @Test
    fun updatesKeyToNeverExpire() = testApplication {
        var sentBody = ""
        var operation = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(keyInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.patch("/api/keys/GK01") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"neverExpires":true}""")
        }

        assertEquals("UpdateKey", operation)
        val sent = json.decodeFromString<JsonObject>(sentBody)
        assertEquals("true", sent["neverExpires"]?.toString())
        assertNull(sent["expiration"])
    }

    @Test
    fun leavesPermissionsAloneWhenNotSpecified() = testApplication {
        var sentBody = ""
        var operation = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(keyInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.patch("/api/keys/GK01") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"renamed"}""")
        }

        assertEquals("UpdateKey", operation)
        val sent = json.decodeFromString<JsonObject>(sentBody)
        assertEquals("renamed", sent["name"]?.toString()?.trim('"'))
        assertNull(sent["allow"])
        assertNull(sent["deny"])
    }

    @Test
    fun deletesKey() = testApplication {
        var operation = ""
        var deletedId: String? = null
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                deletedId = request.url.parameters["id"]
                respond("", HttpStatusCode.OK)
            },
        )

        val response = client.delete("/api/keys/GK01") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("DeleteKey", operation)
        assertEquals("GK01", deletedId)
    }
}
