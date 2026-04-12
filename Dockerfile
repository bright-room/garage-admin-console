FROM gradle:8-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle :server:buildFatJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/server/build/libs/garage-admin-console-all.jar /app/server.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
