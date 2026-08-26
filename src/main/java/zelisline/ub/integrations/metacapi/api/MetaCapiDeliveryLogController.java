package zelisline.ub.integrations.metacapi.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.metacapi.domain.MetaCapiEvent;
import zelisline.ub.integrations.metacapi.repository.MetaCapiEventRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Super Admin → restricted delivery audit for Meta Conversions API events.
 *
 * <p>Backed by the durable outbox rows ({@code meta_capi_events}): full request
 * and response bodies (which contain IP/User-Agent/fbp/fbc) are exposed only
 * here, under {@code /api/v1/super-admin/**} (SUPER_ADMIN role only) — never
 * through the tenant-visible audit stream.
 */
@RestController
@RequestMapping("/api/v1/super-admin/meta-capi/events")
@RequiredArgsConstructor
public class MetaCapiDeliveryLogController {

    private static final int MAX_LOG_BODY = 8000;

    private final MetaCapiEventRepository eventRepository;
    private final BusinessRepository businessRepository;

    public record DeliveryLogRow(
            String id,
            String businessId,
            String businessName,
            String pixelId,
            String eventName,
            String eventId,
            String status,
            Integer httpStatus,
            int attemptCount,
            Instant createdAt,
            Instant sentAt,
            String error,
            String requestJson,
            String responseJson) {}

    @GetMapping
    public List<DeliveryLogRow> list(
            @RequestParam(required = false) String businessId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer sinceMinutes,
            @RequestParam(defaultValue = "100") int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        Instant since = sinceMinutes != null && sinceMinutes > 0
                ? Instant.now().minus(Duration.ofMinutes(sinceMinutes))
                : null;
        List<MetaCapiEvent> rows = eventRepository.findForLog(
                blankToNull(businessId),
                blankToNull(status),
                since,
                PageRequest.of(0, capped));
        Map<String, String> tenantNames = resolveTenantNames(rows);
        return rows.stream()
                .map(e -> toRow(e, tenantNames.get(e.getBusinessId())))
                .toList();
    }

    private Map<String, String> resolveTenantNames(List<MetaCapiEvent> rows) {
        List<String> ids = rows.stream().map(MetaCapiEvent::getBusinessId).distinct().toList();
        return businessRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Business::getId, Business::getName));
    }

    private DeliveryLogRow toRow(MetaCapiEvent e, String businessName) {
        return new DeliveryLogRow(
                e.getId(),
                e.getBusinessId(),
                businessName,
                e.getPixelId(),
                e.getEventName(),
                e.getEventId(),
                e.getStatus(),
                e.getHttpStatus(),
                e.getAttemptCount(),
                e.getCreatedAt(),
                e.getSentAt(),
                e.getError(),
                truncate(e.getRequestJson(), MAX_LOG_BODY),
                truncate(e.getResponseJson(), MAX_LOG_BODY)
        );
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }
}
