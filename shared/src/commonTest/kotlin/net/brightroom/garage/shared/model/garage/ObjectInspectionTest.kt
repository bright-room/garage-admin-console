package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObjectInspectionTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun decodesObjectInspection() {
        // 実機（Garage v2.3.0）から採取した表現。
        val inspection = json.decodeFromString<ObjectInspection>(
            """
            {"bucketId":"b1","key":"logs/app.log","versions":[
              {"uuid":"v1","timestamp":"2026-08-22T16:43:38.636Z","encrypted":false,
               "uploading":false,"aborted":false,"deleteMarker":false,"inline":false,
               "size":1024,"etag":"\"abc\"","headers":[["content-type","text/plain"]],
               "blocks":[{"partNumber":1,"offset":0,"hash":"ff00","size":1024}]}]}
            """.trimIndent(),
        )

        assertEquals("b1", inspection.bucketId)
        assertEquals("logs/app.log", inspection.key)
        assertEquals(1, inspection.versions.size)

        val version = inspection.versions.single()
        assertEquals("v1", version.uuid)
        assertFalse(version.encrypted)
        assertFalse(version.uploading)
        assertFalse(version.aborted)
        assertFalse(version.deleteMarker)
        assertFalse(version.inline)
        assertEquals(1024, version.size)
        assertEquals("\"abc\"", version.etag)
        assertEquals(listOf(listOf("content-type", "text/plain")), version.headers)

        val block = version.blocks.single()
        assertEquals(1, block.partNumber)
        assertEquals(0, block.offset)
        assertEquals("ff00", block.hash)
        assertEquals(1024, block.size)
    }

    @Test
    fun decodesInlineObjectWithoutBlocksOrHeaders() {
        val inspection = json.decodeFromString<ObjectInspection>(
            """
            {"bucketId":"b1","key":"small.txt","versions":[
              {"uuid":"v2","timestamp":"2026-08-22T16:43:38.636Z","encrypted":false,
               "uploading":false,"aborted":false,"deleteMarker":false,"inline":true,
               "size":12,"etag":"\"def\""}]}
            """.trimIndent(),
        )

        val version = inspection.versions.single()
        assertTrue(version.inline)
        assertEquals(emptyList(), version.headers)
        assertEquals(emptyList(), version.blocks)
    }
}
