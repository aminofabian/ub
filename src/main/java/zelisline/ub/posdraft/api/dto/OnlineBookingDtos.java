package zelisline.ub.posdraft.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class OnlineBookingDtos {

    private OnlineBookingDtos() {
    }

    /** Opening and closing time of one weekday, "HH:mm". */
    public record DayHours(
            @NotBlank @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String open,
            @NotBlank @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String close
    ) {
    }

    /**
     * Keys are {@code mon tue wed thu fri sat sun}; a day left out is closed. An empty or missing
     * map switches online booking off for the branch.
     */
    public record SetBookingHoursRequest(
            @NotBlank @Size(max = 36) String branchId,
            Map<String, DayHours> days
    ) {
    }

    public record BookingHoursResponse(String branchId, boolean online, Map<String, DayHours> days) {
    }

    // ------------------------------------------------------------------ public

    public record PublicBranch(
            String id, String name, String address,
            /** When customers may book here, by weekday key; a day left out is closed. */
            Map<String, DayHours> hours
    ) {
    }

    public record PublicService(
            String id, String name, String description, int durationMinutes, BigDecimal price,
            /** True when the price depends on the work (length of braids); shown as "from". */
            boolean priceIsVariable,
            /** Who can be booked for it; empty means anyone on the staff list. */
            List<String> staffIds
    ) {
    }

    public record PublicStaff(String id, String name) {
    }

    public record PublicBookingInfo(
            String businessName,
            String timezone,
            String currency,
            List<PublicBranch> branches,
            List<PublicService> services,
            List<PublicStaff> staff
    ) {
    }

    public record PublicSlots(LocalDate date, String timezone, List<Instant> starts) {
    }

    /** One day on the booking calendar: whether the branch takes bookings then, and how many start times are left. */
    public record PublicDay(LocalDate date, boolean open, int free) {
    }

    public record PublicDays(String timezone, List<PublicDay> days) {
    }

    public record PublicBookingRequest(
            @NotBlank @Size(max = 36) String branchId,
            @NotBlank @Size(max = 36) String serviceId,
            /** Null or blank: anyone who is free. */
            @Size(max = 36) String staffId,
            @NotNull Instant start,
            @NotBlank @Size(min = 2, max = 80) String name,
            @NotBlank @Size(max = 20) String phone,
            /** Made up once by the page; sending it again returns the same booking. */
            @NotBlank @Size(min = 16, max = 64) @Pattern(regexp = "^[A-Za-z0-9-]+$") String requestId
    ) {
    }

    public record PublicBookingConfirmation(
            String reference,
            String serviceName,
            String staffName,
            Instant start,
            Instant end,
            String branchName
    ) {
    }
}
