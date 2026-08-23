package net.brightroom.garage.server.garage

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.brightroom.garage.shared.api.CreateKeyRequest
import net.brightroom.garage.shared.api.ImportKeyRequest
import net.brightroom.garage.shared.api.UpdateKeyRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [KeyRoutesTest] の MockEngine は `url.encodedPath` しか見ないため、HTTP メソッドを
 * 取り違えても気付けない。この operation 層で GET / POST を固定する。
 */
class KeyOperationsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val keyInfoBody = """
        {"accessKeyId":"GK01","name":"dev-key","expired":false,
         "permissions":{"createBucket":false},"buckets":[]}
    """.trimIndent()

    /** 送られたリクエストを記録しつつ、固定の応答を返すクライアント。 */
    private class Recorder {
        val requests = mutableListOf<HttpRequestData>()
        var body: String = ""

        fun client(response: String, status: HttpStatusCode = HttpStatusCode.OK): GarageAdminClient {
            val engine = MockEngine { request ->
                requests += request
                body = (request.body as? TextContent)?.text.orEmpty()
                respond(
                    response,
                    status,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            return GarageAdminClient("http://garage.test:3903", engine)
        }
    }

    @Test
    fun listsKeys() = runTest {
        val recorder = Recorder()
        val client = recorder.client("""[{"id":"GK01","name":"dev-key","expired":false}]""")

        val keys = client.listKeys("tok")

        assertEquals("dev-key", keys.single().name)
        val request = recorder.requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertTrue(request.url.encodedPath.endsWith("/v2/ListKeys"))
    }

    @Test
    fun getsKeyInfoWithoutSecretByDefault() = runTest {
        val recorder = Recorder()
        val client = recorder.client(keyInfoBody)

        val info = client.getKeyInfo("tok", "GK01")

        assertEquals("GK01", info.accessKeyId)
        val request = recorder.requests.single()
        assertEquals("GK01", request.url.parameters["id"])
        assertNull(request.url.parameters["showSecretKey"])
        assertEquals(HttpMethod.Get, request.method)
        assertTrue(request.url.encodedPath.endsWith("/v2/GetKeyInfo"))
    }

    @Test
    fun getsKeyInfoWithSecretWhenAsked() = runTest {
        val recorder = Recorder()
        val client = recorder.client(keyInfoBody)

        client.getKeyInfo("tok", "GK01", showSecret = true)

        val request = recorder.requests.single()
        assertEquals("true", request.url.parameters["showSecretKey"])
        assertEquals(HttpMethod.Get, request.method)
        assertTrue(request.url.encodedPath.endsWith("/v2/GetKeyInfo"))
    }

    @Test
    fun createsKeyWithPermissionAndExpiration() = runTest {
        val recorder = Recorder()
        val client = recorder.client(keyInfoBody)

        client.createKey("tok", CreateKeyRequest(name = "ci", allowCreateBucket = true))

        val sent = json.decodeFromString<JsonObject>(recorder.body)
        assertEquals("ci", sent["name"]?.toString()?.trim('"'))
        assertTrue(sent["allow"].toString().contains("\"createBucket\":true"))
        assertNull(sent["deny"])
        // 期限を指定しなければ無期限
        assertEquals("true", sent["neverExpires"]?.toString())
        val request = recorder.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertTrue(request.url.encodedPath.endsWith("/v2/CreateKey"))
    }

    @Test
    fun importsKeyWithSecret() = runTest {
        val recorder = Recorder()
        val client = recorder.client(keyInfoBody)

        client.importKey(
            "tok",
            ImportKeyRequest(name = "restored", accessKeyId = "GK99", secretAccessKey = "old-secret"),
        )

        val sent = json.decodeFromString<JsonObject>(recorder.body)
        assertEquals("GK99", sent["accessKeyId"]?.toString()?.trim('"'))
        assertEquals("old-secret", sent["secretAccessKey"]?.toString()?.trim('"'))
        val request = recorder.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertTrue(request.url.encodedPath.endsWith("/v2/ImportKey"))
    }

    @Test
    fun updatesKeyById() = runTest {
        val recorder = Recorder()
        val client = recorder.client(keyInfoBody)

        client.updateKey("tok", "GK01", UpdateKeyRequest(allowCreateBucket = false))

        val sent = json.decodeFromString<JsonObject>(recorder.body)
        assertTrue(sent["deny"].toString().contains("\"createBucket\":true"))
        assertNull(sent["allow"])
        val request = recorder.requests.single()
        assertEquals("GK01", request.url.parameters["id"])
        assertEquals(HttpMethod.Post, request.method)
        assertTrue(request.url.encodedPath.endsWith("/v2/UpdateKey"))
    }

    @Test
    fun deletesKeyById() = runTest {
        val recorder = Recorder()
        val client = recorder.client("")

        client.deleteKey("tok", "GK01")

        val request = recorder.requests.single()
        assertEquals("GK01", request.url.parameters["id"])
        assertEquals(HttpMethod.Post, request.method)
        assertTrue(request.url.encodedPath.endsWith("/v2/DeleteKey"))
    }
}
