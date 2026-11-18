package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The public, unauthenticated room-booking storefront (ADR 0031). A guest at {@code {slug}.kiosk.ke}
 * sees the rooms that are let, their nightly rate and what is free for a date range, and requests a
 * stay with a name and a phone — no account, nothing paid online. The request opens a BOOKED stay on
 * the front desk for staff to confirm and check in. Nothing here exposes another guest, a phone, or
 * money owed; the business is resolved from the slug in the path, never from the body.
 */
public final class PublicRoomDtos {

    private PublicRoomDtos() {
    }

    /** What the public page shows about the lodging: the house, its branches and whether it takes bookings. */
    public record PublicRoomInfo(
            String storeName,
            boolean bookable,
            String checkInTime,
            String checkOutTime,
            List<PublicRoomBranch> branches) {
    }

    public record PublicRoomBranch(String id, String name) {
    }

    /** A room offered online: its name, what a night costs, and how many it sleeps. No internal state. */
    public record PublicRoom(
            String id,
            String name,
            BigDecimal nightlyRate,
            BigDecimal weekendRate,
            Integer capacity,
            String bedConfig,
            String amenities,
            String area) {
    }

    /** The rooms free for the requested nights at a branch. */
    public record PublicRoomAvailability(
            String branchId,
            String checkIn,
            String checkOut,
            int nights,
            List<PublicRoom> rooms) {
    }

    /** A guest's request to stay: which room, the nights, their name and phone. */
    public record PublicRoomBookingRequest(
            @NotBlank @Size(max = 36) String branchId,
            @NotBlank @Size(max = 36) String roomId,
            /** YYYY-MM-DD, Nairobi. */
            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String checkIn,
            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String checkOut,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 32) String phone,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(20) Integer guests,
            /** Made once per attempt by the device, so a retry does not book twice. */
            @NotBlank @Size(min = 16, max = 64) @Pattern(regexp = "^[A-Za-z0-9-]+$") String requestId) {
    }

    /**
     * Confirmation the guest sees: the house has their request, to confirm with them. The opaque
     * {@code bookingId} is the handle for an optional online deposit (ADR 0031); it names a single
     * stay and is useless for anything else — the deposit endpoint still re-checks the slug owns it.
     */
    public record PublicRoomBookingConfirmation(
            String bookingId,
            String roomName,
            String checkIn,
            String checkOut,
            int nights,
            BigDecimal estimatedTotal,
            String message) {
    }

    /** A guest asks to pay their booking deposit online by M-Pesa STK (ADR 0031). */
    public record PublicRoomDepositRequest(
            @NotBlank @Size(max = 36) String bookingId,
            /** The number to prompt; defaults to the phone on the booking when blank. */
            @Size(max = 32) String phone) {
    }

    /** The result of asking for an STK prompt: whether it went out, and a short status message. */
    public record PublicRoomDepositResponse(
            boolean accepted,
            BigDecimal amount,
            String checkoutRequestId,
            String message) {
    }

    /** An empty, unbranded "not available" so a probe cannot tell a closed shop from a wrong slug. */
    public record NotAvailable(@NotNull String message) {
    }
}
