package zelisline.ub.platform.realtime;

import java.time.Instant;
import java.util.Set;

/**
 * Immutable record representing a minted WebSocket ticket.
 *
 * @param ticket          the opaque ticket string (sent to client; never logged at INFO)
 * @param ticketHash      SHA-256 hash of the ticket (storage key)
 * @param userId          authenticated user id
 * @param businessId      tenant id
 * @param branchId        optional branch context from JWT
 * @param allowedChannels logical channel names the session may subscribe to
 * @param issuedAt        when the ticket was minted
 * @param expiresAt       when the ticket expires (typically issuedAt + 60s)
 */
public record TicketRecord(
        String ticket,
        String ticketHash,
        String userId,
        String businessId,
        String branchId,
        Set<String> allowedChannels,
        Instant issuedAt,
        Instant expiresAt
) {}
