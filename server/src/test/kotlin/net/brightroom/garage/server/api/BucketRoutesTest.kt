package net.brightroom.garage.server.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import net.brightroom.garage.shared.api.ProblemDetails
import net.brightroom.garage.shared.model.garage.BucketInfo
import net.brightroom.garage.shared.model.garage.BucketSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BucketRoutesTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val json = Json { ignoreUnknownKeys = true }

    private val bucketInfoBody = """
        {"id":"b1","globalAliases":["dev-bucket"],"websiteAccess":false,"keys":[],
         "objects":0,"bytes":0,"unfinishedUploads":0,"unfinishedMultipartUploads":0,
         "unfinishedMultipartUploadParts":0,"unfinishedMultipartUploadBytes":0,"quotas":{}}
    """.trimIndent()

    @Test
    fun listsBuckets() = testApplication {
        var operation = ""
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(
                    """[{"id":"b1","globalAliases":["dev-bucket"],"localAliases":[]}]""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val response = client.get("/api/buckets") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ListBuckets", operation)
        val buckets = GarageJson.decodeFromString<List<BucketSummary>>(response.bodyAsText())
        assertEquals("dev-bucket", buckets.single().displayName)
    }

    @Test
    fun getsBucketInfo() = testApplication {
        var operation = ""
        var requestedId: String? = null
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                requestedId = request.url.parameters["id"]
                respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.get("/api/buckets/b1") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("GetBucketInfo", operation)
        assertEquals("b1", requestedId)
        val info = GarageJson.decodeFromString<BucketInfo>(response.bodyAsText())
        assertEquals("b1", info.id)
    }

    @Test
    fun requiresTokenForEveryRoute() = testApplication {
        garageApp(MockEngine { respond("", HttpStatusCode.OK) })

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/buckets").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/buckets/b1").status)
    }

    @Test
    fun createsBucket() = testApplication {
        var sentBody = ""
        var operation = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/buckets") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"globalAlias":"new-bucket"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("CreateBucket", operation)
        assertTrue(sentBody.contains("new-bucket"))
        val info = GarageJson.decodeFromString<BucketInfo>(response.bodyAsText())
        assertEquals("b1", info.id)
    }

    @Test
    fun rejectsMalformedRequestBody() = testApplication {
        garageApp(MockEngine { respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/buckets") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("not json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]!!.contains("application/problem+json"))
        val problem = GarageJson.decodeFromString<ProblemDetails>(response.bodyAsText())
        assertEquals("リクエストの内容を解釈できませんでした", problem.detail)
    }

    @Test
    fun rejectsMissingTokenBeforeParsingBody() = testApplication {
        garageApp(MockEngine { respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders) })

        // Authorization も本文も両方不正。トークンが先に評価されるべきなので 401
        val response = client.post("/api/buckets") {
            contentType(ContentType.Application.Json)
            setBody("not json")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun rejectsMissingContentType() = testApplication {
        garageApp(MockEngine { respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders) })

        val response = client.post("/api/buckets") {
            header(HttpHeaders.Authorization, "Bearer tok")
            setBody("""{"globalAlias":"new-bucket"}""")
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]!!.contains("application/problem+json"))
    }

    @Test
    fun updatesBucketSettings() = testApplication {
        var sentBody = ""
        var operation = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.patch("/api/buckets/b1") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"corsRules":[]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("UpdateBucket", operation)
        // 触っていない設定を巻き込まないこと
        val sent = json.decodeFromString<JsonObject>(sentBody)
        assertEquals("[]", sent["corsRules"]?.toString())
        assertEquals(null, sent["quotas"])
        assertEquals(null, sent["websiteAccess"])
    }

    @Test
    fun deletesBucket() = testApplication {
        var operation = ""
        var requestedId: String? = null
        garageApp(
            MockEngine { request ->
                operation = request.url.encodedPath.substringAfterLast('/')
                requestedId = request.url.parameters["id"]
                respond("", HttpStatusCode.OK)
            },
        )

        val response = client.delete("/api/buckets/b1") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("DeleteBucket", operation)
        assertEquals("b1", requestedId)
    }

    @Test
    fun reportsGarageRefusalToDeleteNonEmptyBucket() = testApplication {
        garageApp(MockEngine { respond("bucket is not empty", HttpStatusCode.BadRequest) })

        val response = client.delete("/api/buckets/b1") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val problem = GarageJson.decodeFromString<ProblemDetails>(response.bodyAsText())
        assertEquals("bucket is not empty", problem.detail)
        assertEquals("DeleteBucket", problem.operation)
    }

    @Test
    fun addsAliasFromBodyAndRemovesFromQuery() = testApplication {
        val operations = mutableListOf<String>()
        garageApp(
            MockEngine { request ->
                operations += request.url.encodedPath.substringAfterLast('/')
                respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.post("/api/buckets/b1/aliases") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"alias":"alt"}""")
        }
        client.delete("/api/buckets/b1/aliases?alias=alt") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(listOf("AddBucketAlias", "RemoveBucketAlias"), operations)
    }

    @Test
    fun rejectsAliasRemovalWithoutAliasParameter() = testApplication {
        garageApp(MockEngine { respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders) })

        val response = client.delete("/api/buckets/b1/aliases") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val problem = GarageJson.decodeFromString<ProblemDetails>(response.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest.value, problem.status)
    }

    @Test
    fun grantsPermissionsFromBody() = testApplication {
        var sentBody = ""
        var operation = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.put("/api/buckets/b1/keys/GK01") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("""{"permissions":{"read":true,"write":true,"owner":false}}""")
        }

        assertEquals("AllowBucketKey", operation)
        val sent = json.decodeFromString<JsonObject>(sentBody)
        assertEquals("GK01", sent["accessKeyId"]?.toString()?.trim('"'))
        val permissions = sent["permissions"].toString()
        assertTrue(permissions.contains("\"write\":true"))
        // owner:false は Kotlin 側の既定値と同じなので GarageJson の encodeDefaults により
        // JSON に出ない。「owner を false にする」＝「owner キーを送らない」であり、
        // どちらでも Garage は owner を書き換えない（true のフラグしか見ないため、P2-11 の
        // 隣の DELETE と違って、これは「置き換え」ではない）。write:true だけを見ると
        // 「permissions を丸ごと置き換えている」と誤読しやすいので、owner が true としては
        // 送られていないことも確かめる
        assertFalse(permissions.contains("\"owner\":true"))
    }

    @Test
    fun revokesEveryPermission() = testApplication {
        var sentBody = ""
        var operation = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                operation = request.url.encodedPath.substringAfterLast('/')
                respond(bucketInfoBody, HttpStatusCode.OK, jsonHeaders)
            },
        )

        client.delete("/api/buckets/b1/keys/GK01") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals("DenyBucketKey", operation)
        // 部分的な剥奪はしない（P2-11）
        val sent = json.decodeFromString<JsonObject>(sentBody)
        val permissions = sent["permissions"].toString()
        assertTrue(permissions.contains("\"owner\":true"))
        assertTrue(permissions.contains("\"read\":true"))
        assertTrue(permissions.contains("\"write\":true"))
    }

    @Test
    fun cleansUpUploadsWithDefaultAge() = testApplication {
        var sentBody = ""
        var operation = ""
        garageApp(
            MockEngine { request ->
                sentBody = (request.body as? TextContent)?.text.orEmpty()
                operation = request.url.encodedPath.substringAfterLast('/')
                respond("""{"uploadsDeleted":2}""", HttpStatusCode.OK, jsonHeaders)
            },
        )

        val response = client.post("/api/buckets/b1/cleanup-uploads") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("CleanupIncompleteUploads", operation)
        assertTrue(sentBody.contains("86400"))
        assertTrue(response.bodyAsText().contains("2"))
    }

    @Test
    fun forwardsForbiddenAsProblemDetails() = testApplication {
        garageApp(MockEngine { respond("insufficient scope", HttpStatusCode.Forbidden) })

        val response = client.get("/api/buckets") {
            header(HttpHeaders.Authorization, "Bearer tok")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]!!.contains("application/problem+json"))
        val problem = GarageJson.decodeFromString<ProblemDetails>(response.bodyAsText())
        assertEquals("ListBuckets", problem.operation)
    }
}
