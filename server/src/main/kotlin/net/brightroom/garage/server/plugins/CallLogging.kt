package net.brightroom.garage.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path

/**
 * アクセスログの形式を明示する。
 *
 * 利用者の admin token が毎リクエストで転送されるため、
 * ヘッダを一切ログに含めない形式を固定する。
 */
fun Application.configureCallLogging() {
    install(CallLogging) {
        format { call ->
            val status = call.response.status()?.value ?: "-"
            "$status ${call.request.httpMethod.value} ${call.request.path()}"
        }
    }
}
