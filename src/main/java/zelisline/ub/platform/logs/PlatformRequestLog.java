package zelisline.ub.platform.logs;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/** One handled API/webhook request, captured by {@link PlatformRequestLogInterceptor}. */
@Entity
@Table(name = "platform_request_log")
@Getter
@Setter
public class PlatformRequestLog {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "logged_at", nullable = false)
    private Instant loggedAt;

    @Column(name = "method", length = 10, nullable = false)
    private String method;

    @Column(name = "path", length = 512, nullable = false)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 32, nullable = false)
    private RequestLogCategory category;

    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "branch_id", length = 36)
    private String branchId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "status", nullable = false)
    private int status;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "ip", length = 64)
    private String ip;

    /** Load-test run id (e.g. {@code lt-…}) when the request came from the load-test console. */
    @Column(name = "load_test_run_id", length = 36)
    private String loadTestRunId;
}
