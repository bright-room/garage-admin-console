package net.brightroom.garage.shared.navigation

/**
 * URL とスクリーンの対応。
 *
 * ブラウザの History API と組み合わせて使うが、この型自体は純粋な変換であり
 * ブラウザ API に依存しない。
 */
sealed interface Route {

    /** この画面を指す正規の URL パス。 */
    val path: String

    data object Overview : Route {
        override val path: String = "/"
    }

    data object Login : Route {
        override val path: String = "/login"
    }

    data class NotFound(val requested: String) : Route {
        override val path: String get() = requested
    }

    companion object {

        fun parse(rawPath: String): Route {
            val normalized = normalize(rawPath)

            return when (normalized) {
                "" -> Overview
                "/login" -> Login
                else -> NotFound(normalized)
            }
        }

        /** クエリと fragment を落とし、末尾スラッシュを取り除く。 */
        private fun normalize(rawPath: String): String {
            val withoutFragment = rawPath.substringBefore('#')
            val withoutQuery = withoutFragment.substringBefore('?')
            val trimmed = withoutQuery.trimEnd('/')

            return if (trimmed == "/") "" else trimmed
        }
    }
}
