FROM maven:3.9.9-eclipse-temurin-21

# Create non-root UID 1000 user (reuse existing UID 1000 user if present, e.g. ubuntu on Noble)
RUN if ! id -u 1000 >/dev/null 2>&1; then \
      groupadd -g 1000 sandbox && useradd -u 1000 -g 1000 -m -s /bin/sh sandbox; \
    fi && \
    mkdir -p /opt/m2 /workspace && \
    chown -R 1000:1000 /opt/m2 /workspace

# Warm the Maven repository with a real `mvn test` on demo-service so Surefire
# providers, compiler plugins, Spring Boot, JPA, and H2 are all cached for offline runs.
COPY --chown=1000:1000 demo-service/pom.xml /tmp/warmup/pom.xml
COPY --chown=1000:1000 demo-service/src /tmp/warmup/src

USER 1000:1000
RUN cd /tmp/warmup && \
    mvn -B -Dmaven.repo.local=/opt/m2 test && \
    rm -rf /tmp/warmup

WORKDIR /workspace
