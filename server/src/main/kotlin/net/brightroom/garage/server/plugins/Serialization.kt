package net.brightroom.garage.server.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json

/**
 * Garage のレスポンスと自身のレスポンスの双方に使う JSON 設定。
 *
 * Garage は将来のバージョンでフィールドを増やしうるため、未知のキーは無視する。
 */
val GarageJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(GarageJson)
    }
}
