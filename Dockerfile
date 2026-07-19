# Coolify / container deploy — build context must be `backend/` (or repo root for aminofabian/ub).
# Runtime: Java 21. Listens on 5050 (-Dserver.port in ENTRYPOINT).

FROM gradle:8.14.4-jdk21-alpine AS builder
WORKDIR /app

# Coolify build VMs are often ~2GB total. A Spring Boot fat JAR + a large Gradle
# heap leaves no headroom: the kernel SIGKILLs Gradle (exit 255, logs cut mid-task).
# Keep heap low, one worker, Serial GC, and split compile vs package.
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.parallel=false -Dorg.gradle.workers.max=1"
ENV GRADLE_USER_HOME=/home/gradle/.gradle
# Cap every forked JVM (Gradle + javac) — without -Xmx here, child processes can
# grow past the builder and get SIGKILL with no Java stacktrace in Coolify logs.
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xss512k -Xmx384m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=32m"

COPY gradle ./gradle
COPY gradlew build.gradle settings.gradle gradle.properties ./
RUN chmod +x gradlew

# Override local/desktop gradle.properties for the constrained builder.
# No HeapDumpOnOutOfMemoryError — dumps on a 2GB VM often tip the process into SIGKILL.
# configureondemand=false: on-demand config has spiked RSS during compile on small VMs.
RUN printf '%s\n' \
	'org.gradle.daemon=false' \
	'org.gradle.parallel=false' \
	'org.gradle.caching=false' \
	'org.gradle.configureondemand=false' \
	'org.gradle.workers.max=1' \
	'org.gradle.vfs.watch=false' \
	'org.gradle.jvmargs=-Xmx384m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -Xss512k -XX:MaxDirectMemorySize=32m' \
	> gradle.properties

# Prime dependency cache (layer reused when only src changes).
RUN ./gradlew dependencies --no-daemon -x test --no-parallel --max-workers=1 \
	|| ./gradlew help --no-daemon \
	|| true

COPY src ./src

# Drop transform/journal junk so compileJava has more free RAM on ~2GB builders.
RUN rm -rf /tmp/* \
	"$GRADLE_USER_HOME"/caches/*/transforms \
	"$GRADLE_USER_HOME"/caches/journal-* \
	"$GRADLE_USER_HOME"/daemon \
	|| true

# Split compile from packaging so peak RSS during bootJar stays lower and
# Coolify logs show which phase died if the builder is still too small.
RUN ./gradlew compileJava --no-daemon -x test --no-parallel --max-workers=1 --no-build-cache --stacktrace
RUN ./gradlew classes --no-daemon -x test --no-parallel --max-workers=1 --no-build-cache --stacktrace
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
# Spring Boot prod + Flyway/Hibernate often need 60–180s on cold start.
# Coolify UI healthcheck must also use a matching start/grace period (or it
# ignores this Dockerfile HEALTHCHECK and rolls back while the JVM is still booting).
# Prefer liveness (process up) over full /actuator/health (DB/Redis readiness).
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=5 \
	CMD wget -qO- http://127.0.0.1:5050/actuator/health/liveness >/dev/null \
		|| wget -qO- http://127.0.0.1:5050/actuator/health >/dev/null \
		|| exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${SERVER_PORT:-5050} -jar app.jar"]
