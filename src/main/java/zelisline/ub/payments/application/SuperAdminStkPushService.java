package zelisline.ub.payments.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.GatewayStkPushOpsResponse;
import zelisline.ub.payments.domain.GatewayStkPush;
import zelisline.ub.payments.domain.GatewayStkPushStatuses;
import zelisline.ub.payments.repository.GatewayStkPushRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class SuperAdminStkPushService {

    private final GatewayStkPushRepository pushRepository;
    private final BusinessRepository businessRepository;

    @Transactional(readOnly = true)
    public List<GatewayStkPushOpsResponse> listForSuperAdmin(String status, int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);
        var pageable = PageRequest.of(0, capped, Sort.by(Sort.Direction.DESC, "createdAt"));

        List<GatewayStkPush> rows;
        String normalized = normalizeStatus(status);
        if (normalized != null) {
            rows = pushRepository.findByStatus(normalized, pageable);
        } else {
            rows = pushRepository.findAll(pageable).getContent();
        }

        Set<String> businessIds = new HashSet<>();
        for (GatewayStkPush row : rows) {
            businessIds.add(row.getBusinessId());
        }
        Map<String, BusinessLite> businesses = loadBusinesses(businessIds);

        return rows.stream()
                .map(row -> toResponse(row, businesses.get(row.getBusinessId())))
                .toList();
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String s = status.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case GatewayStkPushStatuses.PENDING,
                 GatewayStkPushStatuses.SUCCESS,
                 GatewayStkPushStatuses.FAILED -> s;
            default -> null;
        };
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
                    b.getSlug()
            ));
        }
        return out;
    }

    private static GatewayStkPushOpsResponse toResponse(GatewayStkPush row, BusinessLite biz) {
        return new GatewayStkPushOpsResponse(
                row.getId(),
                row.getBusinessId(),
                biz != null ? biz.name() : null,
                biz != null ? biz.slug() : null,
                row.getGatewayType() != null ? row.getGatewayType().name() : null,
                row.getGatewayCheckoutId(),
                row.getMerchantReference(),
                row.getContextType() != null ? row.getContextType().name() : null,
                row.getContextId(),
                row.getAmount(),
                row.getPhoneNumber(),
                row.getStatus(),
                row.getGatewayTransactionId(),
                row.getFailureReason(),
                row.getConfirmedAt(),
                row.getCreatedAt(),
                row.getUpdatedAt()
        );
    }

    private record BusinessLite(String name, String slug) {
    }
}
