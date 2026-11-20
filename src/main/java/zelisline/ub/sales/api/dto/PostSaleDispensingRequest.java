package zelisline.ub.sales.api.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Dispensing details for a sale that contains prescription-only or controlled medicines
 * (ADR 0033). They travel inside the sale request so a sale queued offline replays complete.
 *
 * <p>{@code approvalId} is set when the signed-in cashier may not dispense and a pharmacist
 * approved the sale with their PIN; the PIN itself is never part of the sale request.</p>
 */
public record PostSaleDispensingRequest(
        @NotBlank @Size(max = 191) String prescriberName,
        @Size(max = 64) String prescriberRegNo,
        @Size(max = 64) String prescriptionRef,
        LocalDate prescriptionDate,
        @Size(max = 191) String patientName,
        @Size(max = 32) String patientPhone,
        @Size(max = 36) String approvalId,
        @Valid @Size(max = 100) List<Line> lines,
        /** A patient profile to file this record under (Phase 2). Optional. */
        @Size(max = 36) String patientId
) {

    public PostSaleDispensingRequest(
            String prescriberName, String prescriberRegNo, String prescriptionRef, LocalDate prescriptionDate,
            String patientName, String patientPhone, String approvalId, List<Line> lines) {
        this(prescriberName, prescriberRegNo, prescriptionRef, prescriptionDate, patientName, patientPhone,
                approvalId, lines, null);
    }

    /**
     * Optional per-medicine details: label text ("1 tablet twice a day for 5 days"); what the
     * prescription asked for when less is being supplied now; when the supply runs out; and the
     * earlier record line whose outstanding balance this sale supplies.
     */
    public record Line(
            @NotBlank @Size(max = 36) String itemId,
            @Size(max = 500) String instructions,
            @jakarta.validation.constraints.Positive java.math.BigDecimal quantityPrescribed,
            LocalDate refillDueOn,
            @Size(max = 36) String settlesLineId
    ) {
        public Line(String itemId, String instructions) {
            this(itemId, instructions, null, null, null);
        }
    }
}
