package zelisline.ub.marketplace.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
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

    private final SupplierUserSessionRepository sessionRepository;
    private final JwtTokenService jwtTokenService;

    @Value("${app.jwt.access-ttl-minutes:60}")
    private long accessTtlMinutes;

    @Transactional
    public SupplierPortalLoginResponse issueLogin(SupplierUser user, HttpServletRequest http) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(accessTtlMinutes, ChronoUnit.MINUTES);

        SupplierUserSession session = new SupplierUserSession();
        session.setSupplierUserId(user.getId());
        session.setMarketplaceSupplierId(user.getMarketplaceSupplierId());
        session.setAccessTokenJti(jti);
        session.setUserAgent(trimUa(http == null ? null : http.getHeader("User-Agent")));
        session.setIp(http == null ? null : ClientIpResolver.resolve(http));
        session.setIssuedAt(now);
        session.setExpiresAt(expiresAt);
        session.setLastSeenAt(now);
        sessionRepository.save(session);

        String access = jwtTokenService.createSupplierAccessToken(
                user.getId(),
                user.getMarketplaceSupplierId(),
                user.getRoleKey(),
                jti);
        return new SupplierPortalLoginResponse(
                access,
                session.getId(),
                user.getId(),
                user.getMarketplaceSupplierId(),
                user.getEmail(),
                user.getPhone(),
                user.getName());
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
        sessionRepository.revokeAllActiveForUser(supplierUserId, Instant.now());
    }

    @Transactional
    public void touch(String jti) {
        sessionRepository.touchLastSeen(jti, Instant.now());
    }

    private static String trimUa(String ua) {
        if (ua == null || ua.isBlank()) {
            return null;
        }
        String trimmed = ua.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }
}
