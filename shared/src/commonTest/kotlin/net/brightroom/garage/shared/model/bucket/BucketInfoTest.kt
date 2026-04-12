package net.brightroom.garage.shared.model.bucket

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BucketInfoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodeBucketInfo() {
        val raw = """
            {
                "id": "bucket-id-123",
                "created": "2024-01-01T00:00:00Z",
                "globalAliases": ["my-bucket"],
                "websiteAccess": true,
                "websiteConfig": {
                    "indexDocument": "index.html",
                    "errorDocument": "error.html"
                },
                "keys": [
                    {
                        "accessKeyId": "key1",
                        "name": "admin-key",
                        "permissions": {"read": true, "write": true, "owner": true},
                        "bucketLocalAliases": ["alias1"]
                    }
                ],
                "objects": 42,
                "bytes": 1048576,
                "unfinishedUploads": 0,
                "unfinishedMultipartUploads": 0,
                "unfinishedMultipartUploadParts": 0,
                "unfinishedMultipartUploadBytes": 0,
                "quotas": {"maxSize": 10737418240, "maxObjects": 10000}
            }
        """.trimIndent()

        val result = json.decodeFromString<BucketInfo>(raw)

        assertEquals("bucket-id-123", result.id)
        assertTrue(result.websiteAccess)
        assertEquals("index.html", result.websiteConfig?.indexDocument)
        assertEquals(1, result.keys.size)
        assertTrue(result.keys[0].permissions.owner)
        assertEquals(42L, result.objects)
        assertEquals(10737418240L, result.quotas.maxSize)
    }

    @Test
    fun decodeBucketListItem() {
        val raw = """
            {
                "id": "bucket-123",
                "created": "2024-01-01T00:00:00Z",
                "globalAliases": ["my-bucket"],
                "localAliases": [
                    {"accessKeyId": "key1", "alias": "local-name"}
                ]
            }
        """.trimIndent()

        val result = json.decodeFromString<BucketListItem>(raw)

        assertEquals("bucket-123", result.id)
        assertEquals("local-name", result.localAliases[0].alias)
    }

    @Test
    fun roundTripCreateBucketRequest() {
        val request = CreateBucketRequest(globalAlias = "new-bucket")

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<CreateBucketRequest>(encoded)

        assertEquals(request, decoded)
        assertNull(decoded.localAlias)
    }

    @Test
    fun decodeBucketQuotasWithNulls() {
        val raw = """{"maxSize": null, "maxObjects": null}"""

        val result = json.decodeFromString<BucketQuotas>(raw)

        assertNull(result.maxSize)
        assertNull(result.maxObjects)
    }
}
