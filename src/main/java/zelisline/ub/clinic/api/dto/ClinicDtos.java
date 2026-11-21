package zelisline.ub.clinic.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TicketLineResponse;
import zelisline.ub.sales.api.dto.PostSalePaymentRequest;
import zelisline.ub.sales.api.dto.SaleResponse;

/** Shapes for the Clinic section (ADR 0034). */
public final class ClinicDtos {

    private ClinicDtos() {
    }

    // ── Settings ──────────────────────────────────────────────────────

    public record SettingsRequest(
            @NotNull Boolean enabled,
            Boolean feeFirst,
            @Min(0) @Max(90) Integer freeReviewDays,
            @Size(max = 36) String consultationServiceId,
            @Size(max = 36) String reviewServiceId,
            @Size(max = 100) List<@NotBlank @Size(max = 36) String> performServiceIds,
            Boolean secondPersonForGated
    ) {
    }

    public record SettingsResponse(
            boolean enabled,
            boolean feeFirst,
            int freeReviewDays,
            String consultationServiceId,
            String reviewServiceId,
            List<String> performServiceIds,
            boolean secondPersonForGated,
            /** What the signed-in person may do in the clinic, so the screen follows their hats. */
            Hats hats
    ) {
    }

    public record Hats(
            boolean reception, boolean consult, boolean prescribe, boolean perform,
            boolean dispense, boolean cashier, boolean manage,
            /** The caller's own staff profile, when they have one: "my patients" filters on it. */
            String staffProfileId
    ) {
    }

    /** A service as the visit screen needs it. */
    public record ClinicService(
            String id, String name, BigDecimal price, boolean priceIsVariable, int durationMinutes,
            /** A test or procedure (the lab and treatment station) and not a consultation. */
            boolean perform,
            /** What it is called on the receipt; null when it is sold under its own name. */
            String receiptName
    ) {
    }

    public record ReceiptNameRequest(@Size(max = 191) String receiptName) {
    }

    /** A room or bed a service can be done in. */
    public record Room(String id, String name) {
    }

    /** Someone who can be booked or asked for. */
    public record Clinician(String staffProfileId, String name) {
    }

    // ── Patients ──────────────────────────────────────────────────────

    /** What reception sees of a patient: enough to pick the right person, nothing clinical. */
    public record ReceptionPatient(String id, Long patientNo, String name, String phone, Integer ageYears) {
    }

    public record NewPatientRequest(
            @NotBlank @Size(max = 191) String name,
            @Size(max = 32) String phone,
            LocalDate dateOfBirth
    ) {
    }

    // ── Visits ────────────────────────────────────────────────────────

    public record OpenVisitRequest(
            @NotBlank @Size(max = 36) String branchId,
            /** Made up once by the client; sending it again returns the same visit. */
            @NotBlank @Size(max = 64) String clientVisitId,
            @NotBlank @Size(max = 36) String patientId,
            /** What they came for; empty means the clinic's consultation. Ignored for a review visit. */
            @Size(max = 10) List<@NotBlank @Size(max = 36) String> serviceIds,
            /** The clinician asked for; null means whoever is free. */
            @Size(max = 36) String requestedStaffProfileId,
            /** Open as a free review of the patient's last paid visit. */
            Boolean review,
            /** Who pays, when it is not the patient's usual payer. */
            @Size(max = 36) String payerCustomerId,
            /** Seen before the others (an emergency). */
            Boolean urgent
    ) {
    }

    public record AddServiceRequest(@NotBlank @Size(max = 36) String serviceId) {
    }

    public record LineRequest(
            /** Who is doing it; null means the signed-in person. */
            @Size(max = 36) String performerStaffProfileId,
            @Size(max = 36) String resourceId
    ) {
    }

    public record MedicineGivenRequest(
            @NotBlank @Size(max = 36) String itemId,
            @NotNull @DecimalMin("0.001") @Digits(integer = 6, fraction = 3) BigDecimal quantity
    ) {
    }

    public record AddProductRequest(
            @NotBlank @Size(max = 36) String itemId,
            @NotNull @DecimalMin("0.001") @Digits(integer = 6, fraction = 3) BigDecimal quantity
    ) {
    }

    public record DepositRequest(
            @NotNull @DecimalMin("1") BigDecimal amount,
            @NotBlank @Size(max = 32) String method,
            @Size(max = 64) String reference
    ) {
    }

    public record CancelVisitRequest(
            @Size(max = 500) String reason,
            Boolean leftWithoutBeingSeen,
            @Size(max = 32) String depositBack
    ) {
    }

    public record PayVisitRequest(
            @Valid List<PostSalePaymentRequest> payments,
            @DecimalMin("0") BigDecimal cashReceived,
            Instant clientSoldAt,
            /** A pharmacist's approval, when the person taking payment may not dispense (ADR 0033). */
            @Size(max = 36) String approvalId
    ) {
    }

