package zelisline.ub.platform.logs;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.web.CorrelationIdFilter;
import zelisline.ub.tenancy.infrastructure.TenantRequestAttributes;

/**
 * Records every handled API/webhook request into {@code platform_request_log}
 * so Super Admin → Platform → Logs can show live traffic with per-category
 * success counts.
 *
 * <p>Best-effort and side-effect free: a failed write (e.g. DB hiccup) is
 * logged and never propagated, so request logging can never break a request.
 */
public class PlatformRequestLogInterceptor implements HandlerInterceptor {

    private static final Logger log =
            LoggerFactory.getLogger(PlatformRequestLogInterceptor.class);

    private static final String START_ATTR =
            PlatformRequestLogInterceptor.class.getName() + ".startNanos";

    /** Header the load-test console sets on its self-test requests. */
    public static final String LOAD_TEST_HEADER = "X-Palmart-Load-Test";

    private final PlatformRequestLogRepository repository;
    private final RequestLogClassifier classifier;

    public PlatformRequestLogInterceptor(
            PlatformRequestLogRepository repository,
            RequestLogClassifier classifier) {
        this.repository = repository;
        this.classifier = classifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTR, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        Object start = request.getAttribute(START_ATTR);
        if (!(start instanceof Long started)) {
            return;
        }
        try {
            PlatformRequestLog row = new PlatformRequestLog();
            row.setId(UUID.randomUUID().toString());
            row.setLoggedAt(Instant.now());
            row.setMethod(request.getMethod());
            String path = request.getRequestURI();
            String query = request.getQueryString();
            String full = (query == null || query.isBlank()) ? path : path + "?" + query;
            row.setPath(full.length() > 512 ? full.substring(0, 512) : full);
            row.setCategory(classifier.classify(path));

            Object businessId = request.getAttribute(TenantRequestAttributes.BUSINESS_ID);
            row.setBusinessId(businessId instanceof String s && !s.isBlank() ? s : null);

            CurrentTenantUser.optionalHuman(request).ifPresent(principal -> {
                row.setUserId(principal.userId());
                row.setBranchId(principal.branchId());
                if (row.getBusinessId() == null) {
                    row.setBusinessId(principal.businessId());
                }
            });

            String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
            row.setCorrelationId(correlationId == null || correlationId.isBlank() ? null : correlationId);

            int status = response.getStatus();
            row.setStatus(status);
            row.setSuccess(status >= 200 && status < 400);
            row.setDurationMs((System.nanoTime() - started) / 1_000_000);
            row.setIp(clientIp(request));
            row.setLoadTestRunId(loadTestRunId(request));

            repository.save(row);
        } catch (Exception e) {
            // Logging must never disturb the request it observes.
            log.warn("Failed to record platform request log for {} {}: {}",
                    request.getMethod(), request.getRequestURI(), e.getMessage());
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            return first.length() > 64 ? first.substring(0, 64) : first;
        }
        String remote = request.getRemoteAddr();
        return remote == null ? null : remote;
    }

    private static String loadTestRunId(HttpServletRequest request) {
        String value = request.getHeader(LOAD_TEST_HEADER);
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > 36 ? trimmed.substring(0, 36) : trimmed;
    }
}
