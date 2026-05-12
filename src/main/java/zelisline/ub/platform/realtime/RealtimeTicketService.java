package zelisline.ub.platform.realtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Mints single-use, short-lived opaque tickets that authorize a WebSocket upgrade.
 *
 * <p>Cloud profile: tickets stored hashed in Redis with TTL.
 * Local profile (or Redis down): tickets stored in-memory via {@link InMemoryTicketStore}.
 */
@Service
public class RealtimeTicketService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeTicketService.class);

    private static final int TICKET_BYTES = 32;
    private static final long TICKET_TTL_SECONDS = 60;

    private final SecureRandom secureRandom = new SecureRandom();
    private final StringRedisTemplate redisTemplate;
    private final boolean redisAvailable;
    private final long ticketTtlSeconds;
    private volatile boolean redisFailed = false;

    public RealtimeTicketService(
            @Value("${app.realtime.ticket.ttl-seconds:60}") long ticketTtlSeconds,
            @org.springframework.beans.factory.annotation.Autowired(required = false) StringRedisTemplate redisTemplate
    ) {
        this.ticketTtlSeconds = ticketTtlSeconds > 0 ? ticketTtlSeconds : TICKET_TTL_SECONDS;
        this.redisAvailable = redisTemplate != null;
        this.redisTemplate = redisTemplate;
    }

    public TicketRecord mint(String userId, String businessId, String branchId, Set<String> allowedChannels) {
        byte[] raw = new byte[TICKET_BYTES];
        secureRandom.nextBytes(raw);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String ticketHash = sha256(ticket);

        TicketRecord record = new TicketRecord(
                ticket, ticketHash, userId, businessId, branchId,
                allowedChannels, Instant.now(), Instant.now().plusSeconds(ticketTtlSeconds));

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
        String ticketHash = sha256(ticket);
        TicketRecord record = null;

        if (redisAvailable && !redisFailed) {
            try {
                String raw = redisTemplate.opsForValue().getAndDelete(redisKey(ticketHash));
                if (raw != null) {
                    record = deserializeRecord(raw);
                }
            } catch (Exception e) {
                log.warn("Redis unavailable for ticket validation, falling back to in-memory: {}", e.getMessage());
                redisFailed = true;
            }
        }

        if (record == null) {
            record = InMemoryTicketStore.remove(ticketHash);
        }

        if (record == null) return null;
        if (Instant.now().isAfter(record.expiresAt())) return null;

        return record;
    }

    public long ticketTtlSeconds() {
        return ticketTtlSeconds;
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
