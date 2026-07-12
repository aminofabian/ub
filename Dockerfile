# Coolify / container deploy — build context must be `backend/` (or repo root for aminofabian/ub).
# Runtime: Java 21. Listens on 5050 (-Dserver.port in ENTRYPOINT).

FROM gradle:8.14.4-jdk21-alpine AS builder
WORKDIR /app

# Coolify build VMs are often ~2GB total. A 125MB Spring Boot fat JAR + Xmx1g
# leaves no headroom: the kernel SIGKILLs Gradle (exit 255, logs cut mid-task).
# Keep heap ≤768m, one worker, Serial GC, and split compile vs package.
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.parallel=false -Dorg.gradle.workers.max=1"
ENV GRADLE_USER_HOME=/home/gradle/.gradle
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xss512k"

COPY gradle ./gradle
COPY gradlew build.gradle settings.gradle gradle.properties ./
RUN chmod +x gradlew

# Override local/desktop gradle.properties for the constrained builder.
RUN printf '%s\n' \
	'org.gradle.daemon=false' \
	'org.gradle.parallel=false' \
	'org.gradle.caching=true' \
	'org.gradle.workers.max=1' \
	'org.gradle.jvmargs=-Xmx640m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -Xss512k -XX:+HeapDumpOnOutOfMemoryError' \
	> gradle.properties

# Prime dependency cache (layer reused when only src changes).
RUN ./gradlew dependencies --no-daemon -x test --no-parallel --max-workers=1 \
	|| ./gradlew help --no-daemon \
	|| true

COPY src ./src

# Split compile from packaging so peak RSS during bootJar stays lower and
# Coolify logs show which phase died if the builder is still too small.
RUN ./gradlew classes --no-daemon -x test --no-parallel --max-workers=1 --stacktrace
RUN ./gradlew bootJar --no-daemon -x test --no-parallel --max-workers=1 --no-build-cache --stacktrace \
	&& JAR="$(ls -1 build/libs/*.jar | grep -v -- '-plain.jar$' | head -n1)" \
	&& test -n "$JAR" && test -s "$JAR" \
	&& cp "$JAR" /app/application.jar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# curl = Dockerfile HEALTHCHECK; wget = Coolify deployment probe (uses wget by default).
RUN apk add --no-cache curl wget \
	&& addgroup -g 1000 -S spring && adduser -u 1000 -S spring -G spring
COPY --from=builder /app/application.jar app.jar
USER spring:spring
EXPOSE 5050
ENV SERVER_PORT=5050
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
# Spring Boot prod profile + Flyway can take 60–120s on cold start; Coolify must use the same grace period.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
	CMD wget -qO- http://127.0.0.1:5050/actuator/health >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${SERVER_PORT:-5050} -jar app.jar"]
