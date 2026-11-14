package zelisline.ub.serviceops.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class TeamDtos {

    private TeamDtos() {
    }

    /**
     * Adds someone who serves customers. With an email and a PIN they can sign in and see their
     * own earnings; with neither they are a name on the team with no login.
     */
    public record AddTeamMemberRequest(
            @NotBlank @Size(max = 255) String displayName,
            @Email @Size(max = 191) String email,
            @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4 to 6 digits") String pin,
            @Size(max = 50) String phone,
            /** The branch they sign in at; optional. */
            @Size(max = 36) String branchId
    ) {
    }

    /** Gives a login to someone who was added by name only. Their earnings history stays theirs. */
    public record GiveLoginRequest(
            @NotBlank @Email @Size(max = 191) String email,
            @NotBlank @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4 to 6 digits") String pin,
            @Size(max = 36) String branchId
    ) {
    }

    /** How many name-only team members were given an account, and who could not be (plan limit). */
    public record SyncAccountsResponse(int created, java.util.List<String> skipped) {
    }

    public record TeamMemberResponse(
            String staffProfileId,
            String displayName,
            /** Null when they have no login. */
            String userId,
            String email,
            boolean canSignIn
    ) {
    }

    public record MyBooking(String ticketId, Instant start, Instant end, String customerName, String services) {
    }

    /** What a signed-in staff member sees about themself. Never anyone else's figures. */
    public record MyEarningsResponse(
            String staffProfileId,
            String displayName,
            /** {@code PERCENT}, {@code FIXED} or null when no commission is set. */
            String commissionType,
            BigDecimal commissionRate,
            /** What the shop owes them now: commission and tips, less what was paid. */
            BigDecimal owed,
            long servicesToday,
            BigDecimal commissionToday,
            BigDecimal tipsToday,
            BigDecimal paidToday,
            List<CommissionDtos.LedgerEntryResponse> entries,
            /** Their bookings still to come today. */
            List<MyBooking> bookingsToday
    ) {
    }
}
