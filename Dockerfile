# Coolify / container deploy — build context must be `backend/` (or repo root for aminofabian/ub).
# Runtime: Java 21. Listens on 5050 (-Dserver.port in ENTRYPOINT).

FROM gradle:8.14.4-jdk21-alpine AS builder
WORKDIR /app

# Coolify build VMs are often ~2GB total. A Spring Boot fat JAR + a large Gradle
# heap leaves no headroom: the kernel SIGKILLs Gradle (exit 255, logs cut mid-task).
# Keep heap low, one worker, Serial GC, in-process javac (UB_LOW_MEM_BUILD), split steps.
#
# CRITICAL: do NOT put -Xmx in JAVA_TOOL_OPTIONS. Gradle --no-daemon still forks a
# single-use daemon, so JAVA_TOOL_OPTIONS -Xmx doubles the heap (client + daemon)
# and was the exit-255 / truncated-log pattern on compileJava.
# Tiny client via GRADLE_OPTS; real budget only on the daemon (javac shares it).
ENV UB_LOW_MEM_BUILD=1
ENV GRADLE_USER_HOME=/home/gradle/.gradle
# Client JVM only launches Gradle (config runs in the forked single-use daemon),
# so keep it tiny: every MB counts on ~2GB Coolify builders. Daemon budget below.
# Must set -Xms <= -Xmx here: gradlew ships DEFAULT_JVM_OPTS=-Xms64m -Xmx64m and merges
# GRADLE_OPTS after it — without -Xms, client JVM gets Xms64m + Xmx48m → instant VM init failure.
ENV GRADLE_OPTS="-Xms32m -Xmx48m -XX:MaxMetaspaceSize=48m -XX:+UseSerialGC -Xss256k -XX:MaxDirectMemorySize=8m -Dorg.gradle.daemon=false -Dorg.gradle.parallel=false -Dorg.gradle.workers.max=1"
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xss256k"

COPY gradle ./gradle
COPY gradlew build.gradle settings.gradle gradle.properties ./
RUN chmod +x gradlew

# Override local/desktop gradle.properties for the constrained builder.
# No HeapDumpOnOutOfMemoryError — dumps on a 2GB VM often tip the process into SIGKILL.
# ExitOnOutOfMemoryError: if javac really exhausts the heap, fail fast + visibly instead
# of thrashing until the kernel SIGKILLs the container mid-task (exit 255, logs cut).
# configureondemand=false: on-demand config has spiked RSS during compile on small VMs.
# Daemon ~384m is the only large heap; client stays at GRADLE_OPTS 48m.
RUN printf '%s\n' \
	'org.gradle.daemon=false' \
	'org.gradle.parallel=false' \
	'org.gradle.caching=false' \
	'org.gradle.configureondemand=false' \
	'org.gradle.workers.max=1' \
	'org.gradle.vfs.watch=false' \
	'org.gradle.jvmargs=-Xms128m -Xmx384m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -Xss256k -XX:MaxDirectMemorySize=16m -XX:+ExitOnOutOfMemoryError' \
	> gradle.properties

# Prime the exact configurations bootJar needs. Must succeed (no || true): a soft
# failure here used to cache an empty deps layer, then offline compile blew up.
# Resolve runtimeClasspath last so compile + runtime jars are all on disk.
# Bump the echo below when you need to bust a bad Coolify BuildKit cache layer.
RUN echo "deps-prime-v3" \
	&& ./gradlew --no-daemon -x test --no-parallel --max-workers=1 \
		dependencies --configuration compileClasspath \
	&& ./gradlew --no-daemon -x test --no-parallel --max-workers=1 \
		dependencies --configuration annotationProcessor \
	&& ./gradlew --no-daemon -x test --no-parallel --max-workers=1 \
		dependencies --configuration runtimeClasspath

COPY src ./src

# Drop transform/journal/daemon junk so compileJava has more free RAM on ~2GB builders.
# Keep modules-2 / files-2.1 (resolved jars) from the deps-prime layer.
RUN rm -rf /tmp/* \
	"$GRADLE_USER_HOME"/caches/*/transforms* \
	"$GRADLE_USER_HOME"/caches/journal-* \
	"$GRADLE_USER_HOME"/caches/*/scripts \
	"$GRADLE_USER_HOME"/caches/*/executionHistory \
	"$GRADLE_USER_HOME"/daemon \
	"$GRADLE_USER_HOME"/native \
	"$GRADLE_USER_HOME"/workers \
	|| true

# Split compile from packaging so peak RSS during bootJar stays lower.
# Do NOT use --offline with a || online fallback: Coolify scrapes build logs for
# "BUILD FAILED" / "FAILURE" and aborts the deploy even when Docker exit code is 0.
# After a successful deps prime, online resolve is a no-op (cache hit).
# Note: no standalone `classes` step — bootJar pulls in compileJava + processResources
# itself, so a separate daemon fork there only added another memory spike on 2GB builders.
RUN ./gradlew compileJava --no-daemon -x test --no-parallel --max-workers=1 --no-build-cache --stacktrace
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
