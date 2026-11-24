FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Create non-root UID 1000 matching sandbox container UID so bind-mounted
# /var/autoheal/runs/<runId> directories are writable by both backend and sandbox.
RUN groupadd -g 1000 autoheal || true \
    && useradd -u 1000 -g 1000 -m -s /bin/bash autoheal || true \
    && mkdir -p /var/autoheal/runs \
    && chown -R 1000:1000 /app /var/autoheal/runs

COPY --from=build --chown=1000:1000 /build/target/autoheal-backend-*.jar /app/app.jar

USER 1000:1000
EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
