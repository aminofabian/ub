package zelisline.ub.posdraft.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import zelisline.ub.sales.api.dto.PostSalePaymentRequest;
import zelisline.ub.sales.api.dto.PostSaleTipRequest;
import zelisline.ub.sales.api.dto.SaleResponse;

/** Shapes for walk-in service tickets — the queue of a service business (ADR 0014). */
public final class ServiceTicketDtos {

    private ServiceTicketDtos() {
    }

    public record TicketLineInput(
            @NotBlank @Size(max = 36) String serviceId,
            /** Price for a variable-price service; ignored for a fixed-price one. */
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal unitPrice
    ) {
    }

    public record CreateServiceTicketRequest(
            @NotBlank @Size(max = 36) String branchId,
            /** Made up once by the client; sending it again returns the same ticket. */
            @NotBlank @Size(max = 64) String clientTicketId,
            @Size(max = 36) String customerId,
            /** The staff member the customer asked for; null means next available. */
            @Size(max = 36) String requestedStaffProfileId,
            @NotEmpty @Size(max = 20) @Valid List<TicketLineInput> lines,
            /** For a redo: the sale whose work is being put right. The ticket is then never charged. */
            @Size(max = 36) String redoOfSaleId
    ) {
        public CreateServiceTicketRequest(
                String branchId, String clientTicketId, String customerId, String requestedStaffProfileId,
                List<TicketLineInput> lines) {
            this(branchId, clientTicketId, customerId, requestedStaffProfileId, lines, null);
        }
    }

    public record StartTicketRequest(
            @NotBlank @Size(max = 36) String performerStaffProfileId,
            @NotNull @Min(1) @Max(50) Integer stationNo
    ) {
    }

    public record CancelTicketRequest(
            @Size(max = 500) String reason,
            /** True when the customer walked away before being served or paying. */
            Boolean abandoned
    ) {
    }

    public record PayTicketRequest(
            /** May be empty when a deposit covers the whole ticket. */
            @Valid List<PostSalePaymentRequest> payments,
            @Valid List<PostSaleTipRequest> tips,
            @DecimalMin("0") BigDecimal cashReceived,
            Instant clientSoldAt
    ) {
    }

    public record TicketLineResponse(
            String id,
            String serviceId,
            String name,
            BigDecimal unitPrice,
            BigDecimal lineTotal
    ) {
    }

    public record TicketResponse(
            String id,
            String branchId,
            long ticketNumber,
            /** {@code WAITING}, {@code IN_SERVICE}, {@code READY_TO_PAY}; closed tickets are not listed. */
            String stage,
            String status,
            Integer stationNo,
            String staffProfileId,
            String staffName,
            String customerId,
            BigDecimal total,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            String saleId,
            long version,
            List<TicketLineResponse> lines,
            /** Appointments: when the customer is booked for. Null for a walk-in. */
            Instant scheduledStart,
            Instant scheduledEnd,
            String bookingName,
            String bookingPhone,
            /** Higher is served sooner among waiting tickets. */
            int priority,
            /** Rough minutes until a waiting ticket is likely to start; null when it cannot be estimated. */
            Integer estimatedWaitMinutes,
            /** Money taken in advance; null when none. */
            BigDecimal depositAmount,
            /** {@code HELD}, {@code APPLIED}, {@code CREDITED} or {@code FORFEITED}. */
            String depositState,
            /** Set on a redo: the sale being put right. A redo is never charged. */
            String redoOfSaleId,
            boolean sendReminder
    ) {
    }

    public record TakeDepositRequest(
            @NotNull @DecimalMin("1") BigDecimal amount,
            /** {@code cash} or {@code mpesa_manual}. */
            @NotBlank @Size(max = 32) String method,
            @Size(max = 64) String reference
    ) {
    }

    public record NoShowRequest(
            /** True keeps a deposit as credit for the customer; otherwise the shop keeps it. */
            Boolean keepDepositAsCredit
    ) {
    }

    public record FloorStaffResponse(
            String staffProfileId,
            String displayName,
            /** {@code AVAILABLE}, {@code ON_BREAK} or {@code OFF}. */
            String floorStatus
    ) {
    }

    public record SetFloorStatusRequest(@NotBlank @Size(max = 16) String status) {
    }

    /** Above zero moves a customer up the queue; below zero sends them to the back (skipped). */
    public record SetPriorityRequest(@NotNull @Min(-9) @Max(9) Integer priority) {
    }

    public record CreateAppointmentRequest(
            @NotBlank @Size(max = 36) String branchId,
            /** Made up once by the client; sending it again returns the same appointment. */
            @NotBlank @Size(max = 64) String clientTicketId,
            @NotBlank @Size(max = 36) String staffProfileId,
            @NotNull Instant start,
            @NotEmpty @Size(max = 20) @Valid List<TicketLineInput> lines,
            @Size(max = 36) String customerId,
            @NotBlank @Size(max = 191) String name,
            @Size(max = 32) String phone,
            /** Text the customer shortly before the booking. Needs a phone number; uses SMS credits. */
            Boolean sendReminder
    ) {
    }

    public record RescheduleRequest(
            @NotNull Instant start,
            /** Move the booking to someone else; null keeps the same staff member. */
            @Size(max = 36) String staffProfileId
    ) {
    }

    public record ServiceBoardResponse(
            String branchId,
            /** Number of chairs at the branch (1..N). */
            int stationCount,
            List<TicketResponse> tickets,
            /** Staff who serve customers, with whether each can take the next one. */
            List<FloorStaffResponse> staff
    ) {
    }

    public record PaidTicketResponse(TicketResponse ticket, SaleResponse sale) {
    }
}
