package net.brightroom.garage.shared.model.garage

/**
 * ライフサイクルルールの適用条件を、画面が扱いやすい平らな形にしたもの。
 *
 * S3 は「条件が 1 つならフィルタ直下、2 つ以上なら `And` の中」という決まりを持つ。
 * その差は画面に出さず、ここで吸収する。`And` の入れ子は 1 段までしか扱わない（P2-5）。
 */
data class FilterConditions(
    val prefix: String? = null,
    val sizeGreaterThan: Long? = null,
    val sizeLessThan: Long? = null,
) {
    val isEmpty: Boolean
        get() = prefix == null && sizeGreaterThan == null && sizeLessThan == null

    private val count: Int
        get() = listOfNotNull(prefix, sizeGreaterThan, sizeLessThan).size

    /** S3 が受け付ける形に戻す。条件が 2 つ以上なら `And` でくくる。 */
    fun toFilter(): LifecycleFilter? {
        if (isEmpty) return null

        val flat = LifecycleFilter(
            prefix = prefix,
            objectSizeGreaterThan = sizeGreaterThan,
            objectSizeLessThan = sizeLessThan,
        )

        return if (count == 1) flat else LifecycleFilter(and = flat)
    }
}

/** `And` の有無にかかわらず、条件を平らに取り出す。 */
fun LifecycleFilter?.toConditions(): FilterConditions {
    if (this == null) return FilterConditions()

    val source = and ?: this

    return FilterConditions(
        prefix = source.prefix,
        sizeGreaterThan = source.objectSizeGreaterThan,
        sizeLessThan = source.objectSizeLessThan,
    )
}
