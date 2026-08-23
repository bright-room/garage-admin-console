package net.brightroom.garage.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.config.property
import io.ktor.server.plugins.di.dependencies
import net.brightroom.garage.server.api.OverviewService
import net.brightroom.garage.server.config.AppConfig
import net.brightroom.garage.server.garage.GarageAdminClient
import net.brightroom.garage.server.s3.S3CredentialResolver
import net.brightroom.garage.server.s3.S3ObjectStore
import net.brightroom.garage.server.s3.SecretCache

fun Application.configureDi() {
    val appConfig: AppConfig = property("garage")

    dependencies {
        provide<AppConfig> { appConfig }
        provide<GarageAdminClient> { GarageAdminClient(appConfig.admin.endpoint) }
        provide<OverviewService> { OverviewService(resolve<GarageAdminClient>()) }
        provide<SecretCache> { SecretCache() }
        provide<S3ObjectStore> { S3ObjectStore(appConfig.s3) }
        provide<S3CredentialResolver> {
            S3CredentialResolver(resolve<GarageAdminClient>(), resolve<SecretCache>())
        }
    }
}
