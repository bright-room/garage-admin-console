package net.brightroom.garage.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import net.brightroom.garage.server.config.AppConfig
import net.brightroom.garage.server.garage.GarageAdminClient

fun Application.configureDi() {
    val appConfig = AppConfig.from(environment)

    dependencies {
        provide<AppConfig> { appConfig }
        provide<GarageAdminClient> { GarageAdminClient(appConfig.garageAdminEndpoint) }
    }
}
