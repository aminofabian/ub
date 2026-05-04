package zelisline.ub.reporting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Operator-facing feature flags for the Phase 7 reporting platform (PHASE_7_PLAN.md
 * §Slice 1 deliverable: "Feature flag: disable MV reads → fallback to Phase 6-style
 * query (slower) in emergency").
 *
 * <p>Defaults are conservative — MV reads are <em>off</em> until Slice 2+ tables exist
 * and the scheduler is wired in production; tests don't accidentally activate the
 * scheduler from {@code @SpringBootTest}.</p>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.reporting")
public class ReportingFeatureFlags {

    /** Allow read facades to consult summary tables; when {@code false} they fall back to journal/OLTP. */
    private boolean mvReadsEnabled = false;

    /** Toggle the {@link zelisline.ub.reporting.scheduler scheduler tick} that drives MV refreshes. */
    private Refresh refresh = new Refresh();

    @Getter
    @Setter
    public static class Refresh {
        private boolean enabled = false;
        /** Cron string (Spring 6 style) for the periodic MV refresh tick. */
        private String cron = "0 5 * * * *";
        /** IANA TZ used to interpret the cron above. */
        private String zone = "UTC";
    }
}
