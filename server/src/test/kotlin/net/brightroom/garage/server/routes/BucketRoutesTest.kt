package net.brightroom.garage.server.routes

import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import net.brightroom.garage.server.createMockAdminClient
import kotlin.test.Test
import kotlin.test.assertEquals

class BucketRoutesTest {

    @Test
    fun listBuckets() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/ListBuckets", request.url.encodedPath)
            respond(
                content = """[{"id":"b1","created":"2024-01-01","globalAliases":["bucket1"],"localAliases":[]}]""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { bucketRoutes(adminClient) }
        }

        client.get("/api/buckets").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun createBucket() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/CreateBucket", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            respond(
                content = """{"id":"new-bucket"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { bucketRoutes(adminClient) }
        }

        client.post("/api/buckets") {
            contentType(ContentType.Application.Json)
            setBody("""{"globalAlias":"my-bucket"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun getBucketById() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/GetBucketInfo", request.url.encodedPath)
            assertEquals("bucket-123", request.url.parameters["id"])
            respond(
                content = """{"id":"bucket-123"}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { bucketRoutes(adminClient) }
        }

        client.get("/api/buckets/bucket-123").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun deleteBucket() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/DeleteBucket", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { bucketRoutes(adminClient) }
        }

        client.delete("/api/buckets/bucket-123").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun allowBucketKey() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/AllowBucketKey", request.url.encodedPath)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { bucketRoutes(adminClient) }
        }

        client.post("/api/buckets/bucket-123/allow-key") {
            contentType(ContentType.Application.Json)
            setBody("""{"bucketId":"bucket-123","accessKeyId":"key1","permissions":{"read":true,"write":true,"owner":false}}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun denyBucketKey() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/DenyBucketKey", request.url.encodedPath)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { bucketRoutes(adminClient) }
        }

        client.post("/api/buckets/bucket-123/deny-key") {
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun addBucketAlias() = testApplication {
        val adminClient = createMockAdminClient { request ->
            assertEquals("/v2/AddBucketAlias", request.url.encodedPath)
            respond(
                content = """{}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        install(ContentNegotiation) { json() }
        routing {
            route("/api") { bucketRoutes(adminClient) }
        }

        client.post("/api/buckets/bucket-123/alias") {
            contentType(ContentType.Application.Json)
            setBody("""{"bucketId":"bucket-123","globalAlias":"alias"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }
}
