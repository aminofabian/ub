package zelisline.ub.configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import zelisline.ub.desktop.license.DesktopLicenseReadOnlyFilter;
import zelisline.ub.tenancy.infrastructure.DesktopTenantResolverFilter;

/**
 * Web + security wiring activated only when {@code spring.profiles.active=desktop}.
 *
 * <p>The desktop SKU (see {@code DESKTOP_INSTALLATION.md}) ships the fat JAR with
 * the Next.js static export bundled under {@code classpath:/static/}. Two things
 * have to happen that the cloud profile would never want:
 *
 * <ol>
 *   <li><b>Permit static UI paths.</b> The cloud {@code SecurityConfig} ends
 *       with {@code .anyRequest().denyAll()}, so {@code /}, {@code /login/},
 *       {@code /_next/static/**}, etc. would 403 without an override. We add a
 *       higher-precedence {@link SecurityFilterChain} that matches anything
 *       <em>not</em> in the backend's HTTP surface and permits it
 *       unconditionally. The login JWT / API-key / CORS plumbing in the cloud
 *       chain still applies to {@code /api/**}.</li>
 *   <li><b>SPA fallback.</b> Hard refreshes on client-side routes like
 *       {@code /customers/abc123} would 404 because no static file exists at
 *       that path. The custom {@link PathResourceResolver} below returns
 *       {@code /index.html} as a last resort, after which the client router
 *       takes over.</li>
 * </ol>
 *
 * <p>This class is intentionally additive — it never edits the cloud
 * {@code SecurityConfig}, so toggling the {@code desktop} profile off restores
 * cloud behaviour exactly.
 */
