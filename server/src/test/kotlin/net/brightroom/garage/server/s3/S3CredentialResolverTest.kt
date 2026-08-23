package net.brightroom.garage.server.s3

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.GarageException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class S3CredentialResolverTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    /** owner / read+write / read / write-only の 4 つを持つバケット。 */
    private val bucketWithManyKeys = """
        {"id":"b1","globalAliases":["dev-bucket"],"websiteAccess":false,"objects":0,"bytes":0,
         "unfinishedUploads":0,"unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
         "unfinishedMultipartUploadBytes":0,"quotas":{},
         "keys":[
           {"accessKeyId":"GK-read","name":"reader","bucketLocalAliases":[],
            "permissions":{"owner":false,"read":true,"write":false}},
           {"accessKeyId":"GK-owner","name":"owner-key","bucketLocalAliases":[],
            "permissions":{"owner":true,"read":true,"write":true}},
           {"accessKeyId":"GK-rw","name":"rw-key","bucketLocalAliases":[],
            "permissions":{"owner":false,"read":true,"write":true}},
           {"accessKeyId":"GK-write","name":"writer","bucketLocalAliases":[],
            "permissions":{"owner":false,"read":false,"write":true}}]}
    """.trimIndent()

    private fun engineOf(responses: Map<String, Pair<String, HttpStatusCode>>) = MockEngine { request ->
        val operation = request.url.encodedPath.substringAfterLast('/')
        val (body, status) = responses[operation] ?: error("unexpected operation: $operation")
        respond(body, status, jsonHeaders)
    }

    private fun secretBody(accessKeyId: String, name: String) = """
        {"accessKeyId":"$accessKeyId","name":"$name","expired":false,
         "secretAccessKey":"secret-of-$accessKeyId","permissions":{},"buckets":[]}
    """.trimIndent()

    @Test
    fun prefersOwnerKey() = runTest {
        val resolver = S3CredentialResolver(
            GarageAdminClient(
                "http://garage.test:3903",
                engineOf(
                    mapOf(
                        "GetBucketInfo" to (bucketWithManyKeys to HttpStatusCode.OK),
                        "GetKeyInfo" to (secretBody("GK-owner", "owner-key") to HttpStatusCode.OK),
                    ),
                ),
            ),
            SecretCache(),
        )

        val credentials = resolver.resolve("tok", "b1")

        assertEquals("GK-owner", credentials.accessKeyId)
        assertEquals("owner-key", credentials.keyName)
        assertEquals("secret-of-GK-owner", credentials.secretAccessKey)
        assertEquals("dev-bucket", credentials.bucketName)
    }

    @Test
    fun fallsBackToReadWriteThenRead() = runTest {
        // owner を持つキーが無いバケット。GK-owner 自体を外している（"owner":true だけを
        // false に反転すると GK-owner が read+write のまま残り、GK-rw と同順位になって
        // accessKeyId 昇順のタイブレークで GK-owner が勝ってしまい、このテストが
        // 検証したい「owner が無ければ read+write を選ぶ」を確かめられなくなる）
        val withoutOwner = """
            {"id":"b1","globalAliases":["dev-bucket"],"websiteAccess":false,"objects":0,"bytes":0,
             "unfinishedUploads":0,"unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
             "unfinishedMultipartUploadBytes":0,"quotas":{},
             "keys":[
               {"accessKeyId":"GK-read","name":"reader","bucketLocalAliases":[],
                "permissions":{"owner":false,"read":true,"write":false}},
               {"accessKeyId":"GK-rw","name":"rw-key","bucketLocalAliases":[],
                "permissions":{"owner":false,"read":true,"write":true}},
               {"accessKeyId":"GK-write","name":"writer","bucketLocalAliases":[],
                "permissions":{"owner":false,"read":false,"write":true}}]}
        """.trimIndent()
        val resolver = S3CredentialResolver(
            GarageAdminClient(
                "http://garage.test:3903",
                engineOf(
                    mapOf(
                        "GetBucketInfo" to (withoutOwner to HttpStatusCode.OK),
                        "GetKeyInfo" to (secretBody("GK-rw", "rw-key") to HttpStatusCode.OK),
                    ),
                ),
            ),
            SecretCache(),
        )

        // owner が居なければ read+write。read だけの GK-read や write だけの GK-write は選ばない
        assertEquals("GK-rw", resolver.resolve("tok", "b1").accessKeyId)
    }

    @Test
    fun breaksTiesByAccessKeyIdAscending() = runTest {
        val twoOwners = """
            {"id":"b1","globalAliases":["dev-bucket"],"websiteAccess":false,"objects":0,"bytes":0,
             "unfinishedUploads":0,"unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
             "unfinishedMultipartUploadBytes":0,"quotas":{},
             "keys":[
               {"accessKeyId":"GK-zzz","name":"z","bucketLocalAliases":[],
                "permissions":{"owner":true,"read":true,"write":true}},
               {"accessKeyId":"GK-aaa","name":"a","bucketLocalAliases":[],
                "permissions":{"owner":true,"read":true,"write":true}}]}
        """.trimIndent()

        val resolver = S3CredentialResolver(
            GarageAdminClient(
                "http://garage.test:3903",
                engineOf(
                    mapOf(
                        "GetBucketInfo" to (twoOwners to HttpStatusCode.OK),
                        "GetKeyInfo" to (secretBody("GK-aaa", "a") to HttpStatusCode.OK),
                    ),
                ),
            ),
            SecretCache(),
        )

        // 同順位は決定的に選ぶ。実行のたびに使うキーが変わってはならない
        assertEquals("GK-aaa", resolver.resolve("tok", "b1").accessKeyId)
    }

    @Test
    fun usesLocalAliasWhenNoGlobalAlias() = runTest {
        val localOnly = """
            {"id":"b1","globalAliases":[],"websiteAccess":false,"objects":0,"bytes":0,
             "unfinishedUploads":0,"unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
             "unfinishedMultipartUploadBytes":0,"quotas":{},
             "keys":[{"accessKeyId":"GK01","name":"k","bucketLocalAliases":["mine"],
                      "permissions":{"owner":true,"read":true,"write":true}}]}
        """.trimIndent()

        val resolver = S3CredentialResolver(
            GarageAdminClient(
                "http://garage.test:3903",
                engineOf(
                    mapOf(
                        "GetBucketInfo" to (localOnly to HttpStatusCode.OK),
                        "GetKeyInfo" to (secretBody("GK01", "k") to HttpStatusCode.OK),
                    ),
                ),
            ),
            SecretCache(),
        )

        assertEquals("mine", resolver.resolve("tok", "b1").bucketName)
    }

    @Test
    fun failsWhenBucketHasNoAddressableName() = runTest {
        val noAlias = """
            {"id":"b1","globalAliases":[],"websiteAccess":false,"objects":0,"bytes":0,
             "unfinishedUploads":0,"unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
             "unfinishedMultipartUploadBytes":0,"quotas":{},
             "keys":[{"accessKeyId":"GK01","name":"k","bucketLocalAliases":[],
                      "permissions":{"owner":true,"read":true,"write":true}}]}
        """.trimIndent()

        val resolver = S3CredentialResolver(
            GarageAdminClient(
                "http://garage.test:3903",
                engineOf(
                    mapOf(
                        "GetBucketInfo" to (noAlias to HttpStatusCode.OK),
                        "GetKeyInfo" to (secretBody("GK01", "k") to HttpStatusCode.OK),
                    ),
                ),
            ),
            SecretCache(),
        )

        assertFailsWith<BucketNotAddressableException> { resolver.resolve("tok", "b1") }
    }

    @Test
    fun selectsAmongLocalAliasHoldersWhenNoGlobalAlias() = runTest {
        // global alias 無し。owner (GK-owner) は local alias を持たず、read+write (GK-rw) は
        // 持つ。owner を選ぶと 6.5 のバケット名解決に失敗する（BucketNotAddressableException）
        // が、read+write を選べばアドレスできる。§6.4 は「アドレスできるキーの中で」優先度を
        // 適用しなければならない
        val ownerWithoutLocalAlias = """
            {"id":"b1","globalAliases":[],"websiteAccess":false,"objects":0,"bytes":0,
             "unfinishedUploads":0,"unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
             "unfinishedMultipartUploadBytes":0,"quotas":{},
             "keys":[
               {"accessKeyId":"GK-owner","name":"owner-key","bucketLocalAliases":[],
                "permissions":{"owner":true,"read":true,"write":true}},
               {"accessKeyId":"GK-rw","name":"rw-key","bucketLocalAliases":["mine"],
                "permissions":{"owner":false,"read":true,"write":true}}]}
        """.trimIndent()

        val resolver = S3CredentialResolver(
            GarageAdminClient(
                "http://garage.test:3903",
                engineOf(
                    mapOf(
                        "GetBucketInfo" to (ownerWithoutLocalAlias to HttpStatusCode.OK),
                        "GetKeyInfo" to (secretBody("GK-rw", "rw-key") to HttpStatusCode.OK),
                    ),
                ),
            ),
            SecretCache(),
        )

        val credentials = resolver.resolve("tok", "b1")

        assertEquals("GK-rw", credentials.accessKeyId)
        assertEquals("rw-key", credentials.keyName)
        assertEquals("mine", credentials.bucketName)
    }

    @Test
    fun failsWhenNoKeyCanRead() = runTest {
        val writeOnly = """
            {"id":"b1","globalAliases":["dev-bucket"],"websiteAccess":false,"objects":0,"bytes":0,
             "unfinishedUploads":0,"unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
             "unfinishedMultipartUploadBytes":0,"quotas":{},
             "keys":[{"accessKeyId":"GK01","name":"k","bucketLocalAliases":[],
                      "permissions":{"owner":false,"read":false,"write":true}}]}
        """.trimIndent()

        val resolver = S3CredentialResolver(
            GarageAdminClient(
                "http://garage.test:3903",
                engineOf(mapOf("GetBucketInfo" to (writeOnly to HttpStatusCode.OK))),
            ),
            SecretCache(),
        )

        assertFailsWith<NoUsableKeyException> { resolver.resolve("tok", "b1") }
    }

    @Test
    fun propagatesForbiddenFromGetKeyInfo() = runTest {
        // scope が GetKeyInfo を持たないトークン。S3 ブラウザだけが縮退する（spec §6.4）
        val resolver = S3CredentialResolver(
            GarageAdminClient(
                "http://garage.test:3903",
                engineOf(
                    mapOf(
                        "GetBucketInfo" to (bucketWithManyKeys to HttpStatusCode.OK),
                        "GetKeyInfo" to ("insufficient scope" to HttpStatusCode.Forbidden),
                    ),
                ),
            ),
            SecretCache(),
        )

        val failure = assertFailsWith<GarageException> { resolver.resolve("tok", "b1") }

        assertEquals(HttpStatusCode.Forbidden, failure.status)
        assertEquals("GetKeyInfo", failure.operation)
    }

    @Test
    fun usesCacheOnSecondCall() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            val operation = request.url.encodedPath.substringAfterLast('/')
            val body = if (operation == "GetBucketInfo") {
                bucketWithManyKeys
            } else {
                secretBody("GK-owner", "owner-key")
            }
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        val resolver = S3CredentialResolver(
            GarageAdminClient("http://garage.test:3903", engine),
            SecretCache(),
        )

        resolver.resolve("tok", "b1")
        val callsAfterFirst = calls
        resolver.resolve("tok", "b1")

        assertEquals(2, callsAfterFirst) // GetBucketInfo と GetKeyInfo
        assertEquals(callsAfterFirst, calls)
    }

    /**
     * [engineOf] は operation 名だけでハンドラを分岐するため、HTTP メソッドや
     * クエリパラメータを取り違えても他のテストは緑のまま通る。特に `showSecretKey=true`
     * が抜け落ちると、実機の Garage では secret が返らず S3 ブラウザ全体が縮退するが、
     * そのバグにはここでしか気付けない（実機で確認するまで気付けなくなる）。
     */
    @Test
    fun sendsExpectedMethodAndParamsToGarage() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val operation = request.url.encodedPath.substringAfterLast('/')
            val body = if (operation == "GetBucketInfo") {
                bucketWithManyKeys
            } else {
                secretBody("GK-owner", "owner-key")
            }
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        val resolver = S3CredentialResolver(
            GarageAdminClient("http://garage.test:3903", engine),
            SecretCache(),
        )

        resolver.resolve("tok", "b1")

        val bucketRequest = requests.single { it.url.encodedPath.endsWith("/GetBucketInfo") }
        assertEquals(HttpMethod.Get, bucketRequest.method)
        assertEquals("b1", bucketRequest.url.parameters["id"])

        val keyRequest = requests.single { it.url.encodedPath.endsWith("/GetKeyInfo") }
        assertEquals(HttpMethod.Get, keyRequest.method)
        assertEquals("GK-owner", keyRequest.url.parameters["id"])
        assertEquals("true", keyRequest.url.parameters["showSecretKey"])
    }
}
