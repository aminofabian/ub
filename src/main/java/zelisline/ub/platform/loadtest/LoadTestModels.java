package zelisline.ub.platform.loadtest;

import java.util.List;
import java.util.Map;

/**
 * Wire DTOs for the Super Admin load-test console.
 *
 * <p>Records serialize to plain JSON — the Next.js console reads these field
 * names verbatim (see {@code frontend/lib/super-admin-api.ts}).
 */
public final class LoadTestModels {

    private LoadTestModels() {
    }

    /** POST /api/v1/super-admin/load-test/run body. Nulls fall back to defaults. */
    public record RunRequest(
            String path,
            Integer maxConcurrency,
            Integer steps,
            Integer secondsPerStep,
            Integer targetP95Ms
    ) {
    }

    /** One stair of the load ramp: N concurrent users for {@code secondsPerStep}s. */
    public record StepResult(
            int step,
            int concurrency,
            long requests,
            double rps,
            long p50Ms,
            long p95Ms,
            long p99Ms,
            long errors,
            double errorRatePct,
            Map<Integer, Long> statusCodes
    ) {
    }

    /** A completed run, kept in the console history for comparison. */
    public record RunSummary(
            String runId,
            String path,
            String startedAt,
            long durationSec,
            int maxConcurrency,
            int recommendedConcurrentUsers,
            double peakRps,
            long recommendedP95Ms,
            int targetP95Ms,
            List<StepResult> steps,
            List<String> notes
    ) {
    }

    /** Live progress for the currently executing run (polled by the console). */
    public record LiveProgress(
            String runId,
            String path,
            int step,
            int steps,
            int concurrency,
            int maxConcurrency,
            long elapsedSec,
            long remainingSec,
            double liveRps,
            long liveP95Ms,
            long errors
    ) {
    }

    /** Live capacity readout for the instance the console runs on. */
    public record CapacitySnapshot(
            String ticketStore,
            boolean redisConfigured,
            int activeWsConnections,
            int wsMaxPerUser,
            int wsMaxPerBusiness,
            int tomcatMaxThreads,
            int tomcatActiveThreads,
            int tomcatQueued,
            long tomcatOpenConnections,
            int dbPoolMax,
            int dbPoolActive,
            int dbPoolIdle,
            int dbPoolAwaiting,
            long dbRoundTripMs,
            long jvmHeapUsedMb,
            long jvmHeapMaxMb,
            double processCpuLoad,
            String selfTestBaseUrl,
            String hint
    ) {
    }

    /** GET /status aggregate — everything the console needs for one paint. */
    public record StatusResponse(
            boolean running,
            LiveProgress run,
            CapacitySnapshot capacity,
            List<RunSummary> history
    ) {
    }

    public record StartResponse(String runId) {
    }
}
