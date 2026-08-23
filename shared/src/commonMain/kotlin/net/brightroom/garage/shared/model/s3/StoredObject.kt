package net.brightroom.garage.shared.model.s3

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * `ListObjectsV2` の 1 ページ。
 *
 * S3 に階層は無いが、`delimiter = "/"` を渡すと共通接頭辞がフォルダのように返る。
 * コンソールはこれをフォルダとして描画する。
 *
 * @param prefix この一覧が対象にしている接頭辞。ルートは空文字。
 * @param folders 共通接頭辞。[prefix] を含んだフルパスで、末尾は `/`。
 * @param nextToken 続きがある場合の継続トークン。無ければ null。
 */
@Serializable
data class ObjectListing(
    val prefix: String,
    val folders: List<String> = emptyList(),
    val objects: List<StoredObject> = emptyList(),
    val nextToken: String? = null,
    /**
     * この一覧を取得したアクセスキーの名前。
     *
     * 「どのキーで見ているか」を画面に出すために運ぶ（spec §6.4 の 6）。
     * accessKeyId と secret は載せない。
     */
    val keyName: String? = null,
) {
    /** [prefix] を取り除いたフォルダ名。画面に出すのはこちら。 */
    val folderNames: List<String> get() = folders.map { it.removePrefix(prefix) }

    val isEmpty: Boolean get() = folders.isEmpty() && objects.isEmpty()
}

@Serializable
data class StoredObject(
    val key: String,
    val size: Long,
    val lastModified: Instant? = null,
    val etag: String? = null,
) {
    /** [prefix] 配下での表示名。 */
    fun nameIn(prefix: String): String = key.removePrefix(prefix)
}

/** 1 階層上の接頭辞。ルート（空文字）には親が無いため null を返す。 */
fun parentPrefix(prefix: String): String? {
    if (prefix.isEmpty()) return null

    val trimmed = prefix.trimEnd('/')
    val cut = trimmed.lastIndexOf('/')

    return if (cut < 0) "" else trimmed.substring(0, cut + 1)
}
