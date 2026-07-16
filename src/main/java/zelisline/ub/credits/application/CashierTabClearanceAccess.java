package zelisline.ub.credits.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.application.BusinessInventorySettingsService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class CashierTabClearanceAccess {

    private final BusinessRepository businessRepository;
    private final BusinessInventorySettingsService businessInventorySettingsService;

    public boolean isEnabled(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return false;
        }
        return businessRepository.findById(businessId.trim())
                .map(Business::getSettings)
                .map(businessInventorySettingsService::readFromSettingsJson)
                .map(inv -> inv.creditTabs().allowCashierTabClearance())
                .orElse(false);
    }

    public void requireEnabled(String businessId) {
        if (!isEnabled(businessId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Cashier tab clearance is disabled. Ask an admin to enable it in Business settings."
            );
        }
    }
}
