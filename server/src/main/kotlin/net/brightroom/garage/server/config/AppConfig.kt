package net.brightroom.garage.server.config

data class AppConfig(
    val garageAdminEndpoint: String,
    val garageAdminToken: String,
    val garageS3Endpoint: String,
    val garageS3AccessKeyId: String,
    val garageS3SecretAccessKey: String,
    val garageS3Region: String,
    val port: Int,
) {
    companion object {
        fun fromEnv(): AppConfig = AppConfig(
            garageAdminEndpoint = requireEnv("GARAGE_ADMIN_ENDPOINT"),
            garageAdminToken = requireEnv("GARAGE_ADMIN_TOKEN"),
            garageS3Endpoint = requireEnv("GARAGE_S3_ENDPOINT"),
            garageS3AccessKeyId = requireEnv("GARAGE_S3_ACCESS_KEY_ID"),
            garageS3SecretAccessKey = requireEnv("GARAGE_S3_SECRET_ACCESS_KEY"),
            garageS3Region = System.getenv("GARAGE_S3_REGION") ?: "garage",
            port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
        )

        private fun requireEnv(name: String): String =
            System.getenv(name) ?: error("Required environment variable '$name' is not set")
    }
}
