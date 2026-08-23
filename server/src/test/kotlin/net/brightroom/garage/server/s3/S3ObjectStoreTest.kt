package net.brightroom.garage.server.s3

import aws.sdk.kotlin.services.s3.model.CommonPrefix
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import aws.sdk.kotlin.services.s3.model.Object as S3Object
import aws.smithy.kotlin.runtime.time.Instant as SmithyInstant

class S3ObjectStoreTest {

    @Test
    fun mapsFoldersAndObjects() {
        val response = ListObjectsV2Response {
            commonPrefixes = listOf(CommonPrefix { prefix = "logs/2026/" })
            contents = listOf(
                S3Object {
                    key = "logs/app.log"
                    size = 1024
                    eTag = "\"abc\""
                    lastModified = SmithyInstant.fromEpochSeconds(1_700_000_000, 0)
                },
            )
            nextContinuationToken = "next"
        }

        val listing = response.toListing(prefix = "logs/", keyName = "dev-key")

        assertEquals("logs/", listing.prefix)
        assertEquals(listOf("logs/2026/"), listing.folders)
        assertEquals(listOf("2026/"), listing.folderNames)
        assertEquals("logs/app.log", listing.objects.single().key)
        assertEquals(1024, listing.objects.single().size)
        assertEquals(1_700_000_000, listing.objects.single().lastModified?.epochSeconds)
        assertEquals("next", listing.nextToken)
        assertEquals("dev-key", listing.keyName)
    }

    @Test
    fun dropsTheFolderMarkerItself() {
        // 「logs/」という 0 バイトのキーは、そのフォルダを開いたときに自分自身として現れる
        val response = ListObjectsV2Response {
            contents = listOf(
                S3Object {
                    key = "logs/"
                    size = 0
                },
            )
        }

        assertEquals(emptyList(), response.toListing(prefix = "logs/", keyName = "k").objects)
    }

    @Test
    fun emptyResponseBecomesEmptyListing() {
        val listing = ListObjectsV2Response { }.toListing(prefix = "", keyName = "k")

        assertEquals(true, listing.isEmpty)
        assertNull(listing.nextToken)
    }
}
