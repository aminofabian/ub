# Coolify / container deploy — build context must be `backend/` (or repo root for aminofabian/ub).
# Runtime: Java 21. Listens on 5050 (-Dserver.port in ENTRYPOINT).

FROM gradle:8.14.4-jdk21-alpine AS builder
WORKDIR /app

# Coolify build VMs are often ~2GB. Parallel workers + a 100MB+ fat JAR
# spike past that and the kernel SIGKILLs Gradle (exit 255, truncated logs).
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.parallel=false -Dorg.gradle.workers.max=2"
ENV GRADLE_USER_HOME=/home/gradle/.gradle
# Serial GC keeps peak RSS lower than G1 on small builders.
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC"

COPY gradle ./gradle
COPY gradlew build.gradle settings.gradle gradle.properties ./
RUN chmod +x gradlew

# Override gradle.properties heap for the container builder (file keeps
# higher defaults for local/desktop builds).
RUN printf '%s\n' \
	'org.gradle.daemon=false' \
	'org.gradle.parallel=false' \
	'org.gradle.caching=true' \
	'org.gradle.workers.max=2' \
	'org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC -XX:+HeapDumpOnOutOfMemoryError' \
	> gradle.properties

# Prime dependency cache (layer reused when only src changes).
RUN ./gradlew dependencies --no-daemon -x test --no-parallel \
	|| ./gradlew classes --no-daemon -x test --no-parallel \
	|| true

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test --no-parallel --stacktrace

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# curl = Dockerfile HEALTHCHECK; wget = Coolify deployment probe (uses wget by default).
RUN apk add --no-cache curl wget \
	&& addgroup -g 1000 -S spring && adduser -u 1000 -S spring -G spring
COPY --from=builder /app/build/libs/*.jar app.jar
USER spring:spring
EXPOSE 5050
ENV SERVER_PORT=5050
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
# Spring Boot prod profile + Flyway can take 60–120s on cold start; Coolify must use the same grace period.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
	CMD wget -qO- http://127.0.0.1:5050/actuator/health >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${SERVER_PORT:-5050} -jar app.jar"]
