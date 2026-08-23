package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import net.brightroom.garage.server.garageApp
import net.brightroom.garage.server.plugins.GarageJson
import net.brightroom.garage.shared.api.ProblemDetails
import net.brightroom.garage.shared.api.ProblemTypes
import net.brightroom.garage.shared.model.garage.ObjectInspection
import kotlin.test.Test
import kotlin.test.assertEquals

class ObjectRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun bucketBody(keys: String, aliases: String = """["dev-bucket"]""") = """
        {"id":"b1","globalAliases":$aliases,"websiteAccess":false,"objects":0,"bytes":0,
         "unfinishedUploads":0,"unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
         "unfinishedMultipartUploadBytes":0,"quotas":{},"keys":$keys}
    """.trimIndent()

    private val ownerKey = """
        [{"accessKeyId":"GK01","name":"dev-key","bucketLocalAliases":[],
          "permissions":{"owner":true,"read":true,"write":true}}]
    """.trimIndent()

    private fun engineOf(responses: Map<String, Pair<String, HttpStatusCode>>) = MockEngine { request ->
        val operation = request.url.encodedPath.substringAfterLast('/')
        val (body, status) = responses[operation] ?: error("unexpected operation: $operation")
        respond(body, status, jsonHeaders)
    }

    @Test
    fun requiresKeyParameterForDownload() = testApplication {
        garageApp(engineOf(mapOf("GetBucketInfo" to (bucketBody(ownerKey) to HttpStatusCode.OK))))

        val response = client.get("/api/buckets/b1/objects/content") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun rejectsUploadWithoutContentLength() = testApplication {
        garageApp(engineOf(mapOf("GetBucketInfo" to (bucketBody(ownerKey) to HttpStatusCode.OK))))

        // Transfer-Encoding は Ktor client が拒む（unsafe header）ため手では付けられない。
        // 長さを持たない本文を送ると Content-Length が付かず、同じ経路を通る
        val response = client.put("/api/buckets/b1/objects?key=a.txt") {
            header(HttpHeaders.Authorization, "Bearer tok")
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        channel.writeStringUtf8("hello")
                    }
                },
            )
        }

        assertEquals(HttpStatusCode.LengthRequired, response.status)
    }

    @Test
    fun reportsForbiddenFromKeyLookup() = testApplication {
        // scope に GetKeyInfo が無いトークン。S3 ブラウザだけが縮退する（spec §6.4）
        garageApp(
            engineOf(
                mapOf(
                    "GetBucketInfo" to (bucketBody(ownerKey) to HttpStatusCode.OK),
                    "GetKeyInfo" to ("insufficient scope" to HttpStatusCode.Forbidden),
                ),
            ),
        )

        val response = client.get("/api/buckets/b1/objects") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val problem = GarageJson.decodeFromString<ProblemDetails>(response.bodyAsText())
        assertEquals("GetKeyInfo", problem.operation)
    }

    @Test
    fun reportsMissingUsableKeyWithItsOwnType() = testApplication {
        val writeOnly = """
            [{"accessKeyId":"GK01","name":"writer","bucketLocalAliases":[],
              "permissions":{"owner":false,"read":false,"write":true}}]
        """.trimIndent()
        garageApp(engineOf(mapOf("GetBucketInfo" to (bucketBody(writeOnly) to HttpStatusCode.OK))))

        val response = client.get("/api/buckets/b1/objects") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        val problem = GarageJson.decodeFromString<ProblemDetails>(response.bodyAsText())
        assertEquals(ProblemTypes.NO_USABLE_KEY, problem.type)
    }

    @Test
    fun reportsUnaddressableBucketWithItsOwnType() = testApplication {
        garageApp(
            engineOf(
                mapOf("GetBucketInfo" to (bucketBody(ownerKey, aliases = "[]") to HttpStatusCode.OK)),
            ),
        )

        val response = client.get("/api/buckets/b1/objects") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        val problem = GarageJson.decodeFromString<ProblemDetails>(response.bodyAsText())
        assertEquals(ProblemTypes.BUCKET_NOT_ADDRESSABLE, problem.type)
    }

    @Test
    fun inspectsObjectWithoutTouchingS3() = testApplication {
        var operation = ""
        var requestedBucketId: String? = null
        var requestedKey: String? = null
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                requestedBucketId = request.url.parameters["bucketId"]
                requestedKey = request.url.parameters["key"]
                respond(
                    """
                    {"bucketId":"b1","key":"a.txt","versions":[
                      {"uuid":"v1","timestamp":"2026-08-22T16:43:38.636Z","encrypted":false,
                       "uploading":false,"aborted":false,"deleteMarker":false,"inline":true,
                       "size":5,"blocks":[]}]}
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/buckets/b1/objects/inspect?key=a.txt") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("InspectObject", operation)
        assertEquals("b1", requestedBucketId)
        assertEquals("a.txt", requestedKey)
        val inspection = GarageJson.decodeFromString<ObjectInspection>(response.bodyAsText())
        assertEquals("a.txt", inspection.key)
    }

    @Test
    fun requiresToken() = testApplication {
        garageApp(MockEngine { respond("", HttpStatusCode.OK) })

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/buckets/b1/objects").status)
    }
}
