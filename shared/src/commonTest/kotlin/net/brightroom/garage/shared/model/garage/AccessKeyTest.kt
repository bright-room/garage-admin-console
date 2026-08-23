package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessKeyTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun decodesKeyList() {
        val keys = json.decodeFromString(
            ListSerializer(KeySummary.serializer()),
            """
            [{"id":"GK01","name":"dev-key","expired":false,
              "created":"2026-08-22T16:43:38.636Z","expiration":null}]
            """.trimIndent(),
        )

        assertEquals("GK01", keys.single().id)
        assertEquals("dev-key", keys.single().name)
        assertNull(keys.single().expiration)
    }

    @Test
    fun decodesKeyInfoWithoutSecret() {
        val key = json.decodeFromString<KeyInfo>(
            """
            {"accessKeyId":"GK01","name":"dev-key","expired":false,
             "permissions":{"createBucket":true},
             "buckets":[{"id":"b1","globalAliases":["dev-bucket"],"localAliases":[],
                         "permissions":{"owner":true,"read":true,"write":true}}]}
            """.trimIndent(),
        )

        assertEquals("GK01", key.accessKeyId)
        assertTrue(key.permissions.createBucket)
        assertEquals("dev-bucket", key.buckets.single().displayName)
        assertTrue(key.buckets.single().permissions.owner)
        assertNull(key.secretAccessKey)
    }

    @Test
    fun decodesKeyInfoWithSecret() {
        val key = json.decodeFromString<KeyInfo>(
            """
            {"accessKeyId":"GK01","name":"dev-key","expired":false,
             "secretAccessKey":"s3cr3t","permissions":{},"buckets":[]}
            """.trimIndent(),
        )

        assertEquals("s3cr3t", key.secretAccessKey)
        assertEquals(false, key.permissions.createBucket)
    }
}
