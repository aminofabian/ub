# Coolify / container deploy — build context must be `backend/` (this directory).
# Runtime: Java 21. Default port 5050 (override with SERVER_PORT).

FROM gradle:8.14.4-jdk21-alpine AS builder
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
# Omit slow tests in image builds; run ./gradlew check in CI before tag.
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Coolify / orchestration healthchecks often shell out to curl; JRE images omit it by default.
RUN apk add --no-cache curl \
	&& addgroup -g 1000 -S spring && adduser -u 1000 -S spring -G spring
COPY --from=builder /app/build/libs/*.jar app.jar
USER spring:spring
EXPOSE 5050
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
# Grace period covers Flyway + JPA init (~15s+ observed); path matches management.endpoints base /actuator.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
	CMD curl -fsS http://127.0.0.1:5050/actuator/health >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
