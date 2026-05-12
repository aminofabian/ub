package zelisline.ub.platform.realtime;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import zelisline.ub.notifications.domain.Notification;
import zelisline.ub.notifications.repository.NotificationRepository;

/**
 * Handles WebSocket lifecycle and routes inbound client frames.
 */
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    private static final int HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final int HEARTBEAT_TIMEOUT_SECONDS = 10;
    private static final int MAX_INBOUND_FRAME_BYTES = 2048;
    static final int MAX_OUTBOUND_QUEUE = 256;
    private static final int CLIENT_FRAME_RATE_PER_SECOND = 20;

    private final SessionRegistry sessionRegistry;
    private final RealtimeTicketService ticketService;
    private final MeterRegistry meterRegistry;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    private final Map<String, Instant> lastPong = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    private final Map<String, Integer> frameRateCounter = new ConcurrentHashMap<>();
    private final Map<String, Instant> frameRateWindow = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<QueuedFrame>> outboundQueues = new ConcurrentHashMap<>();
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    private final ScheduledThreadPoolExecutor heartbeatExecutor = new ScheduledThreadPoolExecutor(2, r -> {
        Thread t = new Thread(r, "ws-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public RealtimeWebSocketHandler(SessionRegistry sessionRegistry, RealtimeTicketService ticketService,
                                    MeterRegistry meterRegistry, NotificationRepository notificationRepository) {
        this.sessionRegistry = sessionRegistry;
        this.ticketService = ticketService;
        this.meterRegistry = meterRegistry;
        this.notificationRepository = notificationRepository;
        this.objectMapper = new ObjectMapper();
    }

    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdown();
        try {
            if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                heartbeatExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        TicketRecord ticketRecord = (TicketRecord) session.getAttributes().get("ticket_record");
        if (ticketRecord == null) {
            closeSession(session, new CloseStatus(4401, "Unauthorized"));
            return;
        }

        String sessionId = session.getId();
        RealtimeSession meta = new RealtimeSession(
                ticketRecord.userId(),
                ticketRecord.businessId(),
                "",
                ticketRecord.branchId(),
                ticketRecord.allowedChannels()
        );

        sessionRegistry.register(sessionId, session, meta, ticketRecord.allowedChannels());
        outboundQueues.put(sessionId, new LinkedBlockingQueue<>(MAX_OUTBOUND_QUEUE));

        lastPong.put(sessionId, Instant.now());
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleWithFixedDelay(
                () -> sendHeartbeat(sessionId),
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
        heartbeatTasks.put(sessionId, heartbeat);

        activeConnections.incrementAndGet();
        meterRegistry.gauge("realtime.connections.active", activeConnections);

        log.info("WS connection established: sessionId={} user={} business={} channels={}",
                sessionId, meta.userId(), meta.businessId(), meta.allowedChannels());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = session.getId();
        String payload = message.getPayload();

        if (payload.length() > MAX_INBOUND_FRAME_BYTES) {
            sendError(session, 4400, "Frame too large");
            return;
        }

        if (!checkFrameRate(sessionId)) {
            sendError(session, 4429, "Rate limited");
            return;
        }

        JsonNode frame;
        try {
            frame = objectMapper.readTree(payload);
        } catch (Exception e) {
            sendError(session, 4400, "Malformed JSON");
            return;
        }

        String op = frame.has("op") ? frame.get("op").asText() : null;
        if (op == null) {
            sendError(session, 4400, "Missing 'op' field");
            return;
        }

        switch (op) {
            case "subscribe" -> handleSubscribe(session, frame);
            case "unsubscribe" -> handleUnsubscribe(session, frame);
            case "pong" -> handlePong(sessionId);
            case "reauth" -> handleReauth(session, frame);
            case "catch-up" -> handleCatchUp(session, frame);
            default -> sendError(session, 4400, "Unknown operation: " + op);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        cleanup(sessionId);
        activeConnections.decrementAndGet();
        log.info("WS connection closed: sessionId={} code={} reason={}",
                sessionId, status.getCode(), status.getReason());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String sessionId = session.getId();
        log.warn("WS transport error: sessionId={}", sessionId, exception);
        cleanup(sessionId);
        activeConnections.decrementAndGet();
    }

    // ── Outbound helper ──

    public boolean sendFrame(String sessionId, String type, String eventId, String priority,
                             Instant eventTime, String dataJson) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        if (session == null || !session.isOpen()) {
            return false;
        }

        Instant at = eventTime != null ? eventTime : Instant.now();
        String frame = """
                {"v":1,"type":"%s","eventId":"%s","at":"%s","priority":"%s","data":%s}
                """.formatted(
                escapeJson(type),
                escapeJson(eventId),
                at.toString(),
                escapeJson(priority),
                dataJson
        );

        // ── Backpressure: bounded queue with priority eviction ──
        BlockingQueue<QueuedFrame> queue = outboundQueues.get(sessionId);
        if (queue != null) {
            QueuedFrame qf = new QueuedFrame(frame, priority);
            if (!queue.offer(qf)) {
                // Queue full — evict lowest-priority frame
                QueuedFrame evicted = evictLowPriority(queue);
                if (evicted != null) {
                    meterRegistry.counter("realtime.frames.dropped", "reason", "backpressure").increment();
                    log.debug("Backpressure eviction: sessionId={} evictedType={}", sessionId, evicted.priority);
                }
                if (!queue.offer(qf)) {
                    meterRegistry.counter("realtime.frames.dropped", "reason", "backpressure").increment();
                    return false;
                }
            }
            drainQueue(session, queue);
            meterRegistry.counter("realtime.frames.out", "type", type).increment();
            return true;
        }

        // No queue for this session — send directly
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(frame));
            }
            meterRegistry.counter("realtime.frames.out", "type", type).increment();
            return true;
        } catch (IOException e) {
            log.debug("Failed to send frame to sessionId={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    private void drainQueue(WebSocketSession session, BlockingQueue<QueuedFrame> queue) {
        QueuedFrame qf;
        while ((qf = queue.poll()) != null) {
            try {
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(qf.frame));
                    }
                }
            } catch (IOException e) {
                log.debug("Failed to drain queued frame: {}", e.getMessage());
                meterRegistry.counter("realtime.frames.dropped", "reason", "io_error").increment();
            }
        }
    }

    private QueuedFrame evictLowPriority(BlockingQueue<QueuedFrame> queue) {
        // Try evicting LOW first, then MEDIUM
        for (String target : new String[]{"LOW", "MEDIUM"}) {
            for (QueuedFrame qf : queue) {
                if (target.equals(qf.priority)) {
                    queue.remove(qf);
                    return qf;
                }
            }
        }
        return null; // HIGH frames are never evicted
    }

    // ── Frame handlers ──

    private void handleSubscribe(WebSocketSession session, JsonNode frame) {
        String sessionId = session.getId();
        String channel = frame.has("channel") ? frame.get("channel").asText() : null;
        if (channel == null || channel.isBlank()) {
            sendError(session, 4400, "Missing 'channel' field");
            return;
        }

        RealtimeSession meta = sessionRegistry.getMeta(sessionId);
        if (meta == null) {
            sendError(session, 4401, "Session not found");
            return;
        }

        if (!meta.allowedChannels().contains(channel)) {
            sendError(session, 4403, "Forbidden channel: " + channel);
            return;
        }

        Set<String> subs = sessionRegistry.getSubscriptions(sessionId);
        subs.add(channel);
        log.debug("WS subscribed: sessionId={} channel={}", sessionId, channel);
    }

    private void handleUnsubscribe(WebSocketSession session, JsonNode frame) {
        String sessionId = session.getId();
        String channel = frame.has("channel") ? frame.get("channel").asText() : null;
        if (channel != null) {
            Set<String> subs = sessionRegistry.getSubscriptions(sessionId);
            subs.remove(channel);
            log.debug("WS unsubscribed: sessionId={} channel={}", sessionId, channel);
        }
    }

    private void handlePong(String sessionId) {
        lastPong.put(sessionId, Instant.now());
    }

    private void handleReauth(WebSocketSession session, JsonNode frame) {
        String sessionId = session.getId();
        String newTicket = frame.has("ticket") ? frame.get("ticket").asText() : null;
        if (newTicket == null || newTicket.isBlank()) {
            sendError(session, 4401, "Missing 'ticket' field for reauth");
            return;
        }

        TicketRecord record = ticketService.validateAndConsume(newTicket);
        if (record == null) {
            sendError(session, 4401, "Invalid or expired reauth ticket");
            return;
        }

        RealtimeSession oldMeta = sessionRegistry.getMeta(sessionId);
        if (oldMeta == null) {
            sendError(session, 4401, "Session not found");
            return;
        }

        if (!oldMeta.userId().equals(record.userId())
                || !oldMeta.businessId().equals(record.businessId())) {
            sendError(session, 4403, "Reauth ticket does not match session principal");
            return;
        }

        RealtimeSession newMeta = new RealtimeSession(
                record.userId(),
                record.businessId(),
                oldMeta.roleId(),
                record.branchId(),
                record.allowedChannels()
        );
        sessionRegistry.updateMeta(sessionId, newMeta);
        log.debug("WS reauth successful: sessionId={}", sessionId);
    }

    private void handleCatchUp(WebSocketSession session, JsonNode frame) {
        String sessionId = session.getId();
        RealtimeSession meta = sessionRegistry.getMeta(sessionId);
        if (meta == null) {
            sendError(session, 4401, "Session not found");
            return;
        }

        String lastEventId = frame.has("lastEventId") ? frame.get("lastEventId").asText() : null;
        log.debug("WS catch-up replay: sessionId={} lastEventId={}", sessionId, lastEventId);

        // Query recent notifications for this business, limited to 50
        List<Notification> recent = notificationRepository
                .findByBusinessIdOrderByCreatedAtDesc(meta.businessId());

        int sent = 0;
        for (Notification n : recent) {
            if (sent >= 50) break;
            // Skip if we've already sent this notification (client has lastEventId)
            if (lastEventId != null && !lastEventId.isBlank() && n.getId().equals(lastEventId)) {
                break; // notifications are ordered by created_at DESC, stop here
            }
            try {
                var payload = new java.util.LinkedHashMap<String, Object>();
                payload.put("id", n.getId());
                payload.put("notificationType", n.getType());
                payload.put("createdAt", n.getCreatedAt().toString());
                if (n.getPayloadJson() != null) {
                    try {
                        @SuppressWarnings("unchecked")
                        var parsed = objectMapper.readValue(n.getPayloadJson(), java.util.Map.class);
                        payload.put("payload", parsed);
                    } catch (Exception e) {
                        payload.put("payload", n.getPayloadJson());
                    }
                }
                String dataJson = objectMapper.writeValueAsString(payload);
                sendFrame(sessionId, "notification.created", n.getId(), "MEDIUM",
                        n.getCreatedAt(), dataJson);
                sent++;
            } catch (Exception e) {
                log.debug("Failed to replay notification id={}: {}", n.getId(), e.getMessage());
            }
        }
        log.debug("WS catch-up replay complete: sessionId={} sent={}", sessionId, sent);
    }

    // ── Heartbeat ──

    private void sendHeartbeat(String sessionId) {
        WebSocketSession session = sessionRegistry.getSession(sessionId);
        if (session == null || !session.isOpen()) {
            cleanup(sessionId);
            activeConnections.decrementAndGet();
            return;
        }

        Instant last = lastPong.get(sessionId);
        if (last != null
                && Instant.now().minusSeconds(HEARTBEAT_INTERVAL_SECONDS + HEARTBEAT_TIMEOUT_SECONDS).isAfter(last)) {
            log.debug("WS heartbeat timeout: sessionId={}", sessionId);
            closeSession(session, new CloseStatus(4001, "Heartbeat timeout"));
            cleanup(sessionId);
            activeConnections.decrementAndGet();
            return;
        }

        try {
            synchronized (session) {
                session.sendMessage(new TextMessage("{\"v\":1,\"type\":\"ping\"}"));
            }
        } catch (IOException e) {
            log.debug("Failed to send ping to sessionId={}: {}", sessionId, e.getMessage());
            cleanup(sessionId);
            activeConnections.decrementAndGet();
        }
    }

    // ── Rate limiting ──

    private boolean checkFrameRate(String sessionId) {
        Instant now = Instant.now();
        Instant windowStart = frameRateWindow.get(sessionId);
        if (windowStart == null || now.minusSeconds(1).isAfter(windowStart)) {
            frameRateWindow.put(sessionId, now);
            frameRateCounter.put(sessionId, 1);
            return true;
        }

        int count = frameRateCounter.getOrDefault(sessionId, 0) + 1;
        frameRateCounter.put(sessionId, count);
        if (count > CLIENT_FRAME_RATE_PER_SECOND) {
            meterRegistry.counter("realtime.frames.dropped", "reason", "rate_limit").increment();
        }
        return count <= CLIENT_FRAME_RATE_PER_SECOND;
    }

    // ── Helpers ──

    private void sendError(WebSocketSession session, int code, String message) {
        try {
            String frame = """
                    {"v":1,"type":"error","code":%d,"message":"%s"}
                    """.formatted(code, escapeJson(message));
            synchronized (session) {
                session.sendMessage(new TextMessage(frame));
            }
        } catch (IOException e) {
            log.debug("Failed to send error frame: {}", e.getMessage());
        }
    }

    private void closeSession(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("Failed to close session: {}", e.getMessage());
        }
    }

    private void cleanup(String sessionId) {
        lastPong.remove(sessionId);
        frameRateWindow.remove(sessionId);
        frameRateCounter.remove(sessionId);
        outboundQueues.remove(sessionId);
        ScheduledFuture<?> heartbeat = heartbeatTasks.remove(sessionId);
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        sessionRegistry.unregister(sessionId);
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private record QueuedFrame(String frame, String priority) {}
}
