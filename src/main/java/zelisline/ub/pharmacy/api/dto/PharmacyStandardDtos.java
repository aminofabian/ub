package zelisline.ub.pharmacy.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response shapes for the Phase 2 pharmacy endpoints (ADR 0033). */
public final class PharmacyStandardDtos {

    private PharmacyStandardDtos() {
    }

    // ── Patients ──────────────────────────────────────────────────────

    public record PatientRequest(
            @NotBlank @Size(max = 191) String name,
            @Size(max = 32) String phone,
            LocalDate dateOfBirth,
            @Size(max = 500) String allergies,
            @Size(max = 1000) String notes,
            Boolean reminderConsent
    ) {
    }

    public record PatientResponse(
            String id,
            String name,
            String phone,
            LocalDate dateOfBirth,
            String allergies,
            String notes,
            boolean reminderConsent,
            Instant reminderConsentAt,
            Instant createdAt
    ) {
    }

    /** What the till may see when it looks a patient up by phone: enough to pick the right person. */
    public record PatientMatch(String id, String name, String phone, String allergies) {
    }

    public record PatientPage(List<PatientResponse> patients, long total, int page, int size) {
    }

    public record PatientHistory(PatientResponse patient, List<DispensingRecordResponse> records) {
    }

    // ── Follow-ups ────────────────────────────────────────────────────

    public record Owing(
            String lineId,
            String recordId,
            String saleId,
            String branchId,
            Instant dispensedAt,
            String itemId,
            String itemName,
            BigDecimal quantityPrescribed,
            BigDecimal quantitySupplied,
            BigDecimal quantityOutstanding,
            String patientId,
            String patientName,
            String patientPhone,
            String prescriberName,
            String prescriptionRef
    ) {
    }

    public record RefillDue(
            String lineId,
            String recordId,
            String branchId,
            LocalDate dueOn,
            String itemId,
            String itemName,
            String patientId,
            String patientName,
            String patientPhone,
            boolean reminderConsent
    ) {
    }

    // ── Controlled register ───────────────────────────────────────────

    public record ControlledItem(String itemId, String name, String strength, String dosageForm, BigDecimal onRecord) {
    }

    public record RegisterEntry(
            Instant at,
            String movementType,
            BigDecimal quantityIn,
            BigDecimal quantityOut,
            BigDecimal balance,
            String lotNumber,
            String byName,
            String prescriberName,
            String patientName,
            String dispensedByName,
            String note
    ) {
    }

    public record ControlledRegister(
            String itemId,
            String name,
            LocalDate from,
            LocalDate to,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            boolean truncated,
            List<RegisterEntry> entries
    ) {
    }

    // ── Temperature log ───────────────────────────────────────────────

    public record TemperatureRequest(
            @NotBlank @Size(max = 36) String branchId,
            @NotBlank @Size(max = 64) String storageName,
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal readingC,
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal minC,
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal maxC,
            @Size(max = 255) String actionNote
    ) {
    }

    public record TemperatureResponse(
            String id,
            String branchId,
            String storageName,
            BigDecimal readingC,
            BigDecimal minC,
            BigDecimal maxC,
            boolean outOfRange,
            String actionNote,
            String recordedByName,
            Instant recordedAt
    ) {
    }

    // ── Supplier licences ─────────────────────────────────────────────

    public record SupplierLicenceRequest(
            @Size(max = 64) String licenceNo,
            LocalDate expiresOn,
            @Size(max = 255) String note
    ) {
    }

    public record SupplierLicenceResponse(
            String supplierId,
            String supplierName,
            String licenceNo,
            LocalDate expiresOn,
            /** Days until the licence expires; negative when it already has. Null when no date is recorded. */
            Long daysLeft,
            String note
    ) {
    }

    // ── Adverse events ────────────────────────────────────────────────

    public record AdverseEventRequest(
            @NotNull LocalDate occurredOn,
            @Size(max = 36) String itemId,
            @NotBlank @Size(max = 255) String productName,
            @Size(max = 64) String lotNumber,
            @Size(max = 36) String patientId,
            @NotBlank @Size(max = 2000) String description,
            @NotBlank @Pattern(regexp = "MILD|MODERATE|SEVERE") String severity,
            @Size(max = 500) String actionTaken
    ) {
    }

    public record AdverseEventReportedRequest(@Size(max = 64) String externalRef) {
    }

    public record AdverseEventResponse(
            String id,
            LocalDate occurredOn,
            String itemId,
            String productName,
            String lotNumber,
            String patientId,
            String patientName,
            String description,
            String severity,
            String actionTaken,
            boolean reportedExternally,
            String externalRef,
            Instant reportedAt,
            String recordedByName,
            Instant createdAt
    ) {
    }
}
