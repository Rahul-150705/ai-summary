# ════════════════════════════════════════════════════════════════════════════
# Stage 1 — BUILD
# Uses a full Maven + JDK image to compile the project and produce a fat JAR.
# This stage is discarded after the build; only the JAR is kept.
# ════════════════════════════════════════════════════════════════════════════
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /app

# Copy pom.xml first and download dependencies separately.
# This layer is cached by Docker — dependencies are NOT re-downloaded
# on every build unless pom.xml changes (big speed boost).
COPY pom.xml .
RUN mvn dependency:go-offline -B --no-transfer-progress

# Now copy source and build the JAR (skip tests — run those separately)
COPY src ./src
RUN mvn package -DskipTests -B --no-transfer-progress

# ════════════════════════════════════════════════════════════════════════════
# Stage 2 — RUNTIME
# Uses a minimal JRE-only image (no Maven, no JDK source) — much smaller.
# eclipse-temurin:17-jre-alpine is ~180 MB vs ~600 MB for the full JDK image.
# ════════════════════════════════════════════════════════════════════════════
FROM eclipse-temurin:17-jre-alpine AS runtime

WORKDIR /app

# Add a non-root user for security (best practice — never run as root)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy the compiled JAR from the builder stage
COPY --from=builder /app/target/*.jar app.jar

# Expose the port Spring Boot listens on
EXPOSE 8080

# JVM tuning for containers:
#   -XX:+UseContainerSupport   → respect Docker memory limits (default in JDK 11+)
#   -XX:MaxRAMPercentage=75.0  → use up to 75% of container's allocated RAM
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]
