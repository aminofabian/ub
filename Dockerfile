# Coolify / container deploy — build context must be `backend/` (or repo root for aminofabian/ub).
# Runtime: Java 21. Listens on 5050 (-Dserver.port in ENTRYPOINT).

FROM gradle:8.14.4-jdk21-alpine AS builder
WORKDIR /app

# Avoid OOM / daemon issues in constrained Docker build hosts (Coolify).
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.parallel=true"
ENV GRADLE_USER_HOME=/home/gradle/.gradle

COPY gradle ./gradle
COPY gradlew build.gradle settings.gradle gradle.properties ./
RUN chmod +x gradlew

# Prime dependency cache (layer reused when only src changes).
RUN ./gradlew dependencies --no-daemon -x test \
	|| ./gradlew classes --no-daemon -x test \
	|| true

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test --stacktrace

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl \
	&& addgroup -g 1000 -S spring && adduser -u 1000 -S spring -G spring
COPY --from=builder /app/build/libs/*.jar app.jar
USER spring:spring
EXPOSE 5050
ENV SERVER_PORT=5050
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
	CMD curl -fsS http://127.0.0.1:5050/actuator/health >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=5050 -jar app.jar"]
