package zelisline.ub.till.application;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.till.api.dto.RegisterTillDeviceRequest;
import zelisline.ub.till.api.dto.TillDeviceListResponse;
import zelisline.ub.till.api.dto.TillDeviceResponse;
import zelisline.ub.till.domain.TillDevice;
import zelisline.ub.till.repository.TillDeviceRepository;

@Service
@RequiredArgsConstructor
public class TillDeviceService {

    public static final String TILL_DEVICE_HEADER = "X-Till-Device-Id";

    /**
     * RFC 9457 detail when PIN login is blocked because the branch has registered
     * tills and this browser is not among them.
     */
    public static final String TILL_DEVICE_NOT_REGISTERED_DETAIL =
            "This till is not registered for this branch. Ask a manager to register it under Business Settings → Trusted tills.";

    private static final Pattern DEVICE_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    private final TillDeviceRepository tillDeviceRepository;
    private final BranchRepository branchRepository;

    @Transactional
    public TillDeviceResponse register(
            String businessId,
            String userId,
            String branchId,
            RegisterTillDeviceRequest request,
            String headerDeviceKey
    ) {
        String deviceKey = resolveDeviceKey(request.deviceKey(), headerDeviceKey);
        String label = resolveLabel(request.label(), deviceKey);

        branchRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));

        TillDevice row = tillDeviceRepository
                .findByBusinessIdAndBranchIdAndDeviceKey(businessId, branchId, deviceKey)
                .orElseGet(TillDevice::new);

        boolean wasRevoked = row.getRevokedAt() != null;
        boolean isNew = row.getId() == null || row.getId().isBlank();

        row.setBusinessId(businessId);
        row.setBranchId(branchId);
        row.setDeviceKey(deviceKey);
        row.setLabel(label);
        row.setRegisteredBy(userId);
        if (isNew || wasRevoked) {
            row.setRegisteredAt(Instant.now());
        }
        row.setRevokedAt(null);
        row.setUpdatedAt(Instant.now());

        return toResponse(tillDeviceRepository.save(row));
    }

    @Transactional(readOnly = true)
    public TillDeviceListResponse list(
            String businessId,
            String branchId,
            boolean includeRevoked
    ) {
        branchRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));

        List<TillDevice> rows = includeRevoked
                ? tillDeviceRepository.findByBusinessIdAndBranchIdOrderByRegisteredAtDesc(
                        businessId, branchId)
                : tillDeviceRepository.findByBusinessIdAndBranchIdAndRevokedAtIsNullOrderByRegisteredAtDesc(
                        businessId, branchId);

        return new TillDeviceListResponse(rows.stream().map(this::toResponse).toList());
    }

    @Transactional
    public void revoke(String businessId, String id) {
        int updated = tillDeviceRepository.revoke(id.trim(), businessId, Instant.now());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Till device not found");
        }
    }

    /**
     * Hybrid trusted-till policy for PIN login:
     * <ul>
     *   <li>If the branch has <em>no</em> active registered tills → allow (gradual rollout).</li>
     *   <li>If it has one or more → require {@code deviceKey} to match an active row.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public void assertPinLoginAllowed(String businessId, String branchId, String deviceKey) {
        if (!tillDeviceRepository.existsByBusinessIdAndBranchIdAndRevokedAtIsNull(businessId, branchId)) {
            return;
        }
        String key = deviceKey != null ? deviceKey.trim() : "";
        if (key.isEmpty()
                || !tillDeviceRepository.existsByBusinessIdAndBranchIdAndDeviceKeyAndRevokedAtIsNull(
                        businessId, branchId, key)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, TILL_DEVICE_NOT_REGISTERED_DETAIL);
        }
    }

    static String resolveDeviceKey(String bodyKey, String headerKey) {
        String fromBody = bodyKey != null ? bodyKey.trim() : "";
        String fromHeader = headerKey != null ? headerKey.trim() : "";
        String key = !fromBody.isEmpty() ? fromBody : fromHeader;
        if (key.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "deviceKey required (body or " + TILL_DEVICE_HEADER + ")"
            );
        }
        if (!DEVICE_KEY_PATTERN.matcher(key).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "deviceKey must be 8–64 characters (letters, digits, ._-)"
            );
        }
        return key;
    }

    static String resolveLabel(String rawLabel, String deviceKey) {
        String label = rawLabel != null ? rawLabel.trim() : "";
        if (!label.isEmpty()) {
            return label.length() > 80 ? label.substring(0, 80) : label;
        }
        String compact = deviceKey.replace("-", "");
        String shortId = compact.length() > 8 ? compact.substring(0, 8) : compact;
        return "Till " + shortId.toLowerCase(Locale.ROOT);
    }

    private TillDeviceResponse toResponse(TillDevice entity) {
        return new TillDeviceResponse(
                entity.getId(),
                entity.getBranchId(),
                entity.getDeviceKey(),
                entity.getLabel(),
                entity.getRegisteredBy(),
                entity.getRegisteredAt(),
                entity.getRevokedAt()
        );
    }
}
