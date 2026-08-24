package zelisline.ub.platform.realtime;

import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Mints WebSocket tickets for the super-admin console. The tenant ticket
 * endpoint requires a {@code TenantPrincipal}; super-admin JWTs authenticate
 * with a plain principal, so they get a dedicated endpoint under
 * {@code /api/v1/super-admin/**} (guarded by {@code ROLE_SUPER_ADMIN}).
 *
 * <p>Super-admin sessions connect with the {@link RealtimeScopes#PLATFORM}
 * business scope and the {@code SUPER_ADMIN} role, and may only subscribe to
 * channels granted here (the super-admin console currently uses {@code support}).
 */
@RestController
@RequestMapping("/api/v1/super-admin/realtime")
public class SuperAdminRealtimeTicketController {

    private final RealtimeTicketService ticketService;
    private final RealtimeTicketController tenantTicketController;

    public SuperAdminRealtimeTicketController(
            RealtimeTicketService ticketService,
            RealtimeTicketController tenantTicketController
    ) {
        this.ticketService = ticketService;
        this.tenantTicketController = tenantTicketController;
    }

    @PostMapping("/tickets")
    public ResponseEntity<Map<String, Object>> mintTicket(
            @RequestBody(required = false) TicketRequest body,
            HttpServletRequest request
    ) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof String superAdminId) || superAdminId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Super-admin session required");
        }

        Set<String> channels = (body != null && body.channels() != null && !body.channels().isEmpty())
                ? Set.copyOf(body.channels())
                : Set.of("support");

        TicketRecord record = ticketService.mint(
                superAdminId,
                RealtimeScopes.PLATFORM,
                null,
                channels
        );

        return ResponseEntity.ok(Map.of(
                "ticket", record.ticket(),
                "expiresAt", record.expiresAt().toEpochMilli(),
                "wsUrl", tenantTicketController.resolveWebSocketUrl(request)
        ));
    }

    /**
     * Request body for platform ticket minting.
     *
     * @param channels logical channel names to allow on this ticket (default: ["support"])
     */
    public record TicketRequest(Set<String> channels) {}
}
