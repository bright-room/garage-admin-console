package net.brightroom.garage.server.config

import kotlinx.serialization.Serializable

/**
 * サーバーの設定。`application.yaml` の `garage` セクションをそのまま受ける。
 *
 * admin token は含まない。トークンは利用者がブラウザで入力し、
 * リクエストごとに転送されるため、サーバーは保持しない。
 */
@Serializable
data class AppConfig(val admin: Admin) {
    @Serializable
    data class Admin(val endpoint: String)
}
