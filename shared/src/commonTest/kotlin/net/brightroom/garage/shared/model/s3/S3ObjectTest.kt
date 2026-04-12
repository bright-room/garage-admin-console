package net.brightroom.garage.shared.model.s3

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S3ObjectTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodeS3ObjectList() {
        val raw = """
            {
                "prefix": "photos/",
                "objects": [
                    {"key": "photos/a.jpg", "size": 1024, "lastModified": "2024-01-01T00:00:00Z", "etag": "\"abc\""},
                    {"key": "photos/b.png", "size": 2048, "lastModified": "2024-02-01T00:00:00Z"}
                ],
                "commonPrefixes": ["photos/sub/"],
                "isTruncated": true,
                "nextContinuationToken": "token123"
            }
        """.trimIndent()

        val result = json.decodeFromString<S3ObjectList>(raw)

        assertEquals("photos/", result.prefix)
        assertEquals(2, result.objects.size)
        assertEquals("photos/a.jpg", result.objects[0].key)
        assertEquals(1024L, result.objects[0].size)
        assertEquals("\"abc\"", result.objects[0].etag)
        assertNull(result.objects[1].etag)
        assertEquals(listOf("photos/sub/"), result.commonPrefixes)
        assertTrue(result.isTruncated)
        assertEquals("token123", result.nextContinuationToken)
    }

    @Test
    fun roundTripS3ObjectList() {
        val original = S3ObjectList(
            prefix = "",
            objects = listOf(
                S3ObjectItem(key = "file.txt", size = 100, lastModified = "2024-01-01"),
            ),
            commonPrefixes = emptyList(),
            isTruncated = false,
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<S3ObjectList>(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun decodeS3ObjectListWithNullToken() {
        val raw = """
            {
                "prefix": "",
                "objects": [],
                "commonPrefixes": [],
                "isTruncated": false
            }
        """.trimIndent()

        val result = json.decodeFromString<S3ObjectList>(raw)

        assertNull(result.nextContinuationToken)
        assertEquals(0, result.objects.size)
    }
}
