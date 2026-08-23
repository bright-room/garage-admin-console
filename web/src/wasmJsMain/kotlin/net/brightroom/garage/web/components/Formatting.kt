package net.brightroom.garage.web.components

private const val UNIT = 1024.0

/** バイト数を人が読める単位にする。 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"

    var value = bytes.toDouble()
    val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB")
    var index = -1

    while (value >= UNIT && index < units.lastIndex) {
        value /= UNIT
        index++
    }

    val rounded = ((value * 10).toLong()).toDouble() / 10
    return "$rounded ${units[index]}"
}
