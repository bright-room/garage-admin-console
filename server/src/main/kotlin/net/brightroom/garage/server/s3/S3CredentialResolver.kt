package net.brightroom.garage.server.s3

import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.garage.getBucketInfo
import net.brightroom.garage.server.garage.getKeyInfo
import net.brightroom.garage.shared.model.garage.BucketKey

/**
 * admin token から S3 の資格情報を導出する（spec §6.4）。
 *
 * scope で事前に判定はしない。GetBucketInfo や GetKeyInfo が 403 を返したら
 * そのまま伝播させ、S3 ブラウザだけが縮退する（spec §6.3）。
 *
 * global alias を持たないバケットは、選んだキーの local alias でしかアドレスできない
 * （spec §6.5）。そのためキー選択も local alias を持つキーに限定する。そうしないと、
 * 優先度の最上位キーが local alias を持たないだけで、実際にはアドレスできるバケットが
 * 「S3 でアドレスできない」と誤って報告される。
 */
class S3CredentialResolver(private val client: GarageAdminClient, private val cache: SecretCache) {

    suspend fun resolve(token: String, bucketId: String): S3Credentials {
        val tokenHash = hashToken(token)

        cache.get(tokenHash, bucketId)?.let { return it }

        val bucket = client.getBucketInfo(token, bucketId)
        val globalAlias = bucket.globalAliases.firstOrNull()

        val usableKeys = bucket.keys.filter { it.permissions.rank > 0 }
        if (usableKeys.isEmpty()) throw NoUsableKeyException(bucketId)

        // global alias が無いバケットは、導出したキーの local alias でしかアドレスできない
        // （spec §6.5）。優先度の規則はアドレスできるキーの中で適用する
        val candidates = if (globalAlias == null) {
            usableKeys.filter { it.bucketLocalAliases.isNotEmpty() }
        } else {
            usableKeys
        }
        val key = candidates.selectForObjectAccess() ?: throw BucketNotAddressableException(bucketId)

        val bucketName = globalAlias ?: key.bucketLocalAliases.first()

        // showSecretKey=true を付けたときだけ secret が入る。Garage が返さないのは想定外
        val secret = client.getKeyInfo(token, key.accessKeyId, showSecret = true).secretAccessKey
            ?: error("GetKeyInfo が secretAccessKey を返しませんでした")

        val credentials = S3Credentials(
            accessKeyId = key.accessKeyId,
            secretAccessKey = secret,
            keyName = key.name,
            bucketName = bucketName,
        )

        cache.put(tokenHash, bucketId, credentials)

        return credentials
    }
}

/**
 * オブジェクト操作に使うキーを選ぶ。
 *
 * owner > read+write > read の優先度。同順位のときは accessKeyId の昇順で
 * 決定的に選ぶ（実行のたびに使うキーが変わると、画面の「どのキーで見ているか」
 * の表示も揺れるため）。read が無いキーは一覧すらできないので対象外。
 */
private fun List<BucketKey>.selectForObjectAccess(): BucketKey? = this
    .filter { it.permissions.rank > 0 }
    .minWithOrNull(
        compareByDescending<BucketKey> { it.permissions.rank }.thenBy { it.accessKeyId },
    )
