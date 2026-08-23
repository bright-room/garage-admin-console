package net.brightroom.garage.shared.model.s3

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoredObjectTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun roundTripsListing() {
        val listing = ObjectListing(
            prefix = "logs/",
            folders = listOf("logs/2026/"),
            objects = listOf(StoredObject(key = "logs/app.log", size = 1024, etag = "\"abc\"")),
            nextToken = "tok",
            keyName = "dev-key",
        )

        val decoded = json.decodeFromString<ObjectListing>(json.encodeToString(listing))

        assertEquals(listing, decoded)
    }

    @Test
    fun exposesNameRelativeToPrefix() {
        val listing = ObjectListing(
            prefix = "logs/2026/",
            folders = listOf("logs/2026/08/"),
            objects = listOf(StoredObject(key = "logs/2026/app.log", size = 1)),
        )

        assertEquals(listOf("08/"), listing.folderNames)
        assertEquals("app.log", listing.objects.single().nameIn("logs/2026/"))
    }

    @Test
    fun emptyListingIsReportedAsEmpty() {
        assertTrue(ObjectListing(prefix = "").isEmpty)
    }

    @Test
    fun parentPrefixWalksUpOneLevel() {
        assertEquals("logs/", parentPrefix("logs/2026/"))
        assertEquals("", parentPrefix("logs/"))
        assertNull(parentPrefix(""))
    }
}
