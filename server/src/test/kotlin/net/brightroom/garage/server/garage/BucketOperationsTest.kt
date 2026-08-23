package net.brightroom.garage.server.garage

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.brightroom.garage.shared.api.CreateBucketRequest
import net.brightroom.garage.shared.api.UpdateBucketRequest
import net.brightroom.garage.shared.model.garage.BucketKeyPermissions
import net.brightroom.garage.shared.model.garage.BucketQuotas
import net.brightroom.garage.shared.model.garage.CorsRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BucketOperationsTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val json = Json { ignoreUnknownKeys = true }

    private val bucketInfoBody = """
        {"id":"b1","globalAliases":["dev-bucket"],"websiteAccess":false,"keys":[],
         "objects":0,"bytes":0,"unfinishedUploads":0,"unfinishedMultipartUploads":0,
         "unfinishedMultipartUploadParts":0,"unfinishedMultipartUploadBytes":0,"quotas":{}}
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
    fun listsBuckets() = runTest {
        val recorder = Recorder()
        val client = recorder.client(
            """[{"id":"b1","globalAliases":["dev-bucket"],"localAliases":[]}]""",
        )

        val buckets = client.listBuckets("tok")

        assertEquals("dev-bucket", buckets.single().displayName)
        assertTrue(recorder.requests.single().url.encodedPath.endsWith("/v2/ListBuckets"))
    }

    @Test
    fun getsBucketInfoById() = runTest {
        val recorder = Recorder()
        val client = recorder.client(bucketInfoBody)

        val info = client.getBucketInfo("tok", "b1")

        assertEquals("b1", info.id)
        assertEquals("b1", recorder.requests.single().url.parameters["id"])
    }

    @Test
    fun createsBucketWithGlobalAlias() = runTest {
        val recorder = Recorder()
        val client = recorder.client(bucketInfoBody)

        client.createBucket("tok", CreateBucketRequest(globalAlias = "new-bucket"))

        val sent = json.decodeFromString<JsonObject>(recorder.body)
        assertEquals("new-bucket", sent["globalAlias"]?.toString()?.trim('"'))
    }

    @Test
    fun createsBucketWithoutAlias() = runTest {
        val recorder = Recorder()
        val client = recorder.client(bucketInfoBody)

        client.createBucket("tok", CreateBucketRequest(globalAlias = null))

        val sent = json.decodeFromString<JsonObject>(recorder.body)
        assertNull(sent["globalAlias"])
    }

    @Test
    fun updateBucketOmitsUntouchedFields() = runTest {
        // 省略したフィールドは Garage 側で変更されない。空配列は削除を意味する。
        // この差が消えると設定フォームが他の設定を巻き込んで壊す。
        val recorder = Recorder()
        val client = recorder.client(bucketInfoBody)

        client.updateBucket(
            "tok",
            "b1",
            UpdateBucketRequest(
                quotas = BucketQuotas(maxObjects = 10, maxSize = 20),
                corsRules = emptyList(),
            ),
        )

        val sent = json.decodeFromString<JsonObject>(recorder.body)
        assertTrue(sent.containsKey("quotas"))
        assertEquals("[]", sent["corsRules"]?.toString())
        assertNull(sent["lifecycleRules"])
        assertNull(sent["websiteAccess"])
        assertEquals("b1", recorder.requests.single().url.parameters["id"])
    }

    @Test
    fun updateBucketKeepsS3RuleNames() = runTest {
        val recorder = Recorder()
        val client = recorder.client(bucketInfoBody)

        client.updateBucket(
            "tok",
            "b1",
            UpdateBucketRequest(
                corsRules = listOf(CorsRule(allowedOrigins = listOf("*"), allowedMethods = listOf("GET"))),
            ),
        )

        assertTrue(recorder.body.contains("\"AllowedOrigin\""))
    }

    @Test
    fun addsAndRemovesGlobalAlias() = runTest {
        val add = Recorder()
        add.client(bucketInfoBody).addBucketAlias("tok", "b1", "alt")

        val added = json.decodeFromString<JsonObject>(add.body)
        assertEquals("b1", added["bucketId"]?.toString()?.trim('"'))
        assertEquals("alt", added["globalAlias"]?.toString()?.trim('"'))
        assertTrue(add.requests.single().url.encodedPath.endsWith("/v2/AddBucketAlias"))

        val remove = Recorder()
        remove.client(bucketInfoBody).removeBucketAlias("tok", "b1", "alt")

        assertTrue(remove.requests.single().url.encodedPath.endsWith("/v2/RemoveBucketAlias"))
    }

    @Test
    fun allowsAndDeniesBucketKey() = runTest {
        val allow = Recorder()
        allow.client(bucketInfoBody).allowBucketKey(
            "tok",
            "b1",
            "GK01",
            BucketKeyPermissions(read = true, write = true),
        )

        val sent = json.decodeFromString<JsonObject>(allow.body)
        assertEquals("GK01", sent["accessKeyId"]?.toString()?.trim('"'))
        assertTrue(sent["permissions"].toString().contains("\"read\":true"))
        assertTrue(allow.requests.single().url.encodedPath.endsWith("/v2/AllowBucketKey"))

        val deny = Recorder()
        deny.client(bucketInfoBody).denyBucketKey(
            "tok",
            "b1",
            "GK01",
            BucketKeyPermissions(owner = true, read = true, write = true),
        )

        assertTrue(deny.requests.single().url.encodedPath.endsWith("/v2/DenyBucketKey"))
    }

    @Test
    fun cleansUpIncompleteUploads() = runTest {
        val recorder = Recorder()
        val client = recorder.client("""{"uploadsDeleted":3}""")

        val deleted = client.cleanupIncompleteUploads("tok", "b1", olderThanSecs = 86_400)

        assertEquals(3, deleted)
        val sent = json.decodeFromString<JsonObject>(recorder.body)
        assertEquals("86400", sent["olderThanSecs"]?.toString())
    }

    @Test
    fun inspectsObject() = runTest {
        val recorder = Recorder()
        val client = recorder.client(
            """
            {"bucketId":"b1","key":"a.txt","versions":[
              {"uuid":"v1","timestamp":"2026-08-22T16:43:38.636Z","encrypted":false,
               "uploading":false,"aborted":false,"deleteMarker":false,"inline":true,
               "size":12,"etag":"abc","headers":[["content-type","text/plain"]],"blocks":[]}]}
            """.trimIndent(),
        )

        val inspection = client.inspectObject("tok", "b1", "a.txt")

        assertEquals("a.txt", inspection.key)
        assertEquals(listOf("content-type", "text/plain"), inspection.versions.single().headers.single())
        assertEquals("a.txt", recorder.requests.single().url.parameters["key"])
    }

    @Test
    fun propagatesGarageFailure() = runTest {
        val recorder = Recorder()
        val client = recorder.client("insufficient scope", HttpStatusCode.Forbidden)

        val failure = assertFailsWith<GarageException> { client.listBuckets("tok") }

        assertEquals(HttpStatusCode.Forbidden, failure.status)
        assertEquals("ListBuckets", failure.operation)
    }

    @Test
    fun deleteBucketReportsGarageRefusal() = runTest {
        // 空でないバケットは Garage が拒否する。理由をそのまま運ぶ（spec §8.6）
        val recorder = Recorder()
        val client = recorder.client("bucket is not empty", HttpStatusCode.BadRequest)

        val failure = assertFailsWith<GarageException> { client.deleteBucket("tok", "b1") }

        assertEquals(HttpStatusCode.BadRequest, failure.status)
        assertEquals("bucket is not empty", failure.message)
    }
}
