package zelisline.ub.platform.realtime;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
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
    private final String publicApiBaseUrl;

    public RealtimeTicketController(
            RealtimeTicketService ticketService,
            @Value("${app.public.api-base-url:http://localhost:5050}") String publicApiBaseUrl
    ) {
        this.ticketService = ticketService;
        this.publicApiBaseUrl = publicApiBaseUrl == null ? "" : publicApiBaseUrl.trim();
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
                "wsUrl", resolveWebSocketUrl(request)
        ));
    }

    /**
     * Absolute WebSocket URL for the client upgrade. Browsers cannot use the Next.js
     * BFF for WebSocket upgrades, so the ticket must tell the client which API host
     * to open — derived from {@code API_PUBLIC_BASE_URL} on the Java API.
     */
    String resolveWebSocketUrl(HttpServletRequest request) {
        String base = publicApiBaseUrl;
        if (base.isBlank()) {
            int port = request.getServerPort();
            boolean defaultPort =
                    ("https".equalsIgnoreCase(request.getScheme()) && port == 443)
                            || ("http".equalsIgnoreCase(request.getScheme()) && port == 80);
            base = request.getScheme() + "://" + request.getServerName()
                    + (defaultPort ? "" : ":" + port);
        }
        try {
            URI uri = URI.create(base.replaceAll("/+$", "") + "/api/v1/realtime");
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase();
            String wsScheme = scheme.equals("https") || scheme.equals("wss") ? "wss" : "ws";
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "/api/v1/realtime";
            }
            int port = uri.getPort();
            String authority = port > 0 ? host + ":" + port : host;
            return wsScheme + "://" + authority + uri.getPath();
        } catch (IllegalArgumentException ex) {
            return "/api/v1/realtime";
        }
    }

    /**
     * Request body for ticket minting.
     *
     * @param channels logical channel names to allow on this ticket (default: ["notifications"])
     */
    public record TicketRequest(Set<String> channels) {}
}