    public record VisitPatient(
            String id, Long patientNo, String name, String phone, Integer ageYears,
            /** Only for people working the visit clinically or dispensing; null for reception. */
            String allergies
    ) {
    }

    public record VisitProduct(
            String id, String itemId, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal,
            boolean givenHere,
            /** Null for an ordinary product; otherwise the medicine's dispensing class. */
            String dispensingClass
    ) {
    }

    public record PrescriptionLineDto(
            String id, String itemId, String medicineName, BigDecimal quantity, String instructions,
            /** Null when not a stocked product; otherwise its dispensing class, or UNCLASSIFIED. */
            String dispensingClass
    ) {
    }

    public record PrescriptionDto(
            String id, String status, String prescriberName, String prescriberRegNo, LocalDate prescribedOn,
            String preparedByName, Instant preparedAt, List<PrescriptionLineDto> lines
    ) {
    }

    public record PrescriptionLineInput(
            @Size(max = 36) String itemId,
            @NotBlank @Size(max = 255) String medicineName,
            @NotNull @DecimalMin("0.001") @Digits(integer = 8, fraction = 3) BigDecimal quantity,
            @Size(max = 500) String instructions
    ) {
    }

    public record SavePrescriptionRequest(@NotNull @Size(max = 30) @Valid List<PrescriptionLineInput> lines) {
    }

    public record PrepareLine(
            @NotBlank @Size(max = 36) String lineId,
            /** How much is being supplied now; 0 leaves the line out. */
            @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 3) BigDecimal quantity
    ) {
    }

    public record PrepareRequest(@NotNull @Size(max = 30) @Valid List<PrepareLine> lines) {
    }

    public record VisitResponse(
            String id,
            String branchId,
            long number,
            /** booked, waiting, consult, lab, dispensary, pay, done, cancelled. */
            String station,
            String status,
            int priority,
            VisitPatient patient,
            String payerCustomerId,
            String requestedStaffProfileId,
            String requestedStaffName,
            List<TicketLineResponse> lines,
            List<VisitProduct> products,
            BigDecimal total,
            BigDecimal depositAmount,
            String depositState,
            boolean review,
            /** Null when the caller may not read prescriptions, or there is none. */
            PrescriptionDto prescription,
            /** Whether there is one at all, for people who may not read it. */
            String prescriptionStatus,
            Instant openedAt,
            Instant scheduledStart,
            String saleId,
            long version,
            /**
             * True when every prescription-only medicine on the bill was prepared by someone who
             * may dispense, so the cash desk is not asked for a pharmacist's PIN.
             */
            boolean dispenserCleared,
            /** The receipt of a paid visit, for finding its sale (to reprint or refund a line). */
            Long receiptNo
    ) {
    }

    /** One card on the Today list. Never says what the person came for. */
    public record BoardVisit(
            String id,
            long number,
            String firstName,
            String station,
            int priority,
            Instant openedAt,
            /** When the step they are in began, for "waiting 14 min". */
            Instant since,
            String clinicianStaffProfileId,
            String clinicianName,
            String roomName,
            int linesDone,
            int linesTotal,
            BigDecimal total,
            BigDecimal depositAmount,
            String depositState,
            String prescriptionStatus,
            boolean review
    ) {
    }

    public record BoardResponse(String branchId, List<BoardVisit> visits, int finishedToday) {
    }

    public record PaidVisitResponse(VisitResponse visit, SaleResponse sale) {
    }

    // ── Takings ───────────────────────────────────────────────────────

    public record TakingsRow(
            String serviceId, String serviceName, String staffProfileId, String staffName, long times, BigDecimal total) {
    }

    /** What the clinic billed in a period. No patient appears in it. */
    public record TakingsResponse(
            Instant from, Instant to, long visits, BigDecimal services, BigDecimal products, List<TakingsRow> rows) {
    }

    // ── Appointments ──────────────────────────────────────────────────

    public record BookRequest(
            @NotBlank @Size(max = 36) String branchId,
            @NotBlank @Size(max = 64) String clientVisitId,
            @NotBlank @Size(max = 36) String patientId,
            @NotBlank @Size(max = 36) String staffProfileId,
            @NotNull Instant start,
            @Size(max = 10) List<@NotBlank @Size(max = 36) String> serviceIds,
            Boolean sendReminder
    ) {
    }

    public record Appointment(
            String id, Instant start, Instant end, String patientName, Long patientNo,
            String staffProfileId, String staffName, boolean reminder
    ) {
    }

    // ── Scheme claims ─────────────────────────────────────────────────

    public record VisitClaimRequest(
            @NotBlank @Size(max = 36) String schemeId,
            @NotBlank @Size(max = 64) String memberNo,
            @Size(max = 64) String authorisationCode,
            @NotNull @DecimalMin("0.01") BigDecimal amountClaimed
    ) {
    }
}