@Configuration
@Profile("desktop")
public class DesktopWebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(
        DesktopWebConfig.class
    );

    /**
     * Path prefixes owned by the backend HTTP surface. The desktop UI chain
     * deliberately does <em>not</em> match these so the cloud security chain
     * keeps applying its JWT / API-key / rate-limit logic to them.
     */
    private static final List<String> BACKEND_PATH_PATTERNS = List.of(
        "/api/**",
        "/webhooks/**",
        "/actuator/**",
        "/v3/api-docs/**",
        "/swagger-ui",
        "/swagger-ui/**",
        "/swagger-ui.html"
    );

    /**
     * Filesystem root for the {@code LocalMediaStore}. Resolved at startup so
     * the resource handler binds to a real, absolute path even when the
     * directory does not exist yet on a fresh install — Spring's
     * {@code FileSystemResource} returns 404 for missing files, which is the
     * right behaviour.
     */
    private final Path localMediaRoot;

    /** URL prefix that browser clients fetch local media under (default {@code /media}). */
    private final String localMediaPublicBase;

    public DesktopWebConfig(
        @Value(
            "${app.media.local.dir:${user.home}/.palmart/media}"
        ) String localMediaDir,
        @Value(
            "${app.media.local.public-base:/media}"
        ) String localMediaPublicBase
    ) {
        this.localMediaRoot = Path.of(localMediaDir)
            .toAbsolutePath()
            .normalize();
        String base = localMediaPublicBase.isBlank()
            ? "/media"
            : localMediaPublicBase;
        if (!base.startsWith("/")) {
            base = "/" + base;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.localMediaPublicBase = base;
    }

    /**
     * Explicitly register the desktop tenant filter at highest precedence so it
     * runs <em>before</em> Spring Security's filter chain (which is registered
     * at order {@code -100}). Providing a {@link FilterRegistrationBean}
     * suppresses Spring Boot's auto-registration of the same filter bean, so
     * the filter is wired exactly once.
     *
     * <p>The filter pre-seeds {@code tenant.businessId} on every request; the
     * cloud {@code DomainBusinessResolverFilter} (still in the security chain)
     * sees a loopback request and short-circuits without touching it.
     */
    @Bean
    public FilterRegistrationBean<
        DesktopTenantResolverFilter
    > desktopTenantResolverFilterRegistration(
        DesktopTenantResolverFilter filter
    ) {
        FilterRegistrationBean<DesktopTenantResolverFilter> reg =
            new FilterRegistrationBean<>(filter);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }

    /**
     * Reject mutating {@code /api/**} calls when the license is read-only
     * (§10). Runs immediately after the tenant resolver.
     */
    @Bean
    public FilterRegistrationBean<
        DesktopLicenseReadOnlyFilter
    > desktopLicenseReadOnlyFilterRegistration(
        DesktopLicenseReadOnlyFilter filter
    ) {
        FilterRegistrationBean<DesktopLicenseReadOnlyFilter> reg =
            new FilterRegistrationBean<>(filter);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        reg.addUrlPatterns("/api/*");
        return reg;
    }

    /**
     * Path patterns that the desktop chain permits even though they live under
     * {@code /api/**}. The first-run wizard's setup endpoints (§9 of
     * {@code DESKTOP_INSTALLATION.md}) must be reachable before any user exists,
     * so the cloud chain's JWT filter would 401 every call.
     *
     * <p>The endpoints are idempotent on the service side ({@code 409} after
     * setup completes), so leaving them open after first run is safe — there
     * is no enumeration risk because the only data they expose is the
     * configured {@code app.desktop.business-id}, which is per-install.
     */
    private static final List<String> DESKTOP_PUBLIC_API_PATTERNS = List.of(
        "/api/v1/desktop/setup",
        "/api/v1/desktop/setup/**",
        // License status is polled by the shell + frontend on every page load
        // before a user logs in, so it must be unauthenticated.
        "/api/v1/license/status"
    );

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain desktopUiSecurityChain(HttpSecurity http)
        throws Exception {
        // Spring Security 7 deprecated the string-based AntPathRequestMatcher
        // constructor; PathPatternRequestMatcher.withDefaults() is the canonical
        // builder for path-pattern matching against servlet requests.
        PathPatternRequestMatcher.Builder pp =
            PathPatternRequestMatcher.withDefaults();
        RequestMatcher uiMatcher = new NegatedRequestMatcher(
            new OrRequestMatcher(
                BACKEND_PATH_PATTERNS.stream()
                    .map(pp::matcher)
                    .map(RequestMatcher.class::cast)
                    .toList()
            )
        );
        RequestMatcher desktopPublicApiMatcher = new OrRequestMatcher(
            DESKTOP_PUBLIC_API_PATTERNS.stream()
                .map(pp::matcher)
                .map(RequestMatcher.class::cast)
                .toList()
        );
        RequestMatcher combined = new OrRequestMatcher(
            uiMatcher,
            desktopPublicApiMatcher
        );

        http.securityMatcher(combined)
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(s ->
                s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // /media/** must be registered BEFORE the catch-all /** so it wins the
        // path-pattern race. Maps to the {@code LocalMediaStore} root on disk;
        // missing files return 404 (the SPA shell takes over only for non-media
        // paths via the resolver chain on /**).
        try {
            Files.createDirectories(localMediaRoot);
        } catch (IOException e) {
            log.warn(
                "[Desktop] could not pre-create local media root {} ({}). The directory will be created on first upload.",
                localMediaRoot,
                e.getMessage()
            );
        }
        String mediaPattern = localMediaPublicBase + "/**";
        registry
            .addResourceHandler(mediaPattern)
            .addResourceLocations("file:" + localMediaRoot.toString() + "/")
            .setCachePeriod(60 * 60 * 24);
        log.info("[Desktop] serving {} from {}", mediaPattern, localMediaRoot);

        // Map everything else to classpath:/static/ (where copyDesktopUi drops
        // the Next.js export). The custom resolver below adds SPA fallback.
        registry
            .addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            // Long-lived cache for /_next/static/** is safe because the
            // chunks are content-hashed; the rest is short-lived.
            .setCachePeriod(60 * 5)
            .resourceChain(true)
            .addResolver(new SpaFallbackResolver());
    }

    /**
     * Resolution order for a request like {@code /customers/abc-123}:
     * <ol>
     *   <li>Backend paths ({@code /api/**} etc.) → return {@code null} so
     *       Spring falls through to the cloud {@code @RestController}s
     *       (or 404 if there is no matching controller).</li>
     *   <li>Try the literal path: {@code /customers/abc-123} → maps to
     *       {@code classpath:/static/customers/abc-123} (only matches if it's a
     *       real file, which it almost never is).</li>
     *   <li>Try {@code /customers/abc-123/index.html} (Next.js writes routes
     *       as directories when {@code trailingSlash: true}).</li>
     *   <li>Try {@code /customers/abc-123.html}.</li>
     *   <li>Fall back to {@code /index.html} — the SPA shell takes over and
     *       the client router renders the right page.</li>
     * </ol>
     */
    static final class SpaFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location)
            throws IOException {
            if (isBackendPath(resourcePath)) {
                return null;
            }

            Resource direct = super.getResource(resourcePath, location);
            if (direct != null && direct.exists() && direct.isReadable()) {
                return direct;
            }

            String trimmed = resourcePath.endsWith("/")
                ? resourcePath.substring(0, resourcePath.length() - 1)
                : resourcePath;

            Resource asDir = super.getResource(
                trimmed + "/index.html",
                location
            );
            if (asDir != null && asDir.exists() && asDir.isReadable()) {
                return asDir;
            }

            Resource asHtml = super.getResource(trimmed + ".html", location);
            if (asHtml != null && asHtml.exists() && asHtml.isReadable()) {
                return asHtml;
            }

            // Last resort — the SPA shell. The client router will read
            // window.location and render the matching page.
            return super.getResource("index.html", location);
        }

        private static boolean isBackendPath(String resourcePath) {
            // PathResourceResolver strips the leading slash before invoking
            // this method, so we match against the prefix without it.
            return (
                resourcePath.startsWith("api/") ||
                resourcePath.startsWith("webhooks/") ||
                resourcePath.startsWith("actuator/") ||
                resourcePath.startsWith("v3/api-docs") ||
                resourcePath.startsWith("swagger-ui")
            );
        }
    }
}
