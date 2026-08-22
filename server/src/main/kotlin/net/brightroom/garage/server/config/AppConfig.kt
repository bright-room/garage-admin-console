package net.brightroom.garage.server.config

import io.ktor.server.application.ApplicationEnvironment

/**
 * サーバーの設定。
 *
 * admin token は含まない。トークンは利用者がブラウザで入力し、
 * リクエストごとに転送されるため、サーバーは保持しない。
 */
data class AppConfig(
    val garageAdminEndpoint: String,
) {
    companion object {
        fun from(environment: ApplicationEnvironment): AppConfig =
            AppConfig(
                garageAdminEndpoint = environment.config
                    .property("garage.admin.endpoint")
                    .getString(),
            )
    }
}
