package zelisline.ub.platform.logs;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Super Admin → Platform → Logs — live request feed.
 *
 * <p>Backed by {@code platform_request_log} (see {@link PlatformRequestLogInterceptor}),
 * which captures every API/webhook request with its category, outcome, duration,
 * tenant and user. The summary endpoint aggregates success/failure per category
 * for a rolling window (default 24 h).
 */
@RestController
@RequestMapping("/api/v1/super-admin/platform/request-logs")
@RequiredArgsConstructor
public class SuperAdminRequestLogsController {

    private final PlatformRequestLogRepository repository;
    private final BusinessRepository businessRepository;

    public record RequestLogRow(
            String id,
            Instant loggedAt,
            String method,
            String path,
            RequestLogCategory category,
            String businessId,
            String businessName,
            String userId,
            String branchId,
            String correlationId,
            int status,
            boolean success,
            long durationMs,
            String ip) {}

    public record CategorySummary(
            RequestLogCategory category,
            long total,
            long success,
            long failed,
            double successRate,
            long avgDurationMs,
            Instant lastAt) {}

    public record RequestLogSummary(
            long windowMinutes,
            long total,
            long success,
            long failed,
            double successRate,
            long expectedMisses,
            List<CategorySummary> categories) {}

    @GetMapping
    public List<RequestLogRow> list(
            @RequestParam(required = false) RequestLogCategory category,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) Integer sinceMinutes,
            @RequestParam(required = false) String ip,
            @RequestParam(defaultValue = "100") int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        Instant since = sinceMinutes != null && sinceMinutes > 0
                ? Instant.now().minus(Duration.ofMinutes(sinceMinutes))
                : null;
        List<PlatformRequestLog> rows = repository.findAll(
                PlatformRequestLogRepository.matches(category, success, since, ip),
                PageRequest.of(0, capped)).getContent();
        Map<String, String> tenantNames = resolveTenantNames(rows);
        return rows.stream().map(p -> toRow(p, tenantNames.get(p.getBusinessId()))).toList();
    }

    @GetMapping("/summary")
    public RequestLogSummary summary(
            @RequestParam(defaultValue = "1440") int windowMinutes) {
        Instant since = windowMinutes > 0
                ? Instant.now().minus(Duration.ofMinutes(windowMinutes))
                : null;

        // Every category appears even when empty, so the UI stays stable.
        Map<RequestLogCategory, CategorySummary> byCategory = new EnumMap<>(RequestLogCategory.class);
        for (RequestLogCategory category : RequestLogCategory.values()) {
            byCategory.put(category, new CategorySummary(category, 0, 0, 0, 0.0, 0, null));
        }
        for (PlatformRequestLogRepository.CategorySummaryRow row : repository.summarySince(since)) {
            RequestLogCategory category = parseCategory(row.getCategory());
            if (category == null) {
                continue;
            }
            long total = row.getTotal();
            long success = row.getOk();
            byCategory.put(category, new CategorySummary(
                    category,
                    total,
                    success,
                    total - success,
                    rate(success, total),
                    Math.round(row.getAvgMs()),
                    row.getLastAt() == null ? null : row.getLastAt().toInstant(ZoneOffset.UTC)));
        }

        long total = 0;
        long success = 0;
        List<CategorySummary> categories = new ArrayList<>(byCategory.values());
        for (CategorySummary summary : categories) {
            total += summary.total();
            success += summary.success();
        }
        return new RequestLogSummary(
                windowMinutes,
                total,
                success,
                total - success,
                rate(success, total),
                repository.countExpectedMissesSince(since),
                categories);
    }

    private static RequestLogCategory parseCategory(String value) {
        if (value == null) {
            return null;
        }
        try {
            return RequestLogCategory.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static double rate(long part, long total) {
        return total == 0 ? 0.0 : Math.round((part * 1000.0) / total) / 10.0;
    }

    private RequestLogRow toRow(PlatformRequestLog p, String businessName) {
        return new RequestLogRow(
                p.getId(),
                p.getLoggedAt(),
                p.getMethod(),
                p.getPath(),
                p.getCategory(),
                p.getBusinessId(),
                businessName,
                p.getUserId(),
                p.getBranchId(),
                p.getCorrelationId(),
                p.getStatus(),
                p.isSuccess(),
                p.getDurationMs(),
                p.getIp());
    }

    private Map<String, String> resolveTenantNames(List<PlatformRequestLog> rows) {
        java.util.Set<String> ids = rows.stream()
                .map(PlatformRequestLog::getBusinessId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return businessRepository.findNamesByIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(
                        BusinessRepository.BusinessNameRow::getId,
                        BusinessRepository.BusinessNameRow::getName,
                        (a, b) -> a));
    }
}
