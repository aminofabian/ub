package zelisline.ub.platform.realtime;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight ops probe for WebSocket ticket storage (no auth).
 *
 * <p>GET /api/v1/realtime/status — use after deploy to confirm V129 MySQL tickets
 * are active instead of per-JVM in-memory storage.
 */
@RestController
@RequestMapping("/api/v1/realtime")
public class RealtimeStatusController {

    private final RealtimeTicketService ticketService;
    private final SessionRegistry sessionRegistry;
    private final JdbcTemplate jdbcTemplate;

    public RealtimeStatusController(
            RealtimeTicketService ticketService,
            SessionRegistry sessionRegistry,
            @Autowired(required = false) JdbcTemplate jdbcTemplate
    ) {
        this.ticketService = ticketService;
        this.sessionRegistry = sessionRegistry;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean roundTripOk = ticketService.selfTestRoundTrip();
        int activeConnections = sessionRegistry.activeSessionCount();

        body.put("ticketStore", ticketService.activeTicketStore());
        body.put("redisConfigured", ticketService.isRedisConfigured());
        body.put("mysqlTicketTable", mysqlTicketTablePresent());
        body.put("ticketTtlSeconds", ticketService.ticketTtlSeconds());
        body.put("ticketRoundTripOk", roundTripOk);
        body.put("activeConnectionsThisInstance", activeConnections);
        body.put("wsPath", WebSocketConfig.WS_PATH);
        if (!roundTripOk) {
            body.put("hint", "Ticket mint/peek failed on this instance — check MySQL connectivity and timezone");
        } else if (activeConnections >= 5) {
            body.put("hint", "Many open sessions on this instance — close extra tabs or restart the API container");
        } else {
            body.put("hint", "Ticket store OK — if browsers still fail WS, check nginx Connection upgrade map (see DEPLOYMENT.md)");
        }
        return ResponseEntity.ok(body);
    }

    private boolean mysqlTicketTablePresent() {
        if (jdbcTemplate == null) {
            return false;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name = 'realtime_ws_tickets'
                    """,
                    Integer.class
            );
            return count != null && count > 0;
        } catch (Exception ex) {
            return false;
        }
    }
}
