package net.brightroom.garage.shared.session

enum class IdleState {
    ACTIVE,

    /** まもなく自動ログアウトする。利用者に警告を出す。 */
    WARNING,

    /** 自動ログアウトすべき状態。 */
    EXPIRED,
}

/**
 * 最終操作からの経過時間で自動ログアウトを判定する。
 *
 * 判定の基準は利用者の操作（クリックやキー入力）であり、
 * ポーリングによる通信で [recordActivity] を呼んではならない。
 * 自動更新が走り続けるため、通信の有無では放置を検出できない。
 *
 * 現在時刻を引数で受け取るのは、テストで時計を進められるようにするため。
 */
class IdleTracker(
    startedAtMillis: Long,
    private val timeoutMillis: Long = 30 * 60 * 1000,
    private val warningMillis: Long = 60 * 1000,
) {
    private var lastActivityAtMillis: Long = startedAtMillis

    fun recordActivity(nowMillis: Long) {
        lastActivityAtMillis = nowMillis
    }

    fun state(nowMillis: Long): IdleState {
        val elapsed = nowMillis - lastActivityAtMillis

        return when {
            elapsed >= timeoutMillis -> IdleState.EXPIRED
            elapsed >= timeoutMillis - warningMillis -> IdleState.WARNING
            else -> IdleState.ACTIVE
        }
    }

    /** 自動ログアウトまでの残り時間。期限を過ぎていれば 0。 */
    fun remainingMillis(nowMillis: Long): Long = (lastActivityAtMillis + timeoutMillis - nowMillis).coerceAtLeast(0)
}
