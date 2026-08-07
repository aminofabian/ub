package zelisline.ub.sales.application;

import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.till.application.TillDeviceService;
import zelisline.ub.till.repository.TillDeviceRepository;

/**
 * Resolves the open cash-drawer shift for a branch register.
 *
 * <p>When {@code X-Till-Device-Id} is present, uniqueness and lookups are per
 * {@code (business, branch, tillDeviceKey)}. Requests without a till key use the
 * legacy shared branch shift ({@code till_device_key IS NULL}). Lookups with a till
 * key fall back to that shared shift so in-flight legacy opens keep working.
 */
@Service
@RequiredArgsConstructor
public class OpenShiftResolver {

    private static final Pattern DEVICE_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    private final ShiftRepository shiftRepository;
    private final TillDeviceRepository tillDeviceRepository;

    /**
     * Normalizes a header/body till key. Returns {@code null} when blank.
     * Invalid non-blank keys are rejected.
     */
    public String normalizeTillDeviceKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim();
        if (!DEVICE_KEY_PATTERN.matcher(key).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid " + TillDeviceService.TILL_DEVICE_HEADER
                            + " (must be 8–64 characters: letters, digits, ._-)");
        }
        return key;
    }

    /**
     * Till key to store on a newly opened shift. When the branch has registered
     * trusted tills, the device must be among them.
     */
    public String resolveTillKeyForOpen(String businessId, String branchId, String headerKey) {
        String key = normalizeTillDeviceKey(headerKey);
        if (tillDeviceRepository.existsByBusinessIdAndBranchIdAndRevokedAtIsNull(businessId, branchId)) {
            if (key == null
                    || !tillDeviceRepository.existsByBusinessIdAndBranchIdAndDeviceKeyAndRevokedAtIsNull(
                            businessId, branchId, key)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, TillDeviceService.TILL_DEVICE_NOT_REGISTERED_DETAIL);
            }
        }
        return key;
    }

    public Optional<Shift> findOpen(String businessId, String branchId, String tillDeviceKey) {
        String key = normalizeTillDeviceKey(tillDeviceKey);
        if (key != null) {
            Optional<Shift> forTill = shiftRepository.findByBusinessIdAndBranchIdAndTillDeviceKeyAndStatus(
                    businessId, branchId, key, SalesConstants.SHIFT_STATUS_OPEN);
            if (forTill.isPresent()) {
                return forTill;
            }
            return shiftRepository.findByBusinessIdAndBranchIdAndTillDeviceKeyIsNullAndStatus(
                    businessId, branchId, SalesConstants.SHIFT_STATUS_OPEN);
        }
        return shiftRepository.findByBusinessIdAndBranchIdAndTillDeviceKeyIsNullAndStatus(
                businessId, branchId, SalesConstants.SHIFT_STATUS_OPEN);
    }

    public Optional<Shift> findOpenForUpdate(String businessId, String branchId, String tillDeviceKey) {
        String key = normalizeTillDeviceKey(tillDeviceKey);
        if (key != null) {
            Optional<Shift> forTill = shiftRepository.findByBusinessIdAndBranchIdAndTillDeviceKeyAndStatusForUpdate(
                    businessId, branchId, key, SalesConstants.SHIFT_STATUS_OPEN);
            if (forTill.isPresent()) {
                return forTill;
            }
            return shiftRepository.findByBusinessIdAndBranchIdAndTillDeviceKeyIsNullAndStatusForUpdate(
                    businessId, branchId, SalesConstants.SHIFT_STATUS_OPEN);
        }
        return shiftRepository.findByBusinessIdAndBranchIdAndTillDeviceKeyIsNullAndStatusForUpdate(
                businessId, branchId, SalesConstants.SHIFT_STATUS_OPEN);
    }

    public boolean hasOpenConflict(String businessId, String branchId, String tillDeviceKey) {
        String key = normalizeTillDeviceKey(tillDeviceKey);
        if (key != null) {
            return shiftRepository
                    .findByBusinessIdAndBranchIdAndTillDeviceKeyAndStatus(
                            businessId, branchId, key, SalesConstants.SHIFT_STATUS_OPEN)
                    .isPresent();
        }
        return shiftRepository
                .findByBusinessIdAndBranchIdAndTillDeviceKeyIsNullAndStatus(
                        businessId, branchId, SalesConstants.SHIFT_STATUS_OPEN)
                .isPresent();
    }

    public static String conflictMessage(String tillDeviceKey) {
        return tillDeviceKey != null && !tillDeviceKey.isBlank()
                ? "Shift already open for this till"
                : "Shift already open for branch";
    }
}
