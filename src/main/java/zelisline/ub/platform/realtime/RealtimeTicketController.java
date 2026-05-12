package zelisline.ub.platform.realtime;

import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Mints single-use WebSocket tickets.
 *
 * <p>POST /api/v1/realtime/tickets
 *
 * <p>Requires a valid JWT access token (standard bearer auth).
 * Returns an opaque ticket that the client uses to upgrade to WebSocket
 * at {@code /api/v1/realtime?ticket=<value>}.
 */
@RestController
@RequestMapping("/api/v1/realtime")
public class RealtimeTicketController {

    private final RealtimeTicketService ticketService;

    public RealtimeTicketController(RealtimeTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping("/tickets")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> mintTicket(
            @RequestBody(required = false) TicketRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);

        Set<String> channels = (body != null && body.channels() != null && !body.channels().isEmpty())
                ? Set.copyOf(body.channels())
                : Set.of("notifications");

        TicketRecord record = ticketService.mint(
                principal.userId(),
                businessId,
                principal.branchId(),
                channels
        );

        return ResponseEntity.ok(Map.of(
                "ticket", record.ticket(),
                "expiresAt", record.expiresAt().toEpochMilli(),
                "wsUrl", "/api/v1/realtime"
        ));
    }

    /**
     * Request body for ticket minting.
     *
     * @param channels logical channel names to allow on this ticket (default: ["notifications"])
     */
    public record TicketRequest(Set<String> channels) {}
}
