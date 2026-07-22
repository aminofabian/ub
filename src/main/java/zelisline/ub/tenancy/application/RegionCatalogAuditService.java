package zelisline.ub.tenancy.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;

/**
 * Funnel / hardening telemetry for regional onboarding and catalog resolution.
 * Failures are swallowed so analytics never block product flows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegionCatalogAuditService {

    public static final String SOURCE_LANDING = "landing";
    public static final String SOURCE_QUESTIONNAIRE = "questionnaire";
    public static final String VIA_OVERRIDE = "override";
    public static final String VIA_REGION = "region";
    public static final String VIA_DEFAULT = "default";

    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    public void countrySelected(String businessId, String countryCode, String source) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("countryCode", countryCode);
        metadata.put("source", source);
        publish(
                businessId,
                AuditEventTypes.ONBOARDING_COUNTRY_SELECTED,
                "business",
                businessId,
                metadata
        );
    }

    public void verticalSelected(String businessId, List<String> storeTypes) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("storeTypes", storeTypes == null ? List.of() : List.copyOf(storeTypes));
        publish(
                businessId,
                AuditEventTypes.ONBOARDING_VERTICAL_SELECTED,
                "business",
                businessId,
                metadata
        );
    }

    public void catalogResolved(String businessId, GlobalCatalog catalog, String via) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("businessId", businessId);
        metadata.put("catalogCode", catalog.getCode());
        metadata.put("regionCode", catalog.getRegionCode());
        metadata.put("via", via);
        publish(
                businessId,
                AuditEventTypes.CATALOG_RESOLVED,
                "global_catalog",
                catalog.getId(),
                metadata
        );
    }

    public void packAdopted(
            String businessId,
            String catalogCode,
            String packId,
            String storeKitId,
            List<String> storeTypes,
            int importedCount
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("catalogCode", catalogCode);
        metadata.put("packId", packId);
        metadata.put("storeKitId", storeKitId);
        metadata.put("storeTypes", storeTypes == null ? List.of() : List.copyOf(storeTypes));
        metadata.put("importedCount", importedCount);
        publish(
                businessId,
                AuditEventTypes.CATALOG_PACK_ADOPTED,
                "global_product_pack",
                packId,
                metadata
        );
    }

    public void regionChangedBySuperAdmin(
            String businessId,
            String actorId,
            Map<String, Object> oldState,
            Map<String, Object> newState,
            boolean hadCatalogOrSalesRisk
    ) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("hadCatalogOrSalesRisk", hadCatalogOrSalesRisk);
            auditEventPublisher.publish(auditEventBuilder
                    .builder(
                            AuditEventCategory.SYSTEM,
                            AuditEventTypes.BUSINESS_REGION_CHANGED,
                            hadCatalogOrSalesRisk ? AuditEventSeverity.WARN : AuditEventSeverity.INFO
                    )
                    .businessId(businessId)
                    .actor(actorId, AuditEventActorType.USER)
                    .target("business", businessId)
                    .source("super_admin_portal")
                    .oldState(oldState)
                    .newState(newState)
                    .metadata(metadata)
                    .reason(hadCatalogOrSalesRisk
                            ? "Super Admin changed region with existing products/sales (acknowledged)"
                            : "Super Admin changed region")
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to audit region change: {}", ex.toString());
        }
    }

    private void publish(
            String businessId,
            String eventType,
            String targetType,
            String targetId,
            Map<String, Object> metadata
    ) {
        try {
            auditEventPublisher.publish(auditEventBuilder
                    .builder(AuditEventCategory.SYSTEM, eventType, AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .actor(null, AuditEventActorType.SYSTEM)
                    .target(targetType, targetId)
                    .source("region_catalog_funnel")
                    .metadata(metadata)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to audit {}: {}", eventType, ex.toString());
        }
    }
}
