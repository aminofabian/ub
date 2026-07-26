package zelisline.ub.marketplace.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalLoginResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalSessionRow;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.domain.SupplierUserSession;
import zelisline.ub.marketplace.repository.SupplierUserSessionRepository;
import zelisline.ub.platform.security.ClientIpResolver;
import zelisline.ub.platform.security.JwtTokenService;

@Service
@RequiredArgsConstructor
public class SupplierPortalSessionService {

    private static final Logger log = LoggerFactory.getLogger(SupplierPortalSessionService.class);

    private final SupplierUserSessionRepository sessionRepository;
    private final JwtTokenService jwtTokenService;

    @Value("${app.jwt.access-ttl-minutes:60}")
    private long accessTtlMinutes;

    /**
     * Mint a supplier JWT. Session row persistence is best-effort and must not
     * share a transaction with the JWT response — a failed insert would otherwise
     * mark the TX rollback-only and surface as HTTP 500 ({@code UnexpectedRollbackException}).
     */
    public SupplierPortalLoginResponse issueLogin(SupplierUser user, HttpServletRequest http) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(accessTtlMinutes, ChronoUnit.MINUTES);

        String sessionId = persistSessionBestEffort(user, jti, now, expiresAt, http);

        try {
            String roleKey = user.getRoleKey() == null || user.getRoleKey().isBlank()
                    ? "admin"
                    : user.getRoleKey();
            String access = jwtTokenService.createSupplierAccessToken(
                    user.getId(),
                    user.getMarketplaceSupplierId(),
                    roleKey,
                    jti);
            return new SupplierPortalLoginResponse(
                    access,
                    sessionId,
                    user.getId(),
                    user.getMarketplaceSupplierId(),
                    user.getEmail(),
                    user.getPhone(),
                    user.getName());
        } catch (RuntimeException ex) {
            log.error("supplier portal JWT mint failed for user {}: {}", user.getId(), ex.getMessage(), ex);
            return loginWithoutToken(user);
        }
    }

    public SupplierPortalLoginResponse loginWithoutToken(SupplierUser user) {
        return new SupplierPortalLoginResponse(
                null,
                null,
                user.getId(),
                user.getMarketplaceSupplierId(),
                user.getEmail(),
                user.getPhone(),
                user.getName());
    }

    @Transactional(readOnly = true)
    public List<SupplierPortalSessionRow> listSessions(String supplierUserId, String currentJti) {
        try {
            return sessionRepository.findBySupplierUserIdOrderByIssuedAtDesc(supplierUserId).stream()
                    .map(s -> new SupplierPortalSessionRow(
                            s.getId(),
                            s.getIp(),
                            s.getUserAgent(),
                            s.getIssuedAt(),
                            s.getLastSeenAt(),
                            s.getExpiresAt(),
                            currentJti != null && currentJti.equals(s.getAccessTokenJti()),
                            s.getRevokedAt() != null))
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("supplier portal sessions list failed: {}", ex.getMessage());
            return List.of();
        }
    }

    @Transactional
    public void revokeSession(String supplierUserId, String sessionId, String currentJti) {
        SupplierUserSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!supplierUserId.equals(session.getSupplierUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
        }
        if (session.getRevokedAt() != null) {
            return;
        }
        session.setRevokedAt(Instant.now());
        sessionRepository.save(session);
    }

    @Transactional
    public void revokeAll(String supplierUserId) {
        try {
            sessionRepository.revokeAllActiveForUser(supplierUserId, Instant.now());
        } catch (DataAccessException ex) {
            log.warn("supplier portal revoke-all failed (is Flyway V172 applied?): {}", ex.getMessage());
        }
    }

    public void touch(String jti) {
        try {
            sessionRepository.touchLastSeen(jti, Instant.now());
        } catch (RuntimeException ex) {
            log.debug("supplier portal session touch failed: {}", ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Optional<SupplierUserSession> findByJti(String jti) {
        try {
            return sessionRepository.findByAccessTokenJti(jti);
        } catch (RuntimeException ex) {
            log.debug("supplier portal session lookup failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private String persistSessionBestEffort(
            SupplierUser user,
            String jti,
            Instant now,
            Instant expiresAt,
            HttpServletRequest http
    ) {
        try {
            SupplierUserSession session = new SupplierUserSession();
            session.setSupplierUserId(user.getId());
            session.setMarketplaceSupplierId(user.getMarketplaceSupplierId());
            session.setAccessTokenJti(jti);
            session.setUserAgent(trimUa(http == null ? null : http.getHeader("User-Agent")));
            session.setIp(http == null ? null : ClientIpResolver.resolve(http));
            session.setIssuedAt(now);
            session.setExpiresAt(expiresAt);
            session.setLastSeenAt(now);
            sessionRepository.saveAndFlush(session);
            return session.getId();
        } catch (RuntimeException ex) {
            // Missing V172 table, FK failure, etc. — claim/login must still succeed.
            Throwable root = rootCause(ex);
            log.warn(
                    "supplier portal session persist failed (is Flyway V172 applied?): {}",
                    root != null && root.getMessage() != null ? root.getMessage() : ex.getMessage());
            return null;
        }
    }

    private static Throwable rootCause(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static String trimUa(String ua) {
        if (ua == null || ua.isBlank()) {
            return null;
        }
        String trimmed = ua.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }
}
