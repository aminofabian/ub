package zelisline.ub.crm.api;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.domain.CrmWebhookEvent;
import zelisline.ub.crm.repository.CrmWebhookEventRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Super Admin → the raw inbound WhatsApp envelope log, so unrouted traffic is visible without SQL.
 *
 * <p>{@code business_id} is null on rows whose {@code phone_number_id} is not routed to a shop
 * (see {@code InboundIngest}) — those are exactly the messages that never reach a merchant inbox.
 * Authorized by the {@code /api/v1/super-admin/**} rule in {@code SecurityConfig}.
 */
@Validated
@RestController
@RequestMapping("/api/v1/super-admin/platform/crm-webhook-events")
@RequiredArgsConstructor
public class SuperAdminCrmWebhookEventsController {

    private static final int MAX_LIMIT = 200;
    private static final int PREVIEW_CHARS = 500;

    private final CrmWebhookEventRepository repository;
    private final BusinessRepository businessRepository;

    public record EventRow(
            String id,
            String wamid,
            String phoneNumberId,
            String businessId,
            String businessName,
            Instant processedAt,
            Instant createdAt,
            String rawPreview
    ) {
    }

    @GetMapping
    public List<EventRow> list(
            @RequestParam(defaultValue = "60") int limit,
            @RequestParam(defaultValue = "false") boolean unroutedOnly
    ) {
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        Pageable page = PageRequest.of(0, capped);
        List<CrmWebhookEvent> rows = (unroutedOnly
                ? repository.findByBusinessIdIsNullOrderByCreatedAtDesc(page)
                : repository.findAllByOrderByCreatedAtDesc(page)).getContent();
        Map<String, String> names = businessNames(rows);
        return rows.stream()
                .map(e -> new EventRow(
                        e.getId(),
                        e.getWamid(),
                        e.getPhoneNumberId(),
                        e.getBusinessId(),
                        names.get(e.getBusinessId()),
                        e.getProcessedAt(),
                        e.getCreatedAt(),
                        preview(e.getRawJson())))
                .toList();
    }

    private Map<String, String> businessNames(List<CrmWebhookEvent> rows) {
        Set<String> ids = rows.stream()
                .map(CrmWebhookEvent::getBusinessId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        return businessRepository.findNamesByIds(ids).stream()
                .collect(Collectors.toMap(
                        BusinessRepository.BusinessNameRow::getId,
                        BusinessRepository.BusinessNameRow::getName,
                        (a, b) -> a));
    }

    private static String preview(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.length() > PREVIEW_CHARS ? raw.substring(0, PREVIEW_CHARS) + "…" : raw;
    }
}
