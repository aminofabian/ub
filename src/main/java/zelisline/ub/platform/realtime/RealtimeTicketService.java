package zelisline.ub.platform.realtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Mints single-use, short-lived opaque tickets that authorize a WebSocket upgrade.
 *
 * <p>MySQL ({@code realtime_ws_tickets}) is the primary shared store when migration V129
 * is applied — all API replicas read/write the same rows. Redis is an optional accelerator.
 * Local single-JVM: in-memory fallback via {@link InMemoryTicketStore}.
 */
@Service
public class RealtimeTicketService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeTicketService.class);

    private static final int TICKET_BYTES = 32;
    private static final long TICKET_TTL_SECONDS = 60;

    private final SecureRandom secureRandom = new SecureRandom();
    private final StringRedisTemplate redisTemplate;
    private final DatabaseRealtimeTicketStore databaseStore;
    private final boolean redisAvailable;
    private final boolean databaseAvailable;
    private final long ticketTtlSeconds;
    private volatile boolean redisFailed = false;

    public RealtimeTicketService(
            @Value("${app.realtime.ticket.ttl-seconds:60}") long ticketTtlSeconds,
            @org.springframework.beans.factory.annotation.Autowired(required = false) StringRedisTemplate redisTemplate,
            @org.springframework.beans.factory.annotation.Autowired(required = false) DatabaseRealtimeTicketStore databaseStore
    ) {
        this.ticketTtlSeconds = ticketTtlSeconds > 0 ? ticketTtlSeconds : TICKET_TTL_SECONDS;
        this.redisAvailable = redisTemplate != null;
        this.redisTemplate = redisTemplate;
        this.databaseStore = databaseStore;
        this.databaseAvailable = databaseStore != null;
    }

    @PostConstruct
    void logActiveTicketStore() {
        if (databaseAvailable) {
            log.info("Realtime tickets: primary store=mysql (shared across API replicas), redis-cache={}",
                    redisAvailable);
        } else if (redisAvailable) {
            log.info("Realtime tickets: primary store=redis (mysql table not available)");
        } else {
            log.warn(
                    "Realtime tickets: in-memory only — WebSocket will fail when mint and upgrade hit different API instances");
        }
    }

    public TicketRecord mint(String userId, String businessId, String branchId, Set<String> allowedChannels) {
        byte[] raw = new byte[TICKET_BYTES];
        secureRandom.nextBytes(raw);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String ticketHash = sha256(ticket);

        TicketRecord record = new TicketRecord(
                ticket, ticketHash, userId, businessId, branchId,
                allowedChannels, Instant.now(), Instant.now().plusSeconds(ticketTtlSeconds));

        // MySQL first — always shared across replicas; avoids Redis-only tickets on multi-instance deploys.
        if (databaseAvailable) {
            databaseStore.put(ticketHash, record);
            cacheInRedis(ticketHash, record);
            return record;
        }
        if (redisAvailable && !redisFailed) {
            try {
                redisTemplate.opsForValue().set(
                        redisKey(ticketHash), serializeRecord(record), ticketTtlSeconds, TimeUnit.SECONDS);
                return record;
            } catch (Exception e) {
                log.warn("Redis unavailable for ticket mint, falling back to in-memory: {}", e.getMessage());
                redisFailed = true;
            }
        }
        InMemoryTicketStore.put(ticketHash, record);
        return record;
    }

    public TicketRecord validateAndConsume(String ticket) {
        TicketRecord record = peek(ticket);
        if (record == null) {
            return null;
        }
        return consume(ticket);
    }

    /**
     * Read ticket metadata without consuming it. Used during the WS handshake so
     * connection-limit rejections do not burn single-use tickets.
     */
    public TicketRecord peek(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        String ticketHash = sha256(ticket);
        TicketRecord record = null;

        if (databaseAvailable) {
            record = databaseStore.peek(ticketHash);
        }

        if (record == null && redisAvailable && !redisFailed) {
            try {
                String raw = redisTemplate.opsForValue().get(redisKey(ticketHash));
                if (raw != null) {
                    record = deserializeRecord(raw);
                }
            } catch (Exception e) {
                log.warn("Redis unavailable for ticket peek: {}", e.getMessage());
                redisFailed = true;
            }
        }

        if (record == null) {
            record = InMemoryTicketStore.get(ticketHash);
        }

        if (record == null) {
            return null;
        }
        if (Instant.now().isAfter(record.expiresAt())) {
            return null;
        }
        return record;
    }

    /** Consume a ticket after handshake checks pass. */
    public TicketRecord consume(String ticket) {
        String ticketHash = sha256(ticket);
        TicketRecord record = null;

        if (databaseAvailable) {
            record = databaseStore.consume(ticketHash);
        }

        if (record == null && redisAvailable && !redisFailed) {
            try {
                String raw = redisTemplate.opsForValue().getAndDelete(redisKey(ticketHash));
                if (raw != null) {
                    record = deserializeRecord(raw);
                }
            } catch (Exception e) {
                log.warn("Redis unavailable for ticket consume: {}", e.getMessage());
                redisFailed = true;
            }
        }

        if (record == null) {
            record = InMemoryTicketStore.remove(ticketHash);
        }

        if (record == null) {
            return null;
        }
        if (Instant.now().isAfter(record.expiresAt())) {
            return null;
        }

        return record;
    }

    public long ticketTtlSeconds() {
        return ticketTtlSeconds;
    }

    /** Which backing store mint/peek/consume use after startup (for ops probes). */
    public String activeTicketStore() {
        if (databaseAvailable) {
            return "mysql";
        }
        if (redisAvailable && !redisFailed) {
            return "redis";
        }
        return "in-memory";
    }

    public boolean isRedisConfigured() {
        return redisAvailable;
    }

    private void cacheInRedis(String ticketHash, TicketRecord record) {
        if (!redisAvailable || redisFailed) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    redisKey(ticketHash), serializeRecord(record), ticketTtlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis cache write failed for ticket (mysql is authoritative): {}", e.getMessage());
            redisFailed = true;
        }
    }

    private String redisKey(String ticketHash) {
        return "realtime:ticket:" + ticketHash;
    }

    private String serializeRecord(TicketRecord record) {
        return String.join("|",
                record.ticketHash(), record.userId(), record.businessId(),
                record.branchId() != null ? record.branchId() : "",
                String.join(",", record.allowedChannels()),
                record.issuedAt().toString(), record.expiresAt().toString());
    }

    private TicketRecord deserializeRecord(String raw) {
        String[] parts = raw.split("\\|");
        if (parts.length < 7) return null;
        String branchId = parts[3].isEmpty() ? null : parts[3];
        Set<String> channels = parts[4].isEmpty() ? Set.of() : Set.of(parts[4].split(","));
        return new TicketRecord("", parts[0], parts[1], parts[2],
                branchId, channels, Instant.parse(parts[5]), Instant.parse(parts[6]));
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
