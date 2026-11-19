package zelisline.ub.restaurant.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import zelisline.ub.restaurant.api.dto.OrderDtos.OrderCard;

/** Lodging rooms and the front desk (ADR 0028). A room is a service_resource of type ROOM. */
public final class RoomDtos {

    private RoomDtos() {
    }

    /** Create a room, or patch one: on an update every field is optional and only what is sent changes. */
    public record RoomRequest(
            @Size(max = 36) String branchId,
            @Size(min = 1, max = 80) String name,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000")
            @Digits(integer = 8, fraction = 2) BigDecimal nightlyRate,
            @Size(max = 60) String area,
            Boolean active
    ) {
    }

    public record RoomResponse(
            String id,
            String branchId,
            String name,
            BigDecimal nightlyRate,
            String area,
            boolean active,
            int sortOrder,
            /** Housekeeping state (ADR 0030): CLEAN, DIRTY, CLEANING, INSPECTED, MAINTENANCE. */
            String housekeepingStatus,
            /** True when the room may take a new stay on its active + housekeeping axes (dates aside). */
            boolean readyToLet,
            /** The room type whose rate card this room inherits (ADR 0030), or null. */
            String roomTypeId,
            /** The weekday rate a guest is actually charged — the type's when typed, else nightlyRate. */
            BigDecimal effectiveNightlyRate,
            /** The weekend rate, when the type sets one; else the same as the weekday rate. */
            BigDecimal effectiveWeekendRate,
            /** A short housekeeping note (damage, missing amenity), or null. */
            String housekeepingNote,
            /** The staff member responsible for cleaning this room now, or null. */
            String cleanerStaffProfileId,
            /** That cleaner's display name, resolved; null when none or since left. */
            String cleanerName,
            /** How many maintenance issues on this room are not yet done (ADR 0030 Phase 2). */
            int openIssueCount
    ) {
    }

    /**
     * Set a room's housekeeping state, and optionally who is cleaning it and a note (ADR 0030). A
     * null cleaner/note leaves it as is; {@code clearCleaner}/{@code clearNote} remove it.
     */
    public record HousekeepingRequest(
            @NotBlank @Size(max = 16) String status,
            @Size(max = 36) String cleanerStaffProfileId,
            @Size(max = 300) String note,
            Boolean clearCleaner,
            Boolean clearNote
    ) {
    }

    public record RoomListResponse(String branchId, List<RoomResponse> rooms) {
    }

    // ---------------------------------------------------------------- maintenance issues (ADR 0030 Phase 2)

    /** Raise a maintenance issue on a room: what broke, optionally a photo and who's on it. */
    public record MaintenanceIssueRequest(
            @NotBlank @Size(max = 500) String description,
            @Size(max = 1024) String photoUrl,
            @Size(max = 36) String assignedStaffProfileId) {
    }

    /** Update an issue: move its status, (re)assign it, or set/clear its photo. */
    public record MaintenanceIssueUpdate(
            @Size(max = 16) String status,
            @Size(max = 36) String assignedStaffProfileId,
            @Size(max = 1024) String photoUrl,
            Boolean clearAssignee,
            Boolean clearPhoto) {
    }

    /** A maintenance issue as the front desk sees it. */
    public record MaintenanceIssueResponse(
            String id,
            String roomId,
            String roomName,
            String description,
            /** OPEN, IN_PROGRESS or DONE. */
            String status,
            String photoUrl,
            String assignedStaffProfileId,
            /** The assignee's display name, resolved; null when none or since left. */
            String assignedName,
            Instant openedAt,
            Instant closedAt) {
    }

    public record MaintenanceIssueListResponse(String branchId, List<MaintenanceIssueResponse> issues) {
    }

    /** A room and the stays on it: the one in-house now, plus any booked for later. */
    public record RoomWithStays(RoomResponse room, List<OrderCard> stays) {
    }

    /**
     * A stay a cashier can charge a drink or a meal to: a guest who is checked in now (ADR 0030).
     * The order carries the charge; it is realized once, at checkout, never as a second sale.
     */
    public record InHouseStay(
            String orderId,
            long orderNumber,
            /** The room the guest is in, or null if the room is gone. */
            String roomId,
            String roomName,
            String guestName,
            /** What the folio stands at now, before this charge. */
            BigDecimal total
    ) {
    }

    /** The stays a cashier may charge to, at a branch. */
    public record InHouseStaysResponse(String branchId, List<InHouseStay> stays) {
    }

    /** Move a stay to another room (ADR 0030). */
    public record MoveStayRequest(@NotBlank @Size(max = 36) String roomId) {
    }

