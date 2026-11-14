# ==============================================================================
# MiniRedis: Production Multi-Stage Dockerfile (Optimized for Render Free 512MB)
# Builds pure Java 17 Redis-compatible engine with zero external runtime deps.
# ==============================================================================

# Stage 1: Build
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

COPY pom.xml .
COPY src ./src

RUN apk add --no-cache maven && \
    mvn clean package -DskipTests

# Stage 2: Minimal Production Runtime
FROM eclipse-temurin:17-jre-alpine

LABEL org.opencontainers.image.title="MiniRedis" \
      org.opencontainers.image.description="High-Performance Redis-Compatible In-Memory Database in Pure Java 17" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.licenses="MIT"

WORKDIR /app

# Create non-root system user & data directory
RUN addgroup -S miniredis && \
    adduser -S -G miniredis -h /app miniredis && \
    mkdir -p /data && \
    chown -R miniredis:miniredis /app /data && \
    apk add --no-cache curl

COPY --from=builder --chown=miniredis:miniredis /build/target/miniredis-java-1.0.0.jar /app/miniredis-java.jar

VOLUME ["/data"]

# Default Render HTTP port
ENV PORT=8080
EXPOSE 8080

USER miniredis

# Memory-tuned JVM options for Render Free 512MB cgroup container:
# Heap (160m) + Metaspace (80m) + CodeCache (32m) + ThreadStacks (256k) leaves ~150MB buffer
ENV JAVA_OPTS="-Xms64m -Xmx160m -Xss256k -XX:MaxMetaspaceSize=80m -XX:ReservedCodeCacheSize=32m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
ENV MINIREDIS_AOF_DIR="/data"
ENV MINIREDIS_DEMO_MODE="true"

# Health Check targeting standard /api/health probe
HEALTHCHECK --interval=20s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:${PORT:-8080}/api/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/miniredis-java.jar --dir \"$MINIREDIS_AOF_DIR\" --web-port \"${PORT:-8080}\" --demo"]
