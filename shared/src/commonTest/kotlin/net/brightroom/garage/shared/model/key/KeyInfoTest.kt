package net.brightroom.garage.shared.model.key

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyInfoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodeKeyInfo() {
        val raw = """
            {
                "accessKeyId": "GK123",
                "name": "my-key",
                "expired": false,
                "created": "2024-01-01T00:00:00Z",
                "permissions": {"createBucket": true},
                "secretAccessKey": "secret123",
                "buckets": [
                    {
                        "id": "bucket-1",
                        "globalAliases": ["my-bucket"],
                        "localAliases": ["local-name"],
                        "permissions": {"read": true, "write": false, "owner": false}
                    }
                ]
            }
        """.trimIndent()

        val result = json.decodeFromString<KeyInfo>(raw)

        assertEquals("GK123", result.accessKeyId)
        assertEquals("my-key", result.name)
        assertTrue(result.permissions.createBucket)
        assertEquals("secret123", result.secretAccessKey)
        assertEquals(1, result.buckets.size)
        assertTrue(result.buckets[0].permissions.read)
    }

    @Test
    fun decodeKeyListItem() {
        val raw = """
            {
                "id": "GK123",
                "name": "test-key",
                "expired": false,
                "created": "2024-01-01T00:00:00Z"
            }
        """.trimIndent()

        val result = json.decodeFromString<KeyListItem>(raw)

        assertEquals("GK123", result.id)
        assertNull(result.expiration)
    }

    @Test
    fun roundTripImportKeyRequest() {
        val request = ImportKeyRequest(
            accessKeyId = "GK123",
            secretAccessKey = "secret",
            name = "imported",
        )

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<ImportKeyRequest>(encoded)

        assertEquals(request, decoded)
    }
}