    /** Change a booking's check-in and check-out before the guest arrives (ADR 0030). */
    public record ChangeStayDatesRequest(
            @NotNull Instant checkIn,
            @NotNull Instant checkOut
    ) {
    }

    /**
     * The front desk of a branch: every room with its stays, plus any stay whose room is gone.
     * A stay is an open order on channel {@code LODGING}; its card is the same shape as a floor card.
     */
    public record FrontDeskResponse(
            String branchId, Instant asOf, List<RoomWithStays> rooms, List<OrderCard> otherStays,
            /** The business default check-in time for a new booking, "HH:mm" Nairobi (ADR 0031). */
            String defaultCheckInTime,
            /** The business default check-out time for a new booking, "HH:mm" Nairobi (ADR 0031). */
            String defaultCheckOutTime) {
    }

    /** A create needs a branch, a name and a rate; an update does not. */
    public record CreateRoomRequest(
            @NotBlank @Size(max = 36) String branchId,
            @NotBlank @Size(max = 80) String name,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000")
            @Digits(integer = 8, fraction = 2) BigDecimal nightlyRate,
            @Size(max = 60) String area
    ) {
    }

    // ---------------------------------------------------------------- room types (rate cards, ADR 0030)

    public record RoomTypeResponse(
            String id, String branchId, String name, BigDecimal defaultNightlyRate, BigDecimal weekendNightlyRate,
            Integer capacity, String bedConfig, String amenities, int sortOrder) {
    }

    public record RoomTypeListResponse(String branchId, List<RoomTypeResponse> types) {
    }

    /** Update a room type: every field optional, only what is sent changes. */
    public record RoomTypeRequest(
            @Size(min = 1, max = 80) String name,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000") @Digits(integer = 8, fraction = 2)
            BigDecimal defaultNightlyRate,
            @DecimalMin(value = "0") @DecimalMax("1000000") @Digits(integer = 8, fraction = 2)
            BigDecimal weekendNightlyRate,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(99) Integer capacity,
            @Size(max = 120) String bedConfig,
            @Size(max = 500) String amenities
    ) {
    }

    public record CreateRoomTypeRequest(
            @NotBlank @Size(max = 36) String branchId,
            @NotBlank @Size(max = 80) String name,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000") @Digits(integer = 8, fraction = 2)
            BigDecimal defaultNightlyRate,
            @DecimalMin(value = "0") @DecimalMax("1000000") @Digits(integer = 8, fraction = 2)
            BigDecimal weekendNightlyRate,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(99) Integer capacity,
            @Size(max = 120) String bedConfig,
            @Size(max = 500) String amenities
    ) {
    }

    /** Assign a room to a type, or clear it with a null/blank id (ADR 0030). */
    public record SetRoomTypeRequest(@Size(max = 36) String roomTypeId) {
    }

    // ---------------------------------------------------------------- guest stay history (ADR 0030)

    /** One of a guest's past or present stays, for repeat-guest recognition. */
    public record GuestStay(
            String orderId,
            long orderNumber,
            String roomName,
            Instant checkIn,
            Instant checkOut,
            /** "booked", "in-house", "checked-out" or "cancelled". */
            String state,
            BigDecimal total
    ) {
    }

    /** A guest's stays with this business, newest first, with a count. */
    public record GuestStaysResponse(String customerId, String customerName, int stayCount, List<GuestStay> stays) {
    }

    // ---------------------------------------------------------------- accommodation report (ADR 0030)

    /**
     * The accommodation dashboard for a branch over a period (ADR 0030). Today's snapshot comes from
     * the live stays; the money and occupancy figures come from the settled ROOM sale lines, kept
     * apart from restaurant revenue.
     */
    public record AccommodationReportResponse(
            String branchId,
            java.time.LocalDate from,
            java.time.LocalDate to,
            // Today, from the live front desk.
            int totalRooms,
            int occupied,
            int free,
            int arrivingToday,
            int leavingToday,
            int dirty,
            int underMaintenance,
            BigDecimal outstandingBalances,
            // The period, from settled room-night sale lines.
            long roomNightsSold,
            BigDecimal accommodationRevenue,
            /** Average rate per occupied room-night (revenue ÷ nights sold). */
            BigDecimal averageDailyRate,
            /** Room revenue per available room-night (revenue ÷ rooms ÷ days). */
            BigDecimal revPar,
            /** Nights sold ÷ available room-nights, as a fraction 0–1. */
            BigDecimal occupancyRate
    ) {
    }
}
