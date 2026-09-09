# ---- Build stage: builds the React frontend, then the Spring Boot fat-jar ----
FROM maven:3.9-eclipse-temurin-17 AS build

# pom.xml's exec-maven-plugin drives the frontend build via `cmd /c "<command>"` (Windows-only,
# see pom.xml's npm-install/npm-build executions). This shim lets that same pom run unmodified
# on Linux by translating a `cmd /c "<command>"` call into a plain shell command.
RUN printf '#!/bin/sh\nshift\nexec sh -c "$1"\n' > /usr/local/bin/cmd \
    && chmod +x /usr/local/bin/cmd

# Node.js/npm — needed by the frontend build the pom triggers during generate-resources.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl gnupg \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY pom.xml .
COPY frontend ./frontend
COPY src ./src

RUN mvn -B -DskipTests clean package

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
COPY --from=build /app/target/breakout-scanner.jar app.jar

# H2 trade-journal store lives at ./data relative to the working dir (see
# application.properties) — keep it in a volume so it survives container restarts.
VOLUME ["/app/data"]

EXPOSE 9111
ENTRYPOINT ["java", "-jar", "app.jar"]
