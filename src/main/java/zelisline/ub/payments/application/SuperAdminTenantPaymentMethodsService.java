package zelisline.ub.payments.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.TenantPaymentMethodsOverviewResponse;
import zelisline.ub.payments.api.dto.TenantPaymentMethodsOverviewResponse.TenantPaymentMethodRow;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class SuperAdminTenantPaymentMethodsService {

    private final PaymentGatewayConfigRepository configRepository;
    private final BusinessRepository businessRepository;
    private final ObjectMapper objectMapper;
    private final org.springframework.beans.factory.ObjectProvider<PlatformCustodySettlementService>
            custodySettlementService;

    @Transactional(readOnly = true)
    public TenantPaymentMethodsOverviewResponse overview() {
        List<PaymentGatewayConfig> configs = configRepository.findAll();
        configs.sort(Comparator
                .comparing(PaymentGatewayConfig::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));

        Set<String> businessIds = configs.stream()
                .map(PaymentGatewayConfig::getBusinessId)
                .collect(Collectors.toCollection(HashSet::new));

        Map<String, BusinessLite> businesses = loadBusinesses(businessIds);
        String custodyProvider = resolveCustodyProvider();

        List<TenantPaymentMethodRow> rows = new ArrayList<>(configs.size());
        Set<String> withAny = new HashSet<>();
        Set<String> withCustody = new HashSet<>();
        Set<String> withByo = new HashSet<>();
        Set<String> withManual = new HashSet<>();
        int activeConfigs = 0;
        int inactiveConfigs = 0;

        for (PaymentGatewayConfig cfg : configs) {
            GatewayType type = cfg.getGatewayType();
            if (cfg.getStatus() == GatewayStatus.ACTIVE) {
                activeConfigs++;
            } else {
                inactiveConfigs++;
            }
            withAny.add(cfg.getBusinessId());
            if (type == GatewayType.CUSTODY_MPESA) {
                withCustody.add(cfg.getBusinessId());
            } else if (type == GatewayType.MANUAL) {
                withManual.add(cfg.getBusinessId());
            } else {
                withByo.add(cfg.getBusinessId());
            }

            BusinessLite biz = businesses.get(cfg.getBusinessId());
            Destination dest = parseDestination(cfg.getDisplayInstructionsJson(), type);
            rows.add(new TenantPaymentMethodRow(
                    cfg.getId(),
                    cfg.getBusinessId(),
                    biz != null ? biz.name() : null,
                    biz != null ? biz.slug() : null,
                    biz != null && biz.active(),
                    type.name(),
                    cfg.getLabel(),
                    cfg.getStatus().name(),
                    cfg.isDefault(),
                    dest.type(),
                    dest.tillNumber(),
                    dest.businessNumber(),
                    dest.accountNumber(),
                    dest.summary(cfg.getLabel()),
                    type == GatewayType.CUSTODY_MPESA ? custodyProvider : null,
                    cfg.getUpdatedAt()
            ));
        }

        int totalBusinesses = (int) businessRepository.countByDeletedAtIsNull();
        int activeBusinesses = (int) businessRepository.countByDeletedAtIsNullAndActiveTrue();
        int tenantsWithAny = withAny.size();

        return new TenantPaymentMethodsOverviewResponse(
                totalBusinesses,
                activeBusinesses,
                tenantsWithAny,
                withCustody.size(),
                withByo.size(),
                withManual.size(),
                Math.max(0, totalBusinesses - tenantsWithAny),
                activeConfigs,
                inactiveConfigs,
                custodyProvider,
                rows
        );
    }

    private String resolveCustodyProvider() {
        PlatformCustodySettlementService custody = custodySettlementService.getIfAvailable();
        return custody != null ? custody.activeProvider() : "OFF";
    }

    private Map<String, BusinessLite> loadBusinesses(Set<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, BusinessLite> out = new HashMap<>();
        for (var b : businessRepository.findAllById(ids)) {
            if (b.getDeletedAt() != null) {
                continue;
            }
            out.put(b.getId(), new BusinessLite(
                    b.getName() == null ? "" : b.getName(),
                    b.getSlug(),
                    b.isActive()
            ));
        }
        return out;
    }

    private Destination parseDestination(String json, GatewayType type) {
        if (json == null || json.isBlank() || !type.isCredentialLess()) {
            return Destination.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String destType = text(root, "type");
            String till = digits(text(root, "tillNumber"));
            String businessNumber = digits(text(root, "businessNumber"));
            String account = text(root, "accountNumber");
            String label = text(root, "label");
            if ("till".equalsIgnoreCase(destType) || (till != null && !till.isBlank())) {
                return new Destination("till", till, null, null, label);
            }
            if ("paybill".equalsIgnoreCase(destType)
                    || (businessNumber != null && !businessNumber.isBlank())) {
                return new Destination("paybill", null, businessNumber, account, label);
            }
            return Destination.empty();
        } catch (Exception e) {
            return Destination.empty();
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String v = n.asText();
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String digits(String raw) {
        if (raw == null) {
            return null;
        }
        String d = raw.replaceAll("\\D", "");
        return d.isBlank() ? null : d;
    }

    private record BusinessLite(String name, String slug, boolean active) {
    }

    private record Destination(
            String type,
            String tillNumber,
            String businessNumber,
            String accountNumber,
            String label
    ) {
        static Destination empty() {
            return new Destination(null, null, null, null, null);
        }

        String summary(String configLabel) {
            if ("till".equals(type) && tillNumber != null) {
                return "Till " + tillNumber;
            }
            if ("paybill".equals(type) && businessNumber != null) {
                String base = "Paybill " + businessNumber;
                if (accountNumber != null && !accountNumber.isBlank()) {
                    base = base + " · Acc " + accountNumber;
                }
                if (label != null && !label.isBlank()
                        && !label.equalsIgnoreCase(configLabel)
                        && label.toLowerCase(Locale.ROOT).contains("bank")) {
                    return label.trim();
                }
                if (configLabel != null && !configLabel.isBlank()
                        && configLabel.toLowerCase(Locale.ROOT).contains("bank")) {
                    return configLabel.trim()
                            + (accountNumber != null ? " · Acc " + accountNumber : "");
                }
                return base;
            }
            return null;
        }
    }
}
