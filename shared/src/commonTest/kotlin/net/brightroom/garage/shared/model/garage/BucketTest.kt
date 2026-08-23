package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BucketTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun decodesBucketList() {
        val buckets = json.decodeFromString(
            ListSerializer(BucketSummary.serializer()),
            """
            [{"id":"b1","created":"2026-08-22T16:43:38.636Z","globalAliases":["dev-bucket"],
              "localAliases":[{"accessKeyId":"GK01","alias":"mine"}]}]
            """.trimIndent(),
        )

        assertEquals(1, buckets.size)
        assertEquals("dev-bucket", buckets.first().globalAliases.first())
        assertEquals("GK01", buckets.first().localAliases.first().accessKeyId)
        assertEquals("dev-bucket", buckets.first().displayName)
    }

    @Test
    fun bucketSummaryDisplayNameFallsBackToLocalAlias() {
        val buckets = json.decodeFromString(
            ListSerializer(BucketSummary.serializer()),
            """[{"id":"4a8ee3738eaa9c1d2e3f4a5b","localAliases":[{"accessKeyId":"GK01","alias":"mine"}]}]""",
        )

        assertEquals("mine", buckets.first().displayName)
    }

    @Test
    fun bucketSummaryDisplayNameFallsBackToIdWhenNoAlias() {
        val buckets = json.decodeFromString(
            ListSerializer(BucketSummary.serializer()),
            """[{"id":"4a8ee3738eaa9c1d2e3f4a5b"}]""",
        )

        assertEquals("4a8ee3738eaa", buckets.first().displayName)
    }

    @Test
    fun decodesBucketInfo() {
        val info = json.decodeFromString<BucketInfo>(
            """
            {"id":"b1","created":"2026-08-22T16:43:38.636Z","globalAliases":["dev-bucket"],
             "websiteAccess":false,"keys":[
               {"accessKeyId":"GK01","name":"dev-key","bucketLocalAliases":[],
                "permissions":{"owner":true,"read":true,"write":true}}],
             "objects":3,"bytes":1024,"unfinishedUploads":1,"unfinishedMultipartUploads":1,
             "unfinishedMultipartUploadParts":2,"unfinishedMultipartUploadBytes":512,
             "quotas":{"maxObjects":null,"maxSize":null}}
            """.trimIndent(),
        )

        assertEquals("b1", info.id)
        assertEquals(3, info.objects)
        assertTrue(info.keys.first().permissions.owner)
        assertNull(info.quotas.maxObjects)
        assertNull(info.corsRules)
        assertNull(info.websiteConfig)
    }

    @Test
    fun bucketInfoDisplayNameFallsBackToLocalAlias() {
        val info = json.decodeFromString<BucketInfo>(
            """
            {"id":"4a8ee3738eaa9c1d2e3f4a5b","globalAliases":[],"websiteAccess":false,
             "keys":[{"accessKeyId":"GK01","name":"dev-key","bucketLocalAliases":["mine"],
                      "permissions":{"owner":true,"read":true,"write":true}}],
             "objects":0,"bytes":0,"unfinishedUploads":0,"unfinishedMultipartUploads":0,
             "unfinishedMultipartUploadParts":0,"unfinishedMultipartUploadBytes":0,
             "quotas":{}}
            """.trimIndent(),
        )

        assertEquals("mine", info.displayName)
    }

    @Test
    fun bucketInfoDisplayNameFallsBackToIdWhenNoAlias() {
        val info = json.decodeFromString<BucketInfo>(
            """
            {"id":"4a8ee3738eaa9c1d2e3f4a5b","globalAliases":[],"websiteAccess":false,
             "keys":[],"objects":0,"bytes":0,"unfinishedUploads":0,"unfinishedMultipartUploads":0,
             "unfinishedMultipartUploadParts":0,"unfinishedMultipartUploadBytes":0,
             "quotas":{}}
            """.trimIndent(),
        )

        assertEquals("4a8ee3738eaa", info.displayName)
    }

    @Test
    fun decodesCorsAndLifecycleRules() {
        // 実機（Garage v2.3.0）から採取した表現。OpenAPI では ID / Status / Prefix の
        // 型が潰れているため、こちらを正とする。
        val info = json.decodeFromString<BucketInfo>(
            """
            {"id":"b1","globalAliases":[],"websiteAccess":true,
             "websiteConfig":{"indexDocument":"index.html","errorDocument":"error.html"},
             "keys":[],"objects":0,"bytes":0,"unfinishedUploads":0,
             "unfinishedMultipartUploads":0,"unfinishedMultipartUploadParts":0,
             "unfinishedMultipartUploadBytes":0,"quotas":{},
             "corsRules":[{"ID":"allow-web","MaxAgeSeconds":3600,
               "AllowedOrigin":["https://example.com"],"AllowedMethod":["GET","PUT"],
               "AllowedHeader":["*"],"ExposeHeader":["ETag"]}],
             "lifecycleRules":[
               {"ID":"expire-tmp","Status":"Enabled","Filter":{"Prefix":"tmp/"},
                "Expiration":{"Days":30},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":7}},
               {"ID":"by-date","Status":"Enabled",
                "Filter":{"And":{"Prefix":"logs/","ObjectSizeGreaterThan":1024}},
                "Expiration":{"Date":"2027-01-01T00:00:00Z"}}]}
            """.trimIndent(),
        )

        val cors = info.corsRules?.single()
        assertEquals("allow-web", cors?.id)
        assertEquals(3600, cors?.maxAgeSeconds)
        assertEquals(listOf("GET", "PUT"), cors?.allowedMethods)
        assertEquals("index.html", info.websiteConfig?.indexDocument)

        val rules = info.lifecycleRules.orEmpty()
        assertEquals(LifecycleStatus.ENABLED, rules[0].status)
        assertEquals("tmp/", rules[0].filter?.prefix)
        assertEquals(30, rules[0].expiration?.days)
        assertEquals(7, rules[0].abortIncompleteMultipartUpload?.daysAfterInitiation)
        assertEquals(1024, rules[1].filter?.and?.objectSizeGreaterThan)
        assertEquals("2027-01-01T00:00:00Z", rules[1].expiration?.date)
    }

    @Test
    fun keepsPascalCaseWhenEncoding() {
        // Garage に送り返すときも S3 由来の名前でなければ受け付けられない
        val encoded = json.encodeToString(
            CorsRule(allowedOrigins = listOf("*"), allowedMethods = listOf("GET")),
        )

        assertTrue(encoded.contains("\"AllowedOrigin\""))
        assertTrue(encoded.contains("\"AllowedMethod\""))
        // explicitNulls = false のため、指定しなかった ID は出ない
        assertFalse(encoded.contains("\"ID\""))
    }

    @Test
    fun permissionRankOrdersOwnerFirst() {
        assertEquals(3, BucketKeyPermissions(owner = true, read = true, write = true).rank)
        assertEquals(2, BucketKeyPermissions(read = true, write = true).rank)
        assertEquals(1, BucketKeyPermissions(read = true).rank)
        assertEquals(0, BucketKeyPermissions(write = true).rank)
        assertEquals(0, BucketKeyPermissions().rank)
    }
}
