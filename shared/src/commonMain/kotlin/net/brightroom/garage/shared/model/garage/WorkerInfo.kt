package net.brightroom.garage.shared.model.garage

import kotlinx.serialization.Serializable

/**
 * ワーカーの状態。
 *
 * Garage は文字列 3 種とオブジェクト 1 種を同じフィールドに返す（OpenAPI の oneOf）。
 */
@Serializable(with = WorkerStateSerializer::class)
sealed interface WorkerState {

    /** Garage が返す語をそのまま使う。運用者が CLI の出力と突き合わせられる。 */
    val label: String

    data object Idle : WorkerState {
        override val label: String = "idle"
    }

    data object Busy : WorkerState {
        override val label: String = "busy"
    }

    data object Done : WorkerState {
        override val label: String = "done"
    }

    /** 待機を挟みながら動いている。[durationSecs] は 1 回の待ち時間。 */
    data class Throttled(val durationSecs: Double) : WorkerState {
        override val label: String = "throttled"
    }
}

/** `ListWorkers` / `GetWorkerInfo` の要素。 */
@Serializable
data class WorkerInfo(
    val id: Long,
    val name: String,
    val state: WorkerState,
    val errors: Long = 0,
    val consecutiveErrors: Long = 0,
    val lastError: WorkerLastError? = null,
    /** 高いほど控えめに動く。設定できるワーカーだけが値を持つ。 */
    val tranquility: Int? = null,
    val progress: String? = null,
    val queueLength: Long? = null,
    val persistentErrors: Long? = null,
    /** ワーカーが自分で書く説明行。パースせずそのまま出す。 */
    val freeform: List<String> = emptyList(),
)

@Serializable
data class WorkerLastError(val message: String, val secsAgo: Long)
