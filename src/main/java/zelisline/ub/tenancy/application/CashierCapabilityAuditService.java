package zelisline.ub.tenancy.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;

/**
 * Writes a diary row whenever an owner or admin flips a flag that widens or
 * narrows what a cashier may do at the till. Loosening a control (turning
 * "allow clear sale" on) is WARN so it shows under "Needs a look"; tightening
 * is INFO.
 *
 * <p>Only the flags below are watched. Everything else in feature flags
 * (loaders, beeps, catalog mode) is cosmetic and would only add noise.
 */
@Service
@RequiredArgsConstructor
public class CashierCapabilityAuditService {

    /** Flag key → the label the diary prints. Order is display order. */
    static final Map<String, String> WATCHED = Map.ofEntries(
            Map.entry(FeatureFlagService.FLAG_POS_CASHIER_CLEAR_SALE, "Allow cashiers to clear a sale"),
            Map.entry(FeatureFlagService.FLAG_POS_CASHIER_CLEAR_ALL_SALES, "Allow cashiers to clear all open sales"),
            Map.entry(FeatureFlagService.FLAG_POS_CASHIER_PRICE_EDIT, "Allow cashiers to edit prices at the till"),
            Map.entry(FeatureFlagService.FLAG_POS_CASHIER_DRAWOUT, "Allow cashiers to request drawouts"),
            Map.entry(FeatureFlagService.FLAG_POS_CASHIER_CREATE_PRODUCT, "Allow cashiers to create products"),
            Map.entry(FeatureFlagService.FLAG_POS_CASHIER_WEIGHED_TOGGLE, "Allow cashiers to toggle weighed items"),
            Map.entry(FeatureFlagService.FLAG_POS_CASHIER_MPESA_CODE, "Allow cashiers to enter an M-Pesa transaction code"),
            Map.entry(FeatureFlagService.FLAG_POS_CASHIER_ORDER_CONFIRM, "Allow cashiers to confirm orders")
    );

    private final StorefrontSettingsService storefrontSettingsService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    /**
     * Compare the watched flags in two settings documents and publish one event
     * per flag whose effective value changed. Absent means off.
     */
    public void publishChanges(String businessId, String actorUserId, String beforeSettings, String afterSettings) {
        Map<String, Boolean> before = flags(beforeSettings);
        Map<String, Boolean> after = flags(afterSettings);
        for (Map.Entry<String, String> watched : WATCHED.entrySet()) {
            String key = watched.getKey();
            boolean was = Boolean.TRUE.equals(before.get(key));
            boolean now = Boolean.TRUE.equals(after.get(key));
            if (was == now) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("flag", key);
            metadata.put("label", watched.getValue());
            metadata.put("loosened", now);
            auditEventPublisher.publish(auditEventBuilder.builder(
                            AuditEventCategory.SECURITY,
                            AuditEventTypes.SETTINGS_CASHIER_CAPABILITY_CHANGED,
                            now ? AuditEventSeverity.WARN : AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .actor(actorUserId, actorUserId == null ? AuditEventActorType.SYSTEM : AuditEventActorType.USER)
                    .target("setting", key)
                    .targetLabel(watched.getValue())
                    .oldState(Map.of("enabled", was))
                    .newState(Map.of("enabled", now))
                    .source("dashboard")
                    .metadata(metadata)
                    .build());
        }
    }

    /** Watched flag keys, for callers that want to know whether a patch could matter. */
    public static List<String> watchedFlags() {
        return List.copyOf(WATCHED.keySet());
    }

    private Map<String, Boolean> flags(String settings) {
        if (settings == null || settings.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Boolean> f = storefrontSettingsService.readTenantConfig(settings, "").featureFlags();
            return f == null ? Map.of() : f;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
