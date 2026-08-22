package net.brightroom.garage.shared.api

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SectionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun loadedRoundTrips() {
        val section: Section<Int> = Section.Loaded(42)
        val serializer = Section.serializer(Int.serializer())

        val encoded = json.encodeToString(serializer, section)

        assertEquals("""{"type":"loaded","data":42}""", encoded)
        assertEquals(section, json.decodeFromString(serializer, encoded))
    }

    @Test
    fun deniedRoundTrips() {
        val section: Section<Int> = Section.Denied("ListBuckets")
        val serializer = Section.serializer(Int.serializer())

        val encoded = json.encodeToString(serializer, section)

        assertEquals("""{"type":"denied","operation":"ListBuckets"}""", encoded)
        assertEquals(section, json.decodeFromString(serializer, encoded))
    }

    @Test
    fun failedRoundTrips() {
        val section: Section<Int> = Section.Failed("connection refused")
        val serializer = Section.serializer(Int.serializer())

        val encoded = json.encodeToString(serializer, section)

        assertEquals("""{"type":"failed","message":"connection refused"}""", encoded)
        assertEquals(section, json.decodeFromString(serializer, encoded))
    }

    @Test
    fun dataOrNullReturnsPayloadOnlyWhenLoaded() {
        assertEquals(42, Section.Loaded(42).dataOrNull())
        assertEquals(null, Section.Denied("X").dataOrNull())
        assertEquals(null, Section.Failed("X").dataOrNull())
    }
}
