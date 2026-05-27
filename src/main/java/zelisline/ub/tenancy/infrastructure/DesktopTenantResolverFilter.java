package zelisline.ub.tenancy.infrastructure;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Single-tenant tenant resolver for the desktop / on-premise SKU
 * (see {@code DESKTOP_INSTALLATION.md} §5.2).
 *
 * <p>The cloud build resolves the tenant from the request {@code Host} header via
 * {@link DomainBusinessResolverFilter}; on a desktop install there is exactly one
 * {@code Business} per machine and the loopback host ({@code 127.0.0.1}) has no
 * domain mapping. This filter runs <em>before</em> the host-based resolver and
 * pre-seeds {@link TenantRequestAttributes#BUSINESS_ID} with the value of
 * {@code app.desktop.business-id} (env var {@code APP_DESKTOP_BUSINESS_ID}).
 * The host-based filter then sees a loopback request and short-circuits without
 * touching the attribute, so the pre-set value flows through to controllers via
 * {@code TenantRequestIds.resolveBusinessId()}.
 *
 * <p>This filter is the <em>only</em> place the {@code app.desktop.business-id}
 * environment variable is read. Other code paths continue to use
 * {@code TenantRequestIds} as in the cloud build, so flipping the desktop
 * profile off restores cloud behaviour without any controller changes.
 *
 * <h2>First-run safety</h2>
 * <p>On a fresh install the {@code business} row does not exist yet — the
 * first-run wizard (§9, lands in step 3) creates it. Until then, controllers
 * that try to load the business by ID will throw 404, which is the same
 * behaviour cloud surfaces for a bad tenant. We deliberately do not validate
 * the UUID here: it would couple the filter to repository state and require
 * special-case bypass for {@code /api/v1/auth/desktop/setup} endpoints we have
 * not built yet.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "app.desktop.single-tenant", havingValue = "true")
public class DesktopTenantResolverFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DesktopTenantResolverFilter.class);

    private final String businessId;

    public DesktopTenantResolverFilter(
            @Value("${app.desktop.business-id:}") String businessId) {
        this.businessId = businessId == null ? "" : businessId.trim();
        if (this.businessId.isEmpty()) {
            log.warn(
                    "[DesktopTenant] app.desktop.business-id is blank. Every request will run "
                            + "without a tenant attribute — controllers will reject most calls. "
                            + "Set APP_DESKTOP_BUSINESS_ID before going live.");
        } else {
            log.info("[DesktopTenant] single-tenant mode active. business-id={}", this.businessId);
        }
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (!businessId.isEmpty()
                && request.getAttribute(TenantRequestAttributes.BUSINESS_ID) == null) {
            // Set only when no upstream resolver has already provided one. This
            // lets a developer override the tenant per-request via X-Tenant-Host
            // during integration tests without ripping the filter out.
            request.setAttribute(TenantRequestAttributes.BUSINESS_ID, businessId);
        }
        filterChain.doFilter(request, response);
    }
}
