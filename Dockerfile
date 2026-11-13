FROM gradle:9-jdk21@sha256:c0ce93e022ea2e705332dabe090019c749356576fc8fe39c38129b2aae9ed68f AS build
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY . .
RUN gradle :server:buildFatJar --no-daemon

FROM eclipse-temurin:25.0.5_8-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918
WORKDIR /app
COPY --from=build /app/server/build/libs/garage-admin-console-all.jar /app/server.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
