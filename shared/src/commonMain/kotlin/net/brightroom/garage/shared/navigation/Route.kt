package net.brightroom.garage.shared.navigation

/**
 * URL とスクリーンの対応。
 *
 * ブラウザの History API と組み合わせて使うが、この型自体は純粋な変換であり
 * ブラウザ API に依存しない。
 */
sealed interface Route {

    /** この画面を指す正規の URL パス。クエリを持つ画面はそれも含む。 */
    val path: String

    data object Overview : Route {
        override val path: String = "/"
    }

    data object Login : Route {
        override val path: String = "/login"
    }

    data object Buckets : Route {
        override val path: String = "/buckets"
    }

    data class BucketDetail(val id: String) : Route {
        override val path: String get() = "/buckets/$id"
    }

    data object Keys : Route {
        override val path: String = "/keys"
    }

    data class KeyDetail(val id: String) : Route {
        override val path: String get() = "/keys/$id"
    }

    /**
     * オブジェクトブラウザ。
     *
     * @param prefix 表示中のフォルダ。ルート直下は空文字。URL のクエリに載る。
     */
    data class Objects(val bucketId: String, val prefix: String = "") : Route {
        override val path: String
            get() = if (prefix.isEmpty()) {
                "/objects/$bucketId"
            } else {
                "/objects/$bucketId?prefix=${percentEncode(prefix)}"
            }
    }

    data class NotFound(val requested: String) : Route {
        override val path: String get() = requested
    }

    companion object {

        fun parse(rawPath: String): Route {
            val withoutFragment = rawPath.substringBefore('#')
            val path = normalize(withoutFragment.substringBefore('?'))
            val query = withoutFragment.substringAfter('?', "")
            val segments = path.split('/').filter { it.isNotEmpty() }

            return when {
                segments.isEmpty() -> Overview

                segments.size == 1 && segments[0] == "login" -> Login

                segments.size == 1 && segments[0] == "buckets" -> Buckets

                segments.size == 2 && segments[0] == "buckets" -> BucketDetail(segments[1])

                segments.size == 1 && segments[0] == "keys" -> Keys

                segments.size == 2 && segments[0] == "keys" -> KeyDetail(segments[1])

                segments.size == 2 && segments[0] == "objects" ->
                    Objects(segments[1], queryValue(query, "prefix").orEmpty())

                else -> NotFound(path)
            }
        }

        /** 末尾スラッシュを取り除く。ルートは空文字にそろえる。 */
        private fun normalize(rawPath: String): String {
            val trimmed = rawPath.trimEnd('/')

            return if (trimmed == "/") "" else trimmed
        }

        private fun queryValue(query: String, name: String): String? = query
            .split('&')
            .firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let(::percentDecode)
    }
}
