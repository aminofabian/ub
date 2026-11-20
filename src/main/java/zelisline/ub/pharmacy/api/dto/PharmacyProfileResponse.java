package zelisline.ub.pharmacy.api.dto;

import java.time.LocalDate;
import java.util.List;

public record PharmacyProfileResponse(
        String premisesLicenceNo,
        LocalDate licenceExpiresOn,
        /** Days until the licence expires; negative when it already has. Null when no date is set. */
        Long licenceDaysLeft,
        String superintendentUserId,
        String superintendentName,
        boolean controlledSalesEnabled,
        int shortDatedDays,
        List<Staff> staff,
        /** A second person who may dispense checks each dispensing record. */
        boolean secondCheckEnabled
) {

    /** Staff who may dispense, with the credentials the shop recorded for them. */
    public record Staff(String userId, String name, String title, String registrationNo, boolean hasPin) {
    }
}
