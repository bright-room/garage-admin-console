package net.brightroom.garage.server.garage

import io.ktor.http.HttpStatusCode

/**
 * Garage が非 2xx を返したことを表す。
 *
 * scope 制限による 403 は正常系であり、呼び出し側は [status] で扱いを分ける。
 * Garage が返したステータスをそのまま運ぶ（独自のエラー分類は設けない）。
 */
class GarageException(
    val status: HttpStatusCode,
    val operation: String,
    override val message: String,
) : RuntimeException(message)
