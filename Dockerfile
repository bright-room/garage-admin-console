FROM gradle:9-jdk21@sha256:d5c815d1281e3edc7e986ca573b1b309639747fa0d91a2199adadf327cfdf634 AS build
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY . .
RUN gradle :server:buildFatJar --no-daemon

FROM eclipse-temurin:25.0.4_7-jre-alpine@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682
WORKDIR /app
COPY --from=build /app/server/build/libs/garage-admin-console-all.jar /app/server.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
