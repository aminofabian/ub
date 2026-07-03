package zelisline.ub.platform.realtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * MySQL-backed ticket store for multi-instance deployments without Redis.
 *
 * <p>Tickets are short-lived (60s) and single-use. All API replicas share the
 * same database, so mint on instance A and upgrade on instance B succeed.
 */
@Component
@Profile("!test")
public class DatabaseRealtimeTicketStore {

    private final JdbcTemplate jdbc;

    public DatabaseRealtimeTicketStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void put(String ticketHash, TicketRecord record) {
        jdbc.update(
                """
                INSERT INTO realtime_ws_tickets
                    (ticket_hash, user_id, business_id, branch_id, allowed_channels, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                ticketHash,
                record.userId(),
                record.businessId(),
                record.branchId(),
                String.join(",", record.allowedChannels()),
                Timestamp.from(record.issuedAt()),
                Timestamp.from(record.expiresAt())
        );
    }

    TicketRecord peek(String ticketHash) {
        try {
            return jdbc.queryForObject(
                    """
                    SELECT user_id, business_id, branch_id, allowed_channels, issued_at, expires_at
                    FROM realtime_ws_tickets
                    WHERE ticket_hash = ?
                    """,
                    (rs, rowNum) -> mapRow(ticketHash, rs),
                    ticketHash
            );
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    TicketRecord consume(String ticketHash) {
        TicketRecord record = peek(ticketHash);
        if (record == null) {
            return null;
        }
        int deleted = jdbc.update(
                "DELETE FROM realtime_ws_tickets WHERE ticket_hash = ?",
                ticketHash
        );
        return deleted > 0 ? record : null;
    }

    private static TicketRecord mapRow(String ticketHash, ResultSet rs) throws SQLException {
        String branchId = rs.getString("branch_id");
        String channelsRaw = rs.getString("allowed_channels");
        Set<String> channels = channelsRaw == null || channelsRaw.isBlank()
                ? Set.of()
                : Arrays.stream(channelsRaw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        Instant issuedAt = rs.getTimestamp("issued_at").toInstant();
        Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
        return new TicketRecord(
                "",
                ticketHash,
                rs.getString("user_id"),
                rs.getString("business_id"),
                branchId,
                channels,
                issuedAt,
                expiresAt
        );
    }
}
