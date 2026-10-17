package zelisline.ub.integrations.easytax.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.easytax.api.dto.EasyTaxBranchSettingsRequest;
import zelisline.ub.integrations.easytax.api.dto.EasyTaxBranchSettingsResponse;
import zelisline.ub.integrations.easytax.domain.EasyTaxBranchSettings;
import zelisline.ub.integrations.easytax.repository.EasyTaxBranchSettingsRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

/** Per-branch KRA identity: the KRA branch id and the VSCU device serial. */
@Service
@RequiredArgsConstructor
public class EasyTaxBranchSettingsService {

    private final EasyTaxBranchSettingsRepository repository;
    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public EasyTaxBranchSettingsResponse get(String businessId, String branchId) {
        requireBranch(businessId, branchId);
        return toResponse(repository.findById(branchId)
                .orElseGet(() -> emptyRow(branchId)));
    }

    @Transactional
    public EasyTaxBranchSettingsResponse update(
            String businessId,
            String branchId,
            EasyTaxBranchSettingsRequest request
    ) {
        requireBranch(businessId, branchId);
        EasyTaxBranchSettings row = repository.findById(branchId)
                .orElseGet(() -> emptyRow(branchId));
        if (request.kraBranchId() != null) {
            row.setKraBranchId(trimToNull(request.kraBranchId()));
        }
        if (request.deviceSerial() != null) {
            row.setDeviceSerial(trimToNull(request.deviceSerial()));
        }
        repository.save(row);
        return toResponse(row);
    }

    @Transactional(readOnly = true)
    public boolean isBranchConfigured(String branchId) {
        return repository.findById(branchId)
                .map(EasyTaxBranchSettings::isConfigured)
                .orElse(false);
    }

    private void requireBranch(String businessId, String branchId) {
        boolean exists = branchId != null
                && branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId).isPresent();
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found");
        }
    }

    private static EasyTaxBranchSettings emptyRow(String branchId) {
        EasyTaxBranchSettings row = new EasyTaxBranchSettings();
        row.setBranchId(branchId);
        return row;
    }

    private static EasyTaxBranchSettingsResponse toResponse(EasyTaxBranchSettings row) {
        return new EasyTaxBranchSettingsResponse(
                row.getBranchId(),
                row.getKraBranchId(),
                row.getDeviceSerial(),
                row.isConfigured());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
