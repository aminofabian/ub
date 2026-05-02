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
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.identity.repository.UserSessionRepository;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Validates {@code Authorization: Bearer} access tokens: tenant JWTs require host
 * tenant alignment and an active {@code user_sessions} row; super-admin JWTs are
 * stateless (PHASE_1_PLAN.md §3.3).
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PROBLEM_BASE = "urn:problem:";

    private final JwtTokenService jwtTokenService;
    private final UserSessionRepository userSessionRepository;
    private final UserRepository userRepository;
    private final SuperAdminRepository superAdminRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        final Claims claims;
        try {
            claims = jwtTokenService.parseAndValidate(token);
        } catch (JwtException | IllegalArgumentException ex) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Invalid or expired access token", "unauthorized");
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
            writeProblem(response, HttpStatus.FORBIDDEN,
                    "Token tenant does not match resolved host tenant", "forbidden");
            return;
        }

        String jti = claims.getId();
        if (jti == null || jti.isBlank()
                || userSessionRepository.findByAccessTokenJtiAndRevokedAtIsNull(jti).isEmpty()) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Session is no longer active", "unauthorized");
            return;
        }

        String userId = claims.getSubject();
        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, resolvedTenant).orElse(null);
        if (user == null || user.getDeletedAt() != null) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "User not found", "unauthorized");
            return;
        }
        if (user.statusAsEnum() != UserStatus.ACTIVE) {
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Account is not active", "unauthorized");
            return;
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
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
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
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

    private void writeProblem(HttpServletResponse response, HttpStatus status, String title, String slug)
            throws IOException {
        ProblemDetail body = ProblemDetail.forStatus(status.value());
        body.setTitle(title);
        body.setType(URI.create(PROBLEM_BASE + slug));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
