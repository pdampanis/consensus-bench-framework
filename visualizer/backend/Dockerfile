# =============================================================================
# Consensus Visualizer backend — Spring Boot + Java 21
# =============================================================================
# Multi-stage build: compile with Maven + JDK, ship with a minimal JRE image.
# =============================================================================

# -------- stage 1: build ------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src

# Install maven
RUN apk add --no-cache maven

# Copy pom first to allow Docker layer caching of dependency resolution
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Copy source and build
COPY src ./src
RUN mvn -B -q -DskipTests package

# -------- stage 2: runtime ----------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user
RUN addgroup -S app && adduser -S app -G app
USER app

# Copy the fat jar from the build stage
COPY --from=build /src/target/consensus-visualizer-*.jar /app/app.jar

# Expose HTTP port (must match server.port in application.yml)
EXPOSE 8080

# Healthcheck via the Spring Boot actuator /health endpoint
HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=30s \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

# JVM flags: container-aware memory, reasonable default heap
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
