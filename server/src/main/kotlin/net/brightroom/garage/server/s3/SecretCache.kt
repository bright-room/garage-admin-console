package net.brightroom.garage.server.s3

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * 導出した S3 資格情報の短命なキャッシュ（spec §6.4 の 4）。
 *
 * オブジェクト操作のたびに GetBucketInfo と GetKeyInfo を呼ばないためにある。
 *
 * キーは **admin token のハッシュ**とバケット ID の組である。生のトークンは
 * 保持しない（spec §6.2）。ハッシュを引けるのは同じトークンを提示できる者だけなので、
 * 別の利用者のキャッシュには到達できない。
 *
 * キャッシュが有効な間は、Garage 側で admin token を失効させても最大 TTL 分は
 * オブジェクト操作が通り続ける（`objectRoutes` はキャッシュヒット時に Garage へ
 * 検証を投げないため）。
 */
class SecretCache(private val ttl: Duration = 5.minutes, private val now: () -> Instant = { Clock.System.now() }) {
    private data class Entry(val credentials: S3Credentials, val expiresAt: Instant)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(tokenHash: String, bucketId: String): S3Credentials? {
        val key = cacheKey(tokenHash, bucketId)
        val entry = entries[key] ?: return null

        if (entry.expiresAt <= now()) {
            // 対象のインスタンスが一致する場合のみ削除する。期限切れと判定した直後に
            // 別スレッドが同じキーへ put() していても、その新しいエントリを消さない
            entries.remove(key, entry)
            return null
        }

        return entry.credentials
    }

    fun put(tokenHash: String, bucketId: String, credentials: S3Credentials) {
        // 配線（定期的な sweepExpired() 呼び出し）が外れていても最低限の掃除が残るよう、
        // put() でも掃く
        sweepExpired()

        entries[cacheKey(tokenHash, bucketId)] = Entry(credentials, now().plus(ttl))
    }

    /**
     * 期限切れのエントリを掃く。
     *
     * TTL はあくまで論理的な有効期限であり、それ自体はエントリを取り除かない。
     * get()/put() は触られたキーしか掃かないため、最後の操作以降誰も cache に
     * 触れなければ、期限切れの平文 secret がプロセス終了までヒープに残りかねない。
     * アプリケーションのライフサイクルに紐づく定期ジョブなど、外部から能動的に
     * 呼び出すことを想定する。
     */
    fun sweepExpired() {
        val nowInstant = now()
        entries.values.removeAll { it.expiresAt <= nowInstant }
    }

    /** ログアウト時にそのトークン配下をすべて捨てる（spec §6.6）。 */
    fun purge(tokenHash: String) {
        entries.keys.removeAll { it.startsWith("$tokenHash:") }
    }

    /** テスト用。 */
    val size: Int get() = entries.size

    private fun cacheKey(tokenHash: String, bucketId: String) = "$tokenHash:$bucketId"
}

/**
 * admin token の SHA-256（hex）。
 *
 * キャッシュのキーに使う。元のトークンは復元できない。
 */
fun hashToken(token: String): String = MessageDigest.getInstance("SHA-256")
    .digest(token.encodeToByteArray())
    .joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
