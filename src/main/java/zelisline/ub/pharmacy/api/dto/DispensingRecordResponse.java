package zelisline.ub.pharmacy.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DispensingRecordResponse(
        String id,
        String saleId,
        Long receiptNo,
        String branchId,
        Instant createdAt,
        String dispensedByUserId,
        String dispensedByName,
        String dispensedByTitle,
        String dispensedByRegistrationNo,
        String soldByName,
        boolean approvedByPin,
        String prescriberName,
        String prescriberRegNo,
        String prescriptionRef,
        LocalDate prescriptionDate,
        String patientName,
        String patientPhone,
        Instant voidedAt,
        List<Line> lines,
        String patientId,
        /** Who did the second check, when one was done. */
        String verifiedByName,
        Instant verifiedAt,
        List<Correction> corrections
) {

    /** A note added later saying what on the record was written wrongly. */
    public record Correction(String id, String note, String byName, Instant createdAt) {
    }

    public record Line(
            String itemId,
            String itemName,
            String dispensingClass,
            BigDecimal quantity,
            String instructions,
            String lotNumbers,
            String id,
            /** Set when the prescription asked for more than this line supplied. */
            BigDecimal quantityPrescribed,
            LocalDate refillDueOn,
            String settlesLineId
    ) {
    }
}
