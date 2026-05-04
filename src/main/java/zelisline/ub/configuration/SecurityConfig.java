package zelisline.ub.configuration;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import zelisline.ub.identity.application.ApiKeyAuthService;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.identity.repository.UserSessionRepository;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.platform.security.ApiKeyAuthenticationFilter;
import zelisline.ub.platform.security.ApiKeyRateLimitFilter;
import zelisline.ub.platform.security.ApiKeyRateLimiter;
import zelisline.ub.platform.security.InvalidApiKeyIpRateLimiter;
import zelisline.ub.platform.security.PublicCreditClaimRateLimitFilter;
import zelisline.ub.platform.security.PublicCreditClaimRateLimiter;
import zelisline.ub.platform.security.PublicStorefrontIpRateLimiter;
import zelisline.ub.platform.security.PublicStorefrontRateLimitFilter;
import zelisline.ub.platform.security.JwtAuthenticationFilter;
import zelisline.ub.platform.security.JwtTokenService;
import zelisline.ub.platform.security.LoginIpRateLimiter;
import zelisline.ub.platform.security.LoginRateLimitFilter;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.tenancy.infrastructure.DomainBusinessResolverFilter;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * HTTP security wiring for the Kiosk POS modular monolith.
 *
 * <p>Aligned with {@code docs/ARCHITECTURE_REVIEW.md} and {@code docs/README.md}:
 * stateless JWT auth, multi-tenant via JWT-driven RLS (set in a downstream filter),
 * and a small public surface (auth, health, OpenAPI in non-prod, payment webhooks).
 *
 * <p>Role layer mirrors the personas in the README: {@code SUPER_ADMIN} (SaaS
 * operator), {@code OWNER}/{@code ADMIN} (shop), {@code CASHIER} (POS), and
 * {@code SYNC_WORKER} (the local→cloud sync principal from §4.3 of the review).
 * Per-permission gating ("permissions as data") is delegated to {@code @PreAuthorize}
 * via {@link EnableMethodSecurity}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final List<String> allowedOrigins;
    private final List<String> allowedMethods;
    private final List<String> extraOriginPatterns;

    public SecurityConfig(
            @Value("${CORS_ALLOWED_ORIGINS:http://localhost:5173,http://localhost:3000}")
            List<String> allowedOrigins,
            @Value("${app.security.cors.allowed-methods:GET,POST,PUT,PATCH,DELETE,OPTIONS}")
            List<String> allowedMethods,
            @Value("${app.security.cors.extra-origin-patterns:https://*.palmart.co.ke,https://*.zelisline.com}")
            List<String> extraOriginPatterns
    ) {
        this.allowedOrigins = allowedOrigins;
        this.allowedMethods = allowedMethods;
        this.extraOriginPatterns = extraOriginPatterns.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            DomainBusinessResolverFilter domainBusinessResolverFilter,
            PublicStorefrontRateLimitFilter publicStorefrontRateLimitFilter,
            PublicCreditClaimRateLimitFilter publicCreditClaimRateLimitFilter,
            LoginRateLimitFilter loginRateLimitFilter,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
            ApiKeyRateLimitFilter apiKeyRateLimitFilter,
            ObjectProvider<TestAuthenticationFilter> testAuthenticationFilter
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> {
                    headers.frameOptions(frame -> frame.deny());
                    headers.contentTypeOptions(Customizer.withDefaults());
                    headers.referrerPolicy(referrer -> referrer.policy(
                            ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                    headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                            "default-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"));
                    headers.httpStrictTransportSecurity(hsts -> hsts.disable());
                })
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/super-admin/auth/login").permitAll()

                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info"
                        ).permitAll()

                        .requestMatchers(
                                "/api/v1/openapi",
                                "/api/v1/openapi/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()

                        // HMAC verification happens inside the controller, not in Spring Security.
                        .requestMatchers(HttpMethod.POST, "/webhooks/**").permitAll()

                        .requestMatchers("/api/v1/public/**").permitAll()

                        .requestMatchers("/api/v1/super-admin/**").hasRole("SUPER_ADMIN")

                        .requestMatchers("/api/v1/admin/**")
                            .hasAnyRole("OWNER", "ADMIN", "SUPER_ADMIN")

                        .requestMatchers("/api/v1/pos/**", "/api/v1/sales/**", "/api/v1/shifts/**")
                            .authenticated()

                        // Local→cloud outbox replay (hybrid profile). Service principal only.
                        .requestMatchers("/api/v1/sync/**").hasRole("SYNC_WORKER")

                        .requestMatchers("/api/**").authenticated()

                        .anyRequest().denyAll()
                );
        http.addFilterBefore(domainBusinessResolverFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(publicStorefrontRateLimitFilter, DomainBusinessResolverFilter.class);
        http.addFilterAfter(publicCreditClaimRateLimitFilter, PublicStorefrontRateLimitFilter.class);
        http.addFilterAfter(loginRateLimitFilter, PublicCreditClaimRateLimitFilter.class);
        http.addFilterAfter(jwtAuthenticationFilter, LoginRateLimitFilter.class);
        http.addFilterAfter(apiKeyAuthenticationFilter, JwtAuthenticationFilter.class);
        http.addFilterAfter(apiKeyRateLimitFilter, ApiKeyAuthenticationFilter.class);
        testAuthenticationFilter.ifAvailable(filter ->
                http.addFilterAfter(filter, ApiKeyRateLimitFilter.class));

        return http.build();
    }

    @Bean
    public PublicStorefrontRateLimitFilter publicStorefrontRateLimitFilter(
            PublicStorefrontIpRateLimiter publicStorefrontIpRateLimiter
    ) {
        return new PublicStorefrontRateLimitFilter(publicStorefrontIpRateLimiter);
    }

    @Bean
    public PublicCreditClaimRateLimitFilter publicCreditClaimRateLimitFilter(
            PublicCreditClaimRateLimiter publicCreditClaimRateLimiter
    ) {
        return new PublicCreditClaimRateLimitFilter(publicCreditClaimRateLimiter);
    }

    @Bean
    public LoginRateLimitFilter loginRateLimitFilter(LoginIpRateLimiter loginIpRateLimiter) {
        return new LoginRateLimitFilter(loginIpRateLimiter);
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenService jwtTokenService,
            UserSessionRepository userSessionRepository,
            UserRepository userRepository,
            SuperAdminRepository superAdminRepository
    ) {
        return new JwtAuthenticationFilter(
                jwtTokenService, userSessionRepository, userRepository, superAdminRepository);
    }

    @Bean
    public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
            ApiKeyAuthService apiKeyAuthService,
            InvalidApiKeyIpRateLimiter invalidApiKeyIpRateLimiter) {
        return new ApiKeyAuthenticationFilter(apiKeyAuthService, invalidApiKeyIpRateLimiter);
    }

    @Bean
    public ApiKeyRateLimitFilter apiKeyRateLimitFilter(ApiKeyRateLimiter apiKeyRateLimiter) {
        return new ApiKeyRateLimitFilter(apiKeyRateLimiter);
    }

    @Bean
    @ConditionalOnProperty(name = "app.security.test-auth.enabled", havingValue = "true")
    public TestAuthenticationFilter testAuthenticationFilter() {
        return new TestAuthenticationFilter();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        /*
         * Exact CORS_ALLOWED_ORIGINS alone blocks tenant subdomains (e.g. http://pal.localhost:3000)
         * and becomes easy to misconfigure: a single production URL in env replaces application.properties,
         * which drops all localhost origins and makes the browser fail fetch with a generic network error.
         */
        List<String> originPatterns = new ArrayList<>(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "http://*.localhost:*"));
        originPatterns.addAll(extraOriginPatterns);
        config.setAllowedOriginPatterns(originPatterns);
        config.setAllowedMethods(allowedMethods);
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "Idempotency-Key",
                "X-API-Key",
                "X-Request-Id",
                "X-Tenant-Id",
                "X-Tenant-Host",
                TestAuthenticationFilter.HEADER_USER_ID,
                TestAuthenticationFilter.HEADER_ROLE_ID
        ));
        config.setExposedHeaders(List.of(
                "X-Request-Id",
                "X-RateLimit-Limit",
                "X-RateLimit-Remaining",
                "X-RateLimit-Reset",
                "Location"
        ));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public DomainBusinessResolverFilter domainBusinessResolverFilter(
            DomainMappingRepository domainMappingRepository,
            zelisline.ub.tenancy.repository.BusinessRepository businessRepository,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            @Value("${app.tenancy.platform-hosts:}") List<String> platformHosts
    ) {
        return new DomainBusinessResolverFilter(
                domainMappingRepository, businessRepository, objectMapper, platformHosts);
    }

    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<DomainBusinessResolverFilter>
            disableDomainBusinessResolverFilterAutoRegistration(
                    DomainBusinessResolverFilter filter
            ) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration
    ) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
}
