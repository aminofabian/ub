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
            Instant clientSoldAt,
            /** Use one session of this prepaid package toward the ticket. The ticket must name the customer. */
            @Size(max = 36) String packageId
    ) {
        public PayTicketRequest(
                List<PostSalePaymentRequest> payments, List<PostSaleTipRequest> tips,
                BigDecimal cashReceived, Instant clientSoldAt) {
            this(payments, tips, cashReceived, clientSoldAt, null);
        }
    }

    public record TicketLineResponse(
            String id,
            String serviceId,
            String name,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            /** Staff sharing the work on this line with the person serving, each with their share. */
            List<LineHelper> helpers,
            /** Who is doing, or did, this service. Null until someone is chosen. */
            String performerStaffProfileId,
            String performerName,
            /** The chair, bed or room it uses; optional. */
            String resourceId,
            String resourceName,
            /** {@code WAITING}, {@code IN_SERVICE} or {@code DONE}. */
            String stage,
            Instant startedAt,
            Instant finishedAt,
            /** Planned minutes, from the service. */
            int plannedMinutes,
            /** What the person leading it is paid for this service when set at the till; null means by their plan. */
            BigDecimal performerAmount
    ) {
    }

    public record TicketProductResponse(
            String id, String itemId, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    /** A product added to a visit. The price is today's shelf price, never the caller's. */
    public record AddProductRequest(
            @NotBlank @Size(max = 36) String itemId,
            @NotNull @DecimalMin("0.001") @Digits(integer = 6, fraction = 3) BigDecimal quantity
    ) {
    }

    /** Who does one service of a visit and where. Sent to assign, and to start. */
    public record LineAssignmentRequest(
            @Size(max = 36) String performerStaffProfileId,
            /** Null leaves the resource as it is; blank clears it. */
            @Size(max = 36) String resourceId
    ) {
    }

    public record EarningsShare(String staffProfileId, String staffName, BigDecimal commission, String rule) {
    }

    public record EarningsLine(
            String lineId, String name, BigDecimal price, List<EarningsShare> shares,
            /** The price less every commission on the line. */
            BigDecimal shopShare
    ) {
    }

    public record StaffEarning(String staffProfileId, String staffName, BigDecimal commission) {
    }

    /** What a visit would pay out if it were paid now. Tips are not in it: they are never revenue. */
    public record VisitEarningsResponse(
            String ticketId,
            BigDecimal serviceTotal,
            BigDecimal commissionTotal,
            BigDecimal shopShare,
            List<EarningsLine> lines,
            List<StaffEarning> staff
    ) {
    }

    public record LineHelper(
            @NotBlank @Size(max = 36) String staffProfileId,
            /** Their share of the line in percent; an equal split when left out. */
            @DecimalMin("1") @jakarta.validation.constraints.DecimalMax("99") BigDecimal sharePercent,
            /** What they are paid for this service, in whole shillings; null means by their plan. */
            @DecimalMin("0") @Digits(integer = 9, fraction = 0) BigDecimal amount,
            /**
             * A helper role the owner set beforehand. When given, the share and the amount come
             * from the role and anything sent for them is ignored. Never returned: the line keeps
             * the pay that was worked out, not the role.
             */
            @Size(max = 36) String roleId
    ) {
        public LineHelper(String staffProfileId, BigDecimal sharePercent) {
            this(staffProfileId, sharePercent, null, null);
        }

        public LineHelper(String staffProfileId, BigDecimal sharePercent, BigDecimal amount) {
            this(staffProfileId, sharePercent, amount, null);
        }
    }

    /**
     * Everyone working on one service, in one go. The first person leads it; the rest help. An
     * amount, where given, is what that person is paid for this service instead of their plan.
     */
    public record SetTeamRequest(@NotEmpty @Size(max = 6) @Valid List<LineHelper> members) {
    }

    /** Everyone sharing a line with the person serving. An empty list means they did it alone. */
    public record SetHelpersRequest(@NotNull @Size(max = 5) @Valid List<LineHelper> helpers) {
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
            boolean sendReminder,
            /** The prepaid package a session of which paid for this visit, if any. */
            String redeemedPackageId,
            /** Products the customer is buying on this visit, paid for with the services. */
            List<TicketProductResponse> products
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
            Boolean sendReminder,
            /** The chair, bed or room to hold for the booking; optional. */
            @Size(max = 36) String resourceId
    ) {
        public CreateAppointmentRequest(
                String branchId, String clientTicketId, String staffProfileId, Instant start,
                List<TicketLineInput> lines, String customerId, String name, String phone, Boolean sendReminder) {
            this(branchId, clientTicketId, staffProfileId, start, lines, customerId, name, phone, sendReminder, null);
        }
    }

    /** One block on the calendar: a booking, a service under way or done, time off, or a resource out of use. */
    public record CalendarEntry(
            /** {@code BOOKED}, {@code IN_SERVICE}, {@code DONE}, {@code NO_SHOW}, {@code TIME_OFF} or {@code BLOCKED}. */
            String kind,
            String ticketId,
            Long ticketNumber,
            String title,
            String customerName,
            String staffProfileId,
            String resourceId,
            Instant start,
            Instant end
    ) {
    }

    public record CalendarColumn(String id, String name) {
    }

    public record CalendarResponse(
            String branchId,
            Instant from,
            Instant to,
            List<CalendarColumn> staff,
            List<CalendarColumn> resources,
            List<CalendarEntry> entries
    ) {
    }

    public record TimeOffRequest(@NotNull Instant from, @NotNull Instant to, @Size(max = 40) String reason) {
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
