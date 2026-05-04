package zelisline.ub.notifications.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.webhook.WebhookEventTypes;
import zelisline.ub.integrations.webhook.application.WebhookEnqueueService;
import zelisline.ub.purchasing.api.dto.ApAgingBuckets;
import zelisline.ub.purchasing.api.dto.ApAgingTotalsResponse;
import zelisline.ub.purchasing.application.SupplierPaymentService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Phase 7 Slice 6 — emits one deduped in-app notification per business per UTC day when
 * ageing buckets show overdue supplier AP (excludes the “current/not yet due” bucket).
 */
@Service
@RequiredArgsConstructor
public class Phase7ApNotificationService {

    private static final Logger log = LoggerFactory.getLogger(Phase7ApNotificationService.class);

    static final String TYPE_OVERDUE_AP = "overdue_ap";

    private static final BigDecimal EPS = new BigDecimal("0.01");
    private static final int TENANT_PAGE_SIZE = 200;

    private final BusinessRepository businessRepository;
    private final SupplierPaymentService supplierPaymentService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final WebhookEnqueueService webhookEnqueueService;

    public void scanAllTenants() {
        LocalDate asOf = LocalDate.now(ZoneOffset.UTC);
        int page = 0;
        while (true) {
            var batch = businessRepository.findByDeletedAtIsNull(PageRequest.of(page, TENANT_PAGE_SIZE));
            for (Business business : batch.getContent()) {
                try {
                    emitOverdueApIfNeeded(business.getId(), asOf);
                } catch (Exception ex) {
                    log.warn("Phase7 overdue AP notification skipped businessId={}", business.getId(), ex);
                }
            }
            if (!batch.hasNext()) {
                break;
            }
            page++;
        }
    }

    /**
     * Used by the nightly scheduler and integration tests (fixed {@code asOf}).
     */
    public void emitOverdueApIfNeeded(String businessId, LocalDate asOf) {
        ApAgingTotalsResponse ap = supplierPaymentService.apAging(businessId, asOf, null);
        BigDecimal overdue = overdueOutsideCurrent(ap.buckets());
        if (overdue.compareTo(EPS) <= 0) {
            return;
        }
        String dedupe = "overdue_ap:" + businessId + ":" + asOf;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("asOf", asOf.toString());
        payload.put("overdueOpenAp", overdue.toPlainString());
        payload.put("totalOpenAp", ap.totalOpen().toPlainString());
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        notificationService.tryInsertDedupe(businessId, TYPE_OVERDUE_AP, dedupe, json);
        webhookEnqueueService.enqueue(
                businessId,
                WebhookEventTypes.INVOICE_OVERDUE,
                payload,
                "invoice.overdue:" + businessId + ":" + asOf);
    }

    private static BigDecimal overdueOutsideCurrent(ApAgingBuckets b) {
        return b.days1To30()
                .add(b.days31To60())
                .add(b.days61To90())
                .add(b.daysOver90());
    }
}
