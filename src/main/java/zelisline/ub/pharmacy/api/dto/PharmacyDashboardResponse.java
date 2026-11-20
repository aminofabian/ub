package zelisline.ub.pharmacy.api.dto;

import java.time.LocalDate;
import java.util.List;

import zelisline.ub.inventory.api.dto.BatchSafetyCountsResponse;

/** Everything the pharmacy home screen shows, in one call. Counts only: no patient or sale detail. */
public record PharmacyDashboardResponse(
        MedicineClassCountsResponse medicines,
        BatchSafetyCountsResponse stock,
        long dispensedToday,
        String premisesLicenceNo,
        LocalDate licenceExpiresOn,
        Long licenceDaysLeft,
        boolean controlledSalesEnabled,
        int dispensingStaff,
        int dispensingStaffWithoutPin,
        List<ChecklistStep> checklist,
        /**
         * Records from the last 30 days still waiting for a second check. Null when the shop does
         * not use a second check.
         */
        Long awaitingSecondCheck
) {

    public record ChecklistStep(String key, String label, boolean done, String hint) {
    }
}
