package net.brightroom.garage.shared.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodeMultiResponse() {
        val raw = """
            {
                "success": {"node1": "ok"},
                "error": {"node2": "connection refused"}
            }
        """.trimIndent()

        val result = json.decodeFromString<MultiResponse>(raw)

        assertEquals(1, result.success.size)
        assertEquals(JsonPrimitive("ok"), result.success["node1"])
        assertEquals("connection refused", result.error["node2"])
    }

    @Test
    fun decodeEmptyMultiResponse() {
        val raw = """{}"""

        val result = json.decodeFromString<MultiResponse>(raw)

        assertTrue(result.success.isEmpty())
        assertTrue(result.error.isEmpty())
    }
}
