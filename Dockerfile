# ==============================================================================
# MiniRedis: Production Multi-Stage Dockerfile
# Builds the pure Java 17 Redis-compatible engine with zero runtime dependencies.
# ==============================================================================

# Stage 1: Build
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper & pom
COPY pom.xml .
COPY src ./src

# Install Maven in alpine builder
RUN apk add --no-cache maven && \
    mvn clean package -DskipTests

# Stage 2: Minimal Production Runtime
FROM eclipse-temurin:17-jre-alpine

LABEL org.opencontainers.image.title="MiniRedis" \
      org.opencontainers.image.description="High-Performance Redis-Compatible In-Memory Database in Pure Java 17" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.licenses="MIT"

WORKDIR /app

# Create non-root system user & persistent data directory
RUN addgroup -S miniredis && \
    adduser -S -G miniredis -h /app miniredis && \
    mkdir -p /data && \
    chown -R miniredis:miniredis /app /data && \
    apk add --no-cache curl

# Copy compiled executable JAR from builder stage
COPY --from=builder --chown=miniredis:miniredis /build/target/miniredis-java-1.0.0.jar /app/miniredis-java.jar

# Persistent storage for Append-Only File (AOF) durability
VOLUME ["/data"]

# Default ports: 6379 (RESP2 TCP), 8080 (REST API & Web Console)
EXPOSE 6379 8080

USER miniredis

# Production JVM flags optimized for low latency and bounded container memory
ENV JAVA_OPTS="-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENV MINIREDIS_AOF_DIR="/data"
ENV MINIREDIS_DEMO_MODE="false"

# Container Health Check targeting standard /api/health probe
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/miniredis-java.jar --dir \"$MINIREDIS_AOF_DIR\""]
