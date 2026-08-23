package net.brightroom.garage.server.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SecretCacheTest {

    private val credentials = S3Credentials(
        accessKeyId = "GK01",
        secretAccessKey = "s3cr3t",
        keyName = "dev-key",
        bucketName = "dev-bucket",
    )

    private class FakeClock(var now: Instant = Instant.fromEpochSeconds(0)) {
        fun advance(minutes: Int) {
            now = now.plus(minutes.minutes)
        }
    }

    @Test
    fun returnsWhatWasStored() {
        val cache = SecretCache()

        cache.put("hash", "b1", credentials)

        assertEquals(credentials, cache.get("hash", "b1"))
    }

    @Test
    fun separatesTokensAndBuckets() {
        val cache = SecretCache()

        cache.put("hash-a", "b1", credentials)

        assertNull(cache.get("hash-b", "b1"))
        assertNull(cache.get("hash-a", "b2"))
    }

    @Test
    fun expiresAfterTtl() {
        val clock = FakeClock()
        val cache = SecretCache(ttl = 5.minutes, now = { clock.now })

        cache.put("hash", "b1", credentials)
        clock.advance(4)
        assertEquals(credentials, cache.get("hash", "b1"))

        clock.advance(2)
        assertNull(cache.get("hash", "b1"))
    }

    @Test
    fun sweepsExpiredEntriesOnPut() {
        // 期限切れの掃除は get() が同じキーで呼ばれたときにしか起きない。
        // put() の先頭でも掃かれることを、別キーの put() 経由で確認する
        val clock = FakeClock()
        val cache = SecretCache(ttl = 5.minutes, now = { clock.now })

        cache.put("hash-a", "b1", credentials)
        clock.advance(6)
        assertEquals(1, cache.size)

        cache.put("hash-b", "b1", credentials)

        assertEquals(1, cache.size)
        assertNull(cache.get("hash-a", "b1"))
    }

    @Test
    fun purgeDropsEveryBucketOfThatToken() {
        val cache = SecretCache()
        cache.put("hash-a", "b1", credentials)
        cache.put("hash-a", "b2", credentials)
        cache.put("hash-b", "b1", credentials)

        cache.purge("hash-a")

        assertNull(cache.get("hash-a", "b1"))
        assertNull(cache.get("hash-a", "b2"))
        assertEquals(credentials, cache.get("hash-b", "b1"))
    }

    @Test
    fun hashesTokenWithSha256() {
        // 生のトークンをキーにしないこと。ハッシュは決定的で、値そのものを含まない
        val hash = hashToken("dev-console-token")

        assertEquals(64, hash.length)
        assertEquals(hash, hashToken("dev-console-token"))
        assertNotEquals(hash, hashToken("other-token"))
        assertEquals(false, hash.contains("dev-console-token"))
    }

    @Test
    fun credentialsDoNotLeakSecretInToString() {
        assertEquals(false, credentials.toString().contains("s3cr3t"))
    }

    @Test
    fun sizeReflectsStoredEntries() {
        val cache = SecretCache()
        assertEquals(0, cache.size)

        cache.put("hash-a", "b1", credentials)
        cache.put("hash-a", "b2", credentials)
        assertEquals(2, cache.size)

        cache.purge("hash-a")
        assertEquals(0, cache.size)
    }
}
