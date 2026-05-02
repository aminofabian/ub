package zelisline.ub.platform.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * HS256 access JWTs (PHASE_1_PLAN.md §3.2). Claims: {@code sub}, {@code jti},
 * {@code business_id}, {@code role} (role id), optional {@code branch_id}.
 */
@Service
public class JwtTokenService {

    public static final String CLAIM_BUSINESS_ID = "business_id";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_BRANCH_ID = "branch_id";
    public static final String CLAIM_PRINCIPAL_KIND = "principal_kind";
    public static final String PRINCIPAL_SUPER_ADMIN = "SUPER_ADMIN";

    private final SecretKey key;
    private final long accessTtlMinutes;

    public JwtTokenService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-ttl-minutes:15}") long accessTtlMinutes
    ) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes (256 bits) for HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtlMinutes = accessTtlMinutes;
    }

    public String createAccessToken(
            String userId,
            String businessId,
            String roleId,
            String branchId,
            String jti
    ) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessTtlMinutes * 60);
        var builder = Jwts.builder()
                .id(jti)
                .subject(userId)
                .claim(CLAIM_BUSINESS_ID, businessId)
                .claim(CLAIM_ROLE, roleId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key);
        if (branchId != null && !branchId.isBlank()) {
            builder.claim(CLAIM_BRANCH_ID, branchId);
        }
        return builder.compact();
    }

    /** Stateless super-admin access token (no {@code business_id}, no refresh session row). */
    public String createSuperAdminAccessToken(String superAdminId, String jti) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessTtlMinutes * 60);
        return Jwts.builder()
                .id(jti)
                .subject(superAdminId)
                .claim(CLAIM_PRINCIPAL_KIND, PRINCIPAL_SUPER_ADMIN)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    public Claims parseAndValidate(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
