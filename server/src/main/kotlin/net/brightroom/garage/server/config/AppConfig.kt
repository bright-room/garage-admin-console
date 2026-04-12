package net.brightroom.garage.server.config

import io.ktor.server.application.ApplicationEnvironment

data class AppConfig(
    val garageAdminEndpoint: String,
    val garageAdminToken: String,
    val garageS3Endpoint: String,
    val garageS3AccessKeyId: String,
    val garageS3SecretAccessKey: String,
    val garageS3Region: String,
) {
    companion object {
        fun from(environment: ApplicationEnvironment): AppConfig {
            val config = environment.config
            return AppConfig(
                garageAdminEndpoint = config.property("garage.admin.endpoint").getString(),
                garageAdminToken = config.property("garage.admin.token").getString(),
                garageS3Endpoint = config.property("garage.s3.endpoint").getString(),
                garageS3AccessKeyId = config.property("garage.s3.accessKeyId").getString(),
                garageS3SecretAccessKey = config.property("garage.s3.secretAccessKey").getString(),
                garageS3Region = config.property("garage.s3.region").getString(),
            )
        }
    }
}
