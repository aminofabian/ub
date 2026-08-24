package zelisline.ub.platform.loadtest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import zelisline.ub.platform.logs.PlatformRequestLogInterceptor;

/**
 * Runs staircase load tests against this instance and estimates how many
 * concurrent users it can sustain.
 *
 * <p>A run ramps {@code maxConcurrency} in {@code steps} stairs, each lasting
 * {@code secondsPerStep}. Every virtual user is a Java virtual thread looping
 * GETs to {@code http://127.0.0.1:{port}{path}} — the request goes through the
 * real Tomcat accept path, security filters, rate limiters and (for DB-bound
 * paths) the connection pool, so the measured ceiling reflects the actual
 * production stack.
 *
 * <p>Trade-offs worth surfacing in the console: the load generator shares the
 * JVM with the server under test (CPU is consumed by both), and only one run
 * may execute at a time.
 */
@Service
public class LoadTestService {

    private static final Logger log = LoggerFactory.getLogger(LoadTestService.class);

    /** In-memory history ring buffer. Restarts wipe it — expected for an ops tool. */
    private static final int HISTORY_CAP = 20;

    /** Rolling latency window used for the live p95 during a run. */
    private static final int LIVE_WINDOW = 2000;

    /** Hard cap on retained latency samples per step (percentiles stay exact). */
    private static final int MAX_SAMPLES_PER_STEP = 200_000;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final double MAX_ERROR_RATE_PCT = 2.0;

    private final LoadTestCapacityService capacityService;
    private final String selfTestBaseUrl;
    private static final AtomicLong runSequence = new AtomicLong();

    private final Counter runsCompleted;
    private final AtomicLong lastRecommendedUsers = new AtomicLong();
    private final AtomicLong lastPeakRpsHundredths = new AtomicLong();
    private final AtomicLong lastDurationSeconds = new AtomicLong();

