# Content Analytics Backend - multi-stage build
#
#   Stage 1 (build)  : Maven + JDK 21 compiles the code and packages a fat jar.
#   Stage 2 (runtime): slim JRE 21 runs only the jar - no Maven, no source, no
#                      build cache ships in the final image.
#
# Build & run the whole stack with one command from this folder:
#   docker compose up --build
# 

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy only the POM first so Docker can cache the dependency download layer.
# Dependencies are re-downloaded only when pom.xml actually changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Now copy the sources and build. Tests are skipped here because CI
# (.github/workflows/ci.yml) already runs the full suite on every push.
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# curl is used by the container HEALTHCHECK below (Actuator liveness probe).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as an unprivileged user - the app never needs root.
RUN useradd --system --uid 1001 --create-home appuser

# Create the writable data directories and hand them to the unprivileged user
# BEFORE dropping root, so the /data volume (uploaded documents + profile
# pictures) is actually writable at runtime. A named volume inherits this
# ownership on first mount; skipping the chown makes every upload fail.
RUN mkdir -p /data/uploads /data/profile-pictures \
    && chown -R appuser:appuser /data
USER appuser

# The Spring Boot fat jar produced in stage 1.
COPY --from=build /app/target/content-analytics-backend-*.jar app.jar

# Local file storage lives on a mountable volume so uploaded documents and
# profile pictures survive container restarts. These two paths are overridden
# here so a volume mounted at /data covers both.
ENV APP_STORAGE_LOCAL_PATH=/data/uploads \
    APP_PROFILE_PICTURE_STORAGE_PATH=/data/profile-pictures
VOLUME ["/data"]

# Cap the JVM heap to 75% of the container's memory limit so the GC has headroom
# inside a constrained (e.g. free-tier) container instead of the host.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8080

# Actuator liveness probe - returns 200 once the Spring context is up.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
