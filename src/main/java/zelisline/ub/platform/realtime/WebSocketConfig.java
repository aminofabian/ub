package zelisline.ub.platform.realtime;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import io.micrometer.core.instrument.MeterRegistry;
import zelisline.ub.notifications.repository.NotificationRepository;

/**
 * Registers the WebSocket endpoint at {@code /api/v1/realtime}.
 *
 * <p>The handshake interceptor validates the single-use ticket from the
 * {@code ticket} query parameter and enforces per-user and per-business
 * connection limits before upgrading to WebSocket.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    static final String WS_PATH = "/api/v1/realtime";

    private final RealtimeTicketService ticketService;
    private final SessionRegistry sessionRegistry;
    private final MeterRegistry meterRegistry;
    private final NotificationRepository notificationRepository;
    private final int maxConnectionsPerUser;
    private final int maxConnectionsPerBusiness;

    public WebSocketConfig(
            RealtimeTicketService ticketService,
            SessionRegistry sessionRegistry,
            MeterRegistry meterRegistry,
            NotificationRepository notificationRepository,
            @Value("${app.realtime.max-connections-per-user:5}") int maxConnectionsPerUser,
            @Value("${app.realtime.max-connections-per-business:50}") int maxConnectionsPerBusiness
    ) {
        this.ticketService = ticketService;
        this.sessionRegistry = sessionRegistry;
        this.meterRegistry = meterRegistry;
        this.notificationRepository = notificationRepository;
        this.maxConnectionsPerUser = maxConnectionsPerUser;
        this.maxConnectionsPerBusiness = maxConnectionsPerBusiness;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(realtimeWebSocketHandler(), WS_PATH)
                .addInterceptors(ticketHandshakeInterceptor())
                .setAllowedOrigins("*");
    }

    @Bean
    public RealtimeWebSocketHandler realtimeWebSocketHandler() {
        return new RealtimeWebSocketHandler(sessionRegistry, ticketService, meterRegistry, notificationRepository);
    }

    @Bean
    public HandshakeInterceptor ticketHandshakeInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(
                    ServerHttpRequest request,
                    ServerHttpResponse response,
                    WebSocketHandler wsHandler,
                    Map<String, Object> attributes
            ) {
                String query = request.getURI().getQuery();
                if (query == null || !query.contains("ticket=")) {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    meterRegistry.counter("realtime.tickets.rejected", "reason", "missing").increment();
                    log.debug("WS handshake rejected: no ticket parameter");
                    return false;
                }

                String ticket = extractQueryParam(query, "ticket");
                if (ticket == null || ticket.isBlank()) {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    meterRegistry.counter("realtime.tickets.rejected", "reason", "empty").increment();
                    log.debug("WS handshake rejected: empty ticket");
                    return false;
                }

                TicketRecord record = ticketService.validateAndConsume(ticket);
                if (record == null) {
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    meterRegistry.counter("realtime.tickets.rejected", "reason", "invalid").increment();
                    log.debug("WS handshake rejected: invalid/expired ticket");
                    return false;
                }

                // ── Connection limits ──
                int userSessions = (int) sessionRegistry.findSessionsByUser(
                        record.businessId(), record.userId()).stream()
                        .map(sessionRegistry::getSession)
                        .filter(s -> s != null && s.isOpen())
                        .count();
                if (userSessions >= maxConnectionsPerUser) {
                    response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    meterRegistry.counter("realtime.tickets.rejected", "reason", "user_limit").increment();
                    log.debug("WS handshake rejected: user connection limit reached user={} count={}",
                            record.userId(), userSessions);
                    return false;
                }

                int businessSessions = sessionRegistry.activeSessionCountForBusiness(record.businessId());
                if (businessSessions >= maxConnectionsPerBusiness) {
                    response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    meterRegistry.counter("realtime.tickets.rejected", "reason", "business_limit").increment();
                    log.debug("WS handshake rejected: business connection limit reached business={} count={}",
                            record.businessId(), businessSessions);
                    return false;
                }

                attributes.put("ticket_record", record);
                meterRegistry.counter("realtime.tickets.minted").increment();
                log.debug("WS handshake accepted: user={} business={}",
                        record.userId(), record.businessId());
                return true;
            }

            @Override
            public void afterHandshake(
                    ServerHttpRequest request,
                    ServerHttpResponse response,
                    WebSocketHandler wsHandler,
                    Exception exception
            ) {
                // no-op
            }
        };
    }

    private static String extractQueryParam(String query, String param) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && param.equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }
}
