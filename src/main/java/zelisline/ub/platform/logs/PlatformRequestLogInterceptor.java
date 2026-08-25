package zelisline.ub.platform.logs;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;

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

    private static final ObjectMapper META_MAPPER = new ObjectMapper();

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
            // Unhandled exceptions can leave status 200 until the container rewrites it.
            if (ex != null && status < 400) {
                status = 500;
            }
            row.setStatus(status);
            row.setSuccess(status >= 200 && status < 400);
            row.setDurationMs((System.nanoTime() - started) / 1_000_000);
            row.setIp(clientIp(request));
            row.setLoadTestRunId(loadTestRunId(request));
            row.setUserAgent(clipHeader(request.getHeader("User-Agent"), 512));

            if (!row.isSuccess()) {
                applyFailureDetail(row, request, ex);
            }

            repository.save(row);
        } catch (Exception e) {
            // Logging must never disturb the request it observes.
            log.warn("Failed to record platform request log for {} {}: {}",
                    request.getMethod(), request.getRequestURI(), e.getMessage());
        }
    }

    private static void applyFailureDetail(PlatformRequestLog row, HttpServletRequest request, Exception ex) {
        // Prefer Problem+JSON captured by GlobalExceptionHandler (ex is usually null there).
        String title = attr(request, PlatformRequestLogErrorCapture.ATTR_TITLE);
        String detail = attr(request, PlatformRequestLogErrorCapture.ATTR_DETAIL);
        String type = attr(request, PlatformRequestLogErrorCapture.ATTR_TYPE);
        String exceptionClass = attr(request, PlatformRequestLogErrorCapture.ATTR_EXCEPTION_CLASS);
        String exceptionChain = attr(request, PlatformRequestLogErrorCapture.ATTR_EXCEPTION_CHAIN);
        String stack = attr(request, PlatformRequestLogErrorCapture.ATTR_STACK);
        String problemJson = attr(request, PlatformRequestLogErrorCapture.ATTR_PROBLEM_JSON);

        if (ex != null) {
            PlatformRequestLogErrorCapture.capture(request, null, ex);
            if (title == null) {
                title = attr(request, PlatformRequestLogErrorCapture.ATTR_TITLE);
            }
            if (detail == null) {
                detail = attr(request, PlatformRequestLogErrorCapture.ATTR_DETAIL);
            }
            if (exceptionClass == null) {
                exceptionClass = attr(request, PlatformRequestLogErrorCapture.ATTR_EXCEPTION_CLASS);
            }
            if (exceptionChain == null) {
                exceptionChain = attr(request, PlatformRequestLogErrorCapture.ATTR_EXCEPTION_CHAIN);
            }
            if (stack == null) {
                stack = attr(request, PlatformRequestLogErrorCapture.ATTR_STACK);
            }
        }

        if (title == null || title.isBlank()) {
            title = "HTTP " + row.getStatus();
        }
        if (detail == null || detail.isBlank()) {
            detail = switch (row.getStatus()) {
                case 400 -> "Bad request";
                case 401 -> "Unauthorized";
                case 403 -> "Forbidden";
                case 404 -> "Not found";
                case 409 -> "Conflict";
                case 422 -> "Unprocessable entity";
                case 429 -> "Too many requests";
                case 500 -> "Internal server error";
                case 502 -> "Bad gateway";
                case 503 -> "Service unavailable";
                default -> "Request failed with status " + row.getStatus();
            };
        }

        row.setErrorTitle(PlatformRequestLogErrorCapture.clip(title, 255));
        row.setErrorDetail(PlatformRequestLogErrorCapture.clip(detail, 12_000));
        row.setErrorType(PlatformRequestLogErrorCapture.clip(type, 255));
        row.setExceptionClass(PlatformRequestLogErrorCapture.clip(exceptionClass, 255));
        row.setExceptionChain(PlatformRequestLogErrorCapture.clip(exceptionChain, 4_000));
        row.setStackSummary(PlatformRequestLogErrorCapture.clip(stack, 16_000));

        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("contentType", request.getContentType());
            meta.put("accept", request.getHeader("Accept"));
            meta.put("origin", request.getHeader("Origin"));
            meta.put("referer", request.getHeader("Referer"));
            meta.put("query", request.getQueryString());
            if (problemJson != null && !problemJson.isBlank()) {
                meta.put("problemJson", problemJson);
            }
            row.setRequestMeta(PlatformRequestLogErrorCapture.clip(
                    META_MAPPER.writeValueAsString(meta), 8_000));
        } catch (Exception ignored) {
            // Best-effort.
        }
    }

    private static String attr(HttpServletRequest request, String key) {
        Object value = request.getAttribute(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static String clipHeader(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return PlatformRequestLogErrorCapture.clip(value.trim(), max);
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
