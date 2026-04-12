package net.brightroom.garage.server.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import net.brightroom.garage.server.config.AppConfig
import net.brightroom.garage.server.service.GarageAdminClient
import net.brightroom.garage.server.service.GarageS3Service

fun Application.configureDi() {
    val appConfig = AppConfig.from(environment)

    dependencies {
        provide<AppConfig> { appConfig }
        provide<GarageAdminClient> { GarageAdminClient(appConfig) }
        provide<GarageS3Service> { GarageS3Service(appConfig) }
    }
}
