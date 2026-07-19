package zelisline.ub.platform.security;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.identity.application.UserSessionActivity;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserSession;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.identity.repository.UserSessionRepository;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Validates {@code Authorization: Bearer} access tokens: tenant JWTs require host
 * tenant alignment and an active {@code user_sessions} row; super-admin JWTs are
 * stateless (PHASE_1_PLAN.md §3.3).
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PROBLEM_BASE = "urn:problem:";
    /** Keep in sync with {@code AuthService.SESSION_IDLE_EXPIRED_TITLE}. */
    private static final String SESSION_IDLE_EXPIRED_TITLE = "Session idle timeout expired";

    private final JwtTokenService jwtTokenService;
    private final UserSessionRepository userSessionRepository;
    private final UserRepository userRepository;
    private final SuperAdminRepository superAdminRepository;
    private final SupplierUserRepository supplierUserRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;
    private final UserSessionActivity userSessionActivity;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Sign-up and sign-in must not fail because the browser still has a Bearer token
     * from another tenant or an expired session (common after onboarding redirect).
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return PublicAuthEndpoints.matches(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (token.startsWith("kpos_")) {
            filterChain.doFilter(request, response);
            return;
        }

        final Claims claims;
        try {
            claims = jwtTokenService.parseAndValidate(token);
        } catch (JwtException | IllegalArgumentException ex) {
            publishSecurityEvent(request, null, AuditEventTypes.LOGIN_FAILED, "Invalid or expired access token");
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Invalid or expired access token", "unauthorized", "token_expired");
            return;
        }

        String kind = claims.get(JwtTokenService.CLAIM_PRINCIPAL_KIND, String.class);
        if (JwtTokenService.PRINCIPAL_SUPER_ADMIN.equals(kind)) {
            if (!authenticateSuperAdmin(claims, response)) {
                return;
            }
            try {
                filterChain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
            return;
        }
        if (JwtTokenService.PRINCIPAL_SUPPLIER.equals(kind)) {
            if (!authenticateSupplier(claims, response)) {
                return;
            }
            try {
                filterChain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
            return;
        }

        final String resolvedTenant;
        try {
            resolvedTenant = TenantRequestIds.resolveBusinessId(request);
        } catch (ResponseStatusException ex) {
            HttpStatus st = HttpStatus.resolve(ex.getStatusCode().value());
            if (st == null) {
                st = HttpStatus.BAD_REQUEST;
            }
            writeProblem(response, st,
                    ex.getReason() != null ? ex.getReason() : "Bad request", "bad-request");
            return;
        }

        String claimTenant = claims.get(JwtTokenService.CLAIM_BUSINESS_ID, String.class);
        if (claimTenant == null || !claimTenant.equals(resolvedTenant)) {
            publishSecurityEvent(request, resolvedTenant, AuditEventTypes.LOGIN_FAILED, "Token tenant mismatch");
            writeProblem(response, HttpStatus.FORBIDDEN,
                    "Token tenant does not match resolved host tenant", "forbidden");
            return;
        }

        String jti = claims.getId();
        if (jti == null || jti.isBlank()) {
            publishSecurityEvent(request, resolvedTenant, AuditEventTypes.LOGIN_FAILED, "Session is no longer active");
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Session is no longer active", "unauthorized");
            return;
        }
        var sessionOpt = userSessionRepository.findByAccessTokenJtiAndRevokedAtIsNull(jti);
        if (sessionOpt.isEmpty()) {
            publishSecurityEvent(request, resolvedTenant, AuditEventTypes.LOGIN_FAILED, "Session is no longer active");
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Session is no longer active", "unauthorized");
            return;
        }
        UserSession session = sessionOpt.get();
        // Enforce idle on access-token requests (not only on refresh) so leftover
        // JWTs cannot outlive the sliding inactivity window.
        try {
            if (userSessionActivity.revokeIfIdle(session)) {
                publishSecurityEvent(
                        request, resolvedTenant, AuditEventTypes.LOGIN_FAILED,
                        SESSION_IDLE_EXPIRED_TITLE);
                writeProblem(
                        response,
                        HttpStatus.UNAUTHORIZED,
                        SESSION_IDLE_EXPIRED_TITLE,
                        "unauthorized");
                return;
            }
        } catch (Exception ignored) {
            // Fail open on idle-check persistence errors; refresh still enforces idle.
        }

        String userId = claims.getSubject();
        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, resolvedTenant).orElse(null);
        if (user == null || user.getDeletedAt() != null) {
            publishSecurityEvent(request, resolvedTenant, AuditEventTypes.LOGIN_FAILED, "User not found");
            writeProblem(response, HttpStatus.UNAUTHORIZED, "User not found", "unauthorized");
            return;
        }
        if (user.statusAsEnum() != UserStatus.ACTIVE) {
            publishSecurityEvent(request, resolvedTenant, AuditEventTypes.LOGIN_FAILED, "Account is not active");
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Account is not active", "unauthorized");
            return;
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            publishSecurityEvent(request, resolvedTenant, AuditEventTypes.LOGIN_FAILED, "Account is temporarily locked");
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Account is temporarily locked", "unauthorized");
            return;
        }

        String roleId = claims.get(JwtTokenService.CLAIM_ROLE, String.class);
        if (roleId == null || roleId.isBlank()) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Invalid token claims", "unauthorized");
            return;
        }
        String branchId = claims.get(JwtTokenService.CLAIM_BRANCH_ID, String.class);

        var principal = new TenantPrincipal(userId, resolvedTenant, roleId, branchId, jti);
        var authentication = new TenantAuthenticationToken(principal);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        try {
            userSessionActivity.recordActivity(jti);
        } catch (Exception ignored) {
            // Never fail the request because of a last_seen write failure.
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void publishSecurityEvent(HttpServletRequest request, String businessId, String eventType, String reason) {
        try {
            auditEventPublisher.publishSynchronous(auditEventBuilder.builder(AuditEventCategory.SECURITY, eventType, AuditEventSeverity.WARN)
                    .businessId(businessId == null ? "unknown" : businessId)
                    .actor(null, AuditEventActorType.ANONYMOUS)
                    .ipAddress(ClientIpResolver.resolve(request))
                    .userAgent(trimToNull(request.getHeader("User-Agent")))
                    .source("api")
                    .reason(reason)
                    .build());
        } catch (Exception ignored) {
            // Never fail the request because of an audit write failure.
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean authenticateSuperAdmin(Claims claims, HttpServletResponse response) throws IOException {
        String id = claims.getSubject();
        if (id == null || id.isBlank()) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Invalid token claims", "unauthorized");
            return false;
        }
        SuperAdmin admin = superAdminRepository.findById(id).orElse(null);
        if (admin == null || !admin.isActive()) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Account is not active", "unauthorized");
            return false;
        }
        if (admin.getLockedUntil() != null && admin.getLockedUntil().isAfter(Instant.now())) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Account is temporarily locked", "unauthorized");
            return false;
        }
        var authentication = new UsernamePasswordAuthenticationToken(
                admin.getId(),
                "",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return true;
    }

    private boolean authenticateSupplier(Claims claims, HttpServletResponse response) throws IOException {
        String id = claims.getSubject();
        String marketplaceSupplierId = claims.get(JwtTokenService.CLAIM_MARKETPLACE_SUPPLIER_ID, String.class);
        String roleKey = claims.get(JwtTokenService.CLAIM_SUPPLIER_ROLE, String.class);
        if (id == null || id.isBlank() || marketplaceSupplierId == null || marketplaceSupplierId.isBlank()) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Invalid token claims", "unauthorized");
            return false;
        }
        SupplierUser user = supplierUserRepository.findByIdAndMarketplaceSupplierId(id, marketplaceSupplierId).orElse(null);
        if (user == null || !user.isActive()) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Account is not active", "unauthorized");
            return false;
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Account is temporarily locked", "unauthorized");
            return false;
        }
        String resolvedRole = roleKey == null || roleKey.isBlank() ? user.getRoleKey() : roleKey;
        var principal = new SupplierPrincipal(user.getId(), marketplaceSupplierId, resolvedRole);
        var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_SUPPLIER"));
        zelisline.ub.marketplace.domain.SupplierUserRoles.permissionsFor(resolvedRole).stream()
                .map(p -> new SimpleGrantedAuthority("PERM_" + p))
                .forEach(authorities::add);
        var authentication = new SupplierAuthenticationToken(principal, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return true;
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String title, String slug)
            throws IOException {
        writeProblem(response, status, title, slug, null);
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String title, String slug, String code)
            throws IOException {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("type", URI.create(PROBLEM_BASE + slug).toString());
        body.put("title", title);
        body.put("status", status.value());
        if (code != null) {
            body.put("code", code);
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