    private final Object lock = new Object();
    private RunContext active;
    private final ArrayDeque<LoadTestModels.RunSummary> history = new ArrayDeque<>();

    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "loadtest-coordinator");
        t.setDaemon(true);
        return t;
    });

    public LoadTestService(
            LoadTestCapacityService capacityService,
            MeterRegistry meterRegistry,
            @Value("${app.load-test.base-url:http://127.0.0.1:${server.port:5050}}") String selfTestBaseUrl
    ) {
        this.capacityService = capacityService;
        this.selfTestBaseUrl = selfTestBaseUrl;
        this.runsCompleted = meterRegistry.counter("loadtest.runs.completed");
        Gauge.builder("loadtest.last.recommended_users", lastRecommendedUsers, AtomicLong::get)
                .description("Concurrent users the last run recommended")
                .register(meterRegistry);
        Gauge.builder("loadtest.last.peak_rps_hundredths", lastPeakRpsHundredths, AtomicLong::get)
                .description("Peak requests/sec of the last run (x100 to keep it integral)")
                .register(meterRegistry);
        Gauge.builder("loadtest.last.duration_seconds", lastDurationSeconds, AtomicLong::get)
                .description("Duration of the last run in seconds")
                .register(meterRegistry);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public LoadTestModels.StartResponse start(LoadTestModels.RunRequest raw, String callerToken) {
        LoadTestModels.RunRequest request = normalize(raw);
        synchronized (lock) {
            if (active != null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A load test is already running — wait for it to finish or cancel it first.");
            }
            RunContext run = new RunContext(request, callerToken);
            active = run;
            coordinator.submit(() -> execute(run));
            return new LoadTestModels.StartResponse(run.runId);
        }
    }

    public void cancel() {
        RunContext run;
        synchronized (lock) {
            run = active;
        }
        if (run == null) {
            return;
        }
        run.running = false;
        log.info("Load test {} cancelled by operator", run.runId);
    }

    public LoadTestModels.StatusResponse status() {
        RunContext run;
        List<LoadTestModels.RunSummary> historySnapshot;
        synchronized (lock) {
            run = active;
            historySnapshot = new ArrayList<>(history);
        }
        LoadTestModels.CapacitySnapshot capacity = capacityService.snapshot();
        boolean running = run != null && run.running;
        LoadTestModels.LiveProgress progress = run == null ? null : liveProgress(run);
        return new LoadTestModels.StatusResponse(running, progress, capacity, historySnapshot);
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    private void execute(RunContext run) {
        try {
            int maxThreads = capacityService.snapshot().tomcatMaxThreads();
            if (maxThreads > 0 && run.maxConcurrency > maxThreads) {
                run.note("maxConcurrency (" + run.maxConcurrency + ") exceeds Tomcat worker threads ("
                        + maxThreads + ") — extra users queue behind the thread pool");
            }
            for (int i = 1; i <= run.steps && run.running; i++) {
                int concurrency = Math.max(1,
                        (int) Math.round((double) run.maxConcurrency * i / run.steps));
                executeStep(run, i, concurrency);
            }
        } catch (Exception ex) {
            log.warn("Load test {} failed", run.runId, ex);
            run.note("Run failed: " + ex.getMessage());
        } finally {
            run.running = false;
            LoadTestModels.RunSummary summary = buildSummary(run);
            synchronized (lock) {
                active = null;
                history.addFirst(summary);
                while (history.size() > HISTORY_CAP) {
                    history.removeLast();
                }
            }
            runsCompleted.increment();
            lastRecommendedUsers.set(summary.recommendedConcurrentUsers());
            lastPeakRpsHundredths.set(Math.round(summary.peakRps() * 100));
            lastDurationSeconds.set(summary.durationSec());
            log.info("Load test {} finished: recommended={} users peakRps={}",
                    run.runId, summary.recommendedConcurrentUsers(), summary.peakRps());
        }
    }

    private void executeStep(RunContext run, int stepIndex, int concurrency) {
        StepContext step = new StepContext(concurrency);
        run.stepContexts[stepIndex - 1] = step;
        run.currentStep = stepIndex;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(selfTestBaseUrl + run.path))
                .timeout(REQUEST_TIMEOUT)
                .header(PlatformRequestLogInterceptor.LOAD_TEST_HEADER, run.runId);
        if (run.callerToken != null && !run.callerToken.isBlank()) {
            requestBuilder.header(HttpHeaders.AUTHORIZATION, "Bearer " + run.callerToken);
        }
        HttpRequest request = requestBuilder.build();

        step.startedAtNanos = System.nanoTime();
        ExecutorService virtualUsers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int v = 0; v < concurrency; v++) {
                virtualUsers.submit(() -> workerLoop(run, step, request));
            }
            long stepMillis = run.secondsPerStep * 1000L;
            long deadline = System.currentTimeMillis() + stepMillis;
            while (run.running && System.currentTimeMillis() < deadline) {
                Thread.sleep(Math.min(250, Math.max(1, deadline - System.currentTimeMillis())));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            run.note("Run interrupted while waiting on step " + stepIndex);
        } finally {
            step.running = false;
            step.endedAtNanos = System.nanoTime();
            virtualUsers.shutdownNow();
            try {
                if (!virtualUsers.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Load test step {} virtual users did not stop in time", stepIndex);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void workerLoop(RunContext run, StepContext step, HttpRequest request) {
        while (run.running && step.running && !Thread.currentThread().isInterrupted()) {
            long start = System.nanoTime();
            int status;
            try {
                HttpResponse<Void> response = SHARED_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
                status = response.statusCode();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                step.record(-1, (System.nanoTime() - start) / 1_000_000);
                continue;
            }
            step.record(status, (System.nanoTime() - start) / 1_000_000);
        }
    }

    // ── Progress / results ────────────────────────────────────────────────────

    private LoadTestModels.LiveProgress liveProgress(RunContext run) {
        long elapsedSec = (System.nanoTime() - run.startedAtNanos) / 1_000_000_000L;
        long totalSec = (long) run.steps * run.secondsPerStep;
        long remainingSec = Math.max(0, totalSec - elapsedSec);
        StepContext step = run.currentStep >= 1 ? run.stepContexts[run.currentStep - 1] : null;
        if (step == null) {
            return new LoadTestModels.LiveProgress(
                    run.runId, run.path, run.currentStep, run.steps, 0, run.maxConcurrency,
                    elapsedSec, remainingSec, 0, 0, 0);
        }
        double stepElapsed = (System.nanoTime() - step.startedAtNanos) / 1_000_000_000.0;
        double rps = stepElapsed > 0 ? step.requests.sum() / stepElapsed : 0;
        long liveP95;
        synchronized (step.sampleLock) {
            liveP95 = percentile(step.liveWindow, 95);
        }
        return new LoadTestModels.LiveProgress(
                run.runId, run.path, run.currentStep, run.steps, step.concurrency, run.maxConcurrency,
                elapsedSec, remainingSec, round1(rps), liveP95, step.errors.sum());
    }

    private LoadTestModels.RunSummary buildSummary(RunContext run) {
        List<LoadTestModels.StepResult> steps = new ArrayList<>();
        double peakRps = 0;
        for (int i = 0; i < run.stepContexts.length; i++) {
            StepContext step = run.stepContexts[i];
            if (step == null) {
                continue;
            }
            double elapsedSec = (step.endedAtNanos - step.startedAtNanos) / 1_000_000_000.0;
            long requests = step.requests.sum();
            double rps = elapsedSec > 0 ? requests / elapsedSec : 0;
            long p50;
            long p95;
            long p99;
            synchronized (step.sampleLock) {
                p50 = percentile(step.latencies, 50);
                p95 = percentile(step.latencies, 95);
                p99 = percentile(step.latencies, 99);
            }
            long errors = step.errors.sum();
            double errorRatePct = requests > 0 ? errors * 100.0 / requests : 0;
            Map<Integer, Long> statusCodes = new LinkedHashMap<>();
            step.statusCodes.forEach((code, count) -> statusCodes.put(code, count.sum()));

            steps.add(new LoadTestModels.StepResult(
                    i + 1, step.concurrency, requests, round1(rps),
                    p50, p95, p99, errors, round1(errorRatePct), statusCodes));
            peakRps = Math.max(peakRps, rps);
        }

        int recommended = 0;
        long recommendedP95 = 0;
        for (LoadTestModels.StepResult step : steps) {
            if (step.errorRatePct() < MAX_ERROR_RATE_PCT && step.p95Ms() <= run.targetP95Ms) {
                recommended = step.concurrency();
                recommendedP95 = step.p95Ms();
            }
        }
        if (steps.isEmpty()) {
            run.note("Run ended before any step completed (cancelled or failed).");
        } else if (recommended == 0) {
            run.note("No step met the target (p95 \u2264 " + run.targetP95Ms
                    + " ms and errors < 2%). Capacity is below the smallest step — lower the concurrency or scale replicas.");
        } else if (recommended == run.maxConcurrency) {
            run.note("Ceiling not reached — the highest step still met targets. Raise max concurrency to find the real limit.");
        }
        if (steps.stream().anyMatch(s -> s.statusCodes().containsKey(429))) {
            run.note("429 responses observed — a rate limiter capped throughput. Authenticated or non-public paths avoid this.");
        }

        long durationSec = Math.max(1, (System.nanoTime() - run.startedAtNanos) / 1_000_000_000L);
        return new LoadTestModels.RunSummary(
                run.runId,
                run.path,
                Instant.ofEpochMilli(run.startedAtEpochMs).toString(),
                durationSec,
                run.maxConcurrency,
                recommended,
                round1(peakRps),
                recommendedP95,
                run.targetP95Ms,
                steps,
                run.notes
        );
    }

    private static long percentile(Collection<Long> samples, double p) {
        if (samples.isEmpty()) {
            return 0;
        }
        long[] sorted = samples.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return Math.max(0, sorted[Math.max(0, idx)]);
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private LoadTestModels.RunRequest normalize(LoadTestModels.RunRequest raw) {
        String path = raw == null || raw.path() == null ? "/actuator/health" : raw.path().trim();
        if (path.isEmpty() || !path.startsWith("/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "path must be a URL path starting with / (e.g. /actuator/health).");
        }
        int maxConcurrency = clamp(raw == null || raw.maxConcurrency() == null ? 200 : raw.maxConcurrency(), 1, 400);
        int steps = clamp(raw == null || raw.steps() == null ? 4 : raw.steps(), 1, 8);
        int secondsPerStep = clamp(raw == null || raw.secondsPerStep() == null ? 10 : raw.secondsPerStep(), 1, 60);
        int targetP95Ms = clamp(raw == null || raw.targetP95Ms() == null ? 800 : raw.targetP95Ms(), 100, 5000);
        return new LoadTestModels.RunRequest(path, maxConcurrency, steps, secondsPerStep, targetP95Ms);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ── Run / step context ────────────────────────────────────────────────────

    private static final class RunContext {
        final String runId;
        final String path;
        final String callerToken;
        final int maxConcurrency;
        final int steps;
        final int secondsPerStep;
        final int targetP95Ms;
        final long startedAtEpochMs = System.currentTimeMillis();
        final long startedAtNanos = System.nanoTime();
        final StepContext[] stepContexts;
        final List<String> notes = new ArrayList<>();
        volatile boolean running = true;
        volatile int currentStep;

        RunContext(LoadTestModels.RunRequest request, String callerToken) {
            this.runId = "lt-" + System.currentTimeMillis() + "-" + runSequence.incrementAndGet();
            this.path = request.path();
            this.callerToken = callerToken;
            this.maxConcurrency = request.maxConcurrency();
            this.steps = request.steps();
            this.secondsPerStep = request.secondsPerStep();
            this.targetP95Ms = request.targetP95Ms();
            this.stepContexts = new StepContext[request.steps()];
        }

        synchronized void note(String message) {
            notes.add(message);
        }
    }

    private static final class StepContext {
        final int concurrency;
        final LongAdder requests = new LongAdder();
        final LongAdder errors = new LongAdder();
        final ConcurrentHashMap<Integer, LongAdder> statusCodes = new ConcurrentHashMap<>();
        final Object sampleLock = new Object();
        final List<Long> latencies = new ArrayList<>();
        final ArrayDeque<Long> liveWindow = new ArrayDeque<>();
        volatile boolean running = true;
        volatile long startedAtNanos;
        volatile long endedAtNanos;

        StepContext(int concurrency) {
            this.concurrency = concurrency;
        }

        void record(int status, long latencyMs) {
            requests.increment();
            if (status < 0 || status >= 400) {
                errors.increment();
            }
            statusCodes.computeIfAbsent(status, key -> new LongAdder()).increment();
            synchronized (sampleLock) {
                if (latencies.size() < MAX_SAMPLES_PER_STEP) {
                    latencies.add(latencyMs);
                }
                liveWindow.addLast(latencyMs);
                while (liveWindow.size() > LIVE_WINDOW) {
                    liveWindow.removeFirst();
                }
            }
        }
    }

    /**
     * Shared client: connection reuse across steps. The JDK client is thread-safe
     * and blocking {@code send} runs on per-user virtual threads.
     */
    private static final HttpClient SHARED_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
}
