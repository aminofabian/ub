package zelisline.ub.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import zelisline.ub.identity.application.GoogleOAuthService;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.identity.domain.OAuthLoginState;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserOAuthIdentity;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.OAuthLoginStateRepository;
import zelisline.ub.identity.repository.UserOAuthIdentityRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.platform.api.dto.UpdatePlatformIntegrationsRequest;
import zelisline.ub.platform.application.PlatformIntegrationSettingsService;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.security.login-rate-limit-per-minute=50",
        "app.public.frontend-base-url=http://localhost:3000"
})
class GoogleOAuthIT {

    private static final String CLIENT_ID = "999790256759-test.apps.googleusercontent.com";
    private static final String CLIENT_SECRET = "GOCSPX-test-secret-value";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlatformIntegrationSettingsService platformIntegrationSettingsService;

    @Autowired
    private OAuthLoginStateRepository oauthLoginStateRepository;

    @Autowired
    private UserOAuthIdentityRepository userOAuthIdentityRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BusinessRepository businessRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @BeforeEach
    void resetGoogle() {
        oauthLoginStateRepository.deleteAll();
        userOAuthIdentityRepository.deleteAll();
        platformIntegrationSettingsService.update(googleUpdate(false, "", ""));
    }

    @Test
    void publicConfig_disabledWhenNotConfigured() throws Exception {
        mockMvc.perform(get("/api/v1/public/auth/oauth/google"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void publicConfig_enabledWhenReady() throws Exception {
        enableGoogle();
        mockMvc.perform(get("/api/v1/public/auth/oauth/google"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void start_returns503WhenDisabled() throws Exception {
        mockMvc.perform(post("/api/v1/auth/oauth/google/start")
                        .contentType(APPLICATION_JSON)
                        .content("{\"intent\":\"sign_in\",\"next\":\"/overview\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void start_returnsAuthorizeUrlAndPersistsState() throws Exception {
        enableGoogle();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/oauth/google/start")
                        .contentType(APPLICATION_JSON)
                        .content("{\"intent\":\"sign_in\",\"next\":\"/overview\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUrl").exists())
                .andReturn();

        String url = result.getResponse().getContentAsString();
        assertThat(url).contains(CLIENT_ID);
        assertThat(url).contains("redirect_uri");
        assertThat(url).contains("code_challenge");
        assertThat(result.getResponse().getHeaders("Set-Cookie").stream()
                        .anyMatch(h -> h.startsWith("ub.oauth_bind=")))
                .isTrue();
        assertThat(oauthLoginStateRepository.findAll()).hasSize(1);
        OAuthLoginState state = oauthLoginStateRepository.findAll().get(0);
        assertThat(state.getCodeVerifier()).isNotBlank();
        assertThat(state.getRedirectUri())
                .isEqualTo("http://localhost:3000/api/v1/auth/oauth/google/callback");
    }

    @Test
    void callback_invalidState_redirectsWithError() throws Exception {
        enableGoogle();
        MvcResult result = mockMvc.perform(get("/api/v1/auth/oauth/google/callback")
                        .param("code", "fake-code")
                        .param("state", "unknown-state"))
                .andExpect(status().isFound())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        assertThat(location).contains("googleError=invalid_state");
    }

    @Test
    void callback_expiredState_redirectsWithError() throws Exception {
        enableGoogle();
        OAuthLoginState row = new OAuthLoginState();
        row.setStateHash("deadbeef".repeat(8));
        row.setCodeVerifier("verifier");
        row.setIntent(OAuthLoginState.INTENT_SIGN_IN);
        row.setNextPath("/");
        row.setBrowserBinding("bind");
        row.setNonce("nonce");
        row.setRedirectUri("http://localhost:3000/api/v1/auth/oauth/google/callback");
        row.setExpiresAt(Instant.now().minusSeconds(60));
        oauthLoginStateRepository.save(row);

        MvcResult result = mockMvc.perform(get("/api/v1/auth/oauth/google/callback")
                        .param("code", "fake-code")
                        .param("state", "raw-state-not-matching-hash")
                        .cookie(new jakarta.servlet.http.Cookie("ub.oauth_bind", "bind")))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(result.getResponse().getHeader("Location")).contains("googleError=");
    }

    @Test
    void exchange_invalidState_returnsBadRequestCode() throws Exception {
        enableGoogle();
        mockMvc.perform(post("/api/v1/auth/oauth/google/exchange")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"fake-code\",\"state\":\"unknown-state\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("invalid_state"));
    }

    @Test
    void exchange_disabled_isReachableWithoutAccessToken() throws Exception {
        // No Authorization header: proves the route is public (JWT filter bypassed),
        // and that a disabled platform fails closed with an ops-safe code.
        mockMvc.perform(post("/api/v1/auth/oauth/google/exchange")
                        .contentType(APPLICATION_JSON)
                        .content("{\"code\":\"fake-code\",\"state\":\"fake-state\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("disabled"));
    }

    @Test
    void shopPick_listsStaffShopsThenSignsIntoTheChosenOne() throws Exception {
        String email = "multi-shop-" + UUID.randomUUID() + "@example.com";
        Business alpha = shop("alpha-" + UUID.randomUUID().toString().substring(0, 8), "Alpha Mart");
        Business beta = shop("beta-" + UUID.randomUUID().toString().substring(0, 8), "Beta Mart");
        staffMember(alpha.getId(), email);
        staffMember(beta.getId(), email);
        String token = "pick-" + UUID.randomUUID();
        String binding = "bind-" + UUID.randomUUID();
        saveShopPick(token, binding, email);

        mockMvc.perform(get("/api/v1/auth/oauth/google/shops")
                        .cookie(shopPickCookies(token, binding)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.destinations[?(@.slug == '" + alpha.getSlug() + "')]").exists())
                .andExpect(jsonPath("$.destinations[?(@.slug == '" + beta.getSlug() + "')]").exists())
                .andExpect(jsonPath("$.destinations[?(@.door == 'SHOPPER')]").doesNotExist());

        mockMvc.perform(post("/api/v1/auth/oauth/google/complete")
                        .contentType(APPLICATION_JSON)
                        .content("{\"slug\":\"" + alpha.getSlug() + "\"}")
                        .cookie(shopPickCookies(token, binding)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.slug").value(alpha.getSlug()));

        mockMvc.perform(post("/api/v1/auth/oauth/google/complete")
                        .contentType(APPLICATION_JSON)
                        .content("{\"slug\":\"" + beta.getSlug() + "\"}")
                        .cookie(shopPickCookies(token, binding)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("expired_state"));
    }

    @Test
    void shopPick_rejectsAShopTheGoogleAccountIsNotOn() throws Exception {
        String email = "one-shop-" + UUID.randomUUID() + "@example.com";
        Business alpha = shop("only-" + UUID.randomUUID().toString().substring(0, 8), "Only Mart");
        staffMember(alpha.getId(), email);
        String token = "pick-" + UUID.randomUUID();
        String binding = "bind-" + UUID.randomUUID();
        saveShopPick(token, binding, email);

        mockMvc.perform(post("/api/v1/auth/oauth/google/complete")
                        .contentType(APPLICATION_JSON)
                        .content("{\"slug\":\"not-their-shop\"}")
                        .cookie(shopPickCookies(token, binding)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("unknown_shop"));

        mockMvc.perform(get("/api/v1/auth/oauth/google/shops")
                        .cookie(shopPickCookies(token, binding)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void shopPick_missingCookieExpires() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/google/shops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("expired_state"));
    }

    @Test
    void googleSecret_encryptRoundTrip() {
        enableGoogle();
        var resolved = platformIntegrationSettingsService.resolveGoogleOauth();
        assertThat(resolved.ready()).isTrue();
        assertThat(resolved.clientId()).isEqualTo(CLIENT_ID);
        assertThat(resolved.clientSecret()).isEqualTo(CLIENT_SECRET);
        assertThat(platformIntegrationSettingsService.getForSuperAdmin().hasGoogleOauthClientSecret())
                .isTrue();
    }

    @Test
    void oauthIdentity_canBePersistedAndDeletedByUserId() {
        String userId = UUID.randomUUID().toString();
        UserOAuthIdentity link = new UserOAuthIdentity();
        link.setId(UUID.randomUUID().toString());
        link.setBusinessId(UUID.randomUUID().toString());
        link.setUserId(userId);
        link.setProvider(UserOAuthIdentity.PROVIDER_GOOGLE);
        link.setProviderSubject("google-sub-1");
        link.setEmailAtLink("owner@example.com");
        link.setCreatedAt(Instant.now());
        link.setUpdatedAt(Instant.now());
        userOAuthIdentityRepository.save(link);

        assertThat(userOAuthIdentityRepository.findByUserId(userId)).hasSize(1);
        userOAuthIdentityRepository.deleteByUserId(userId);
        assertThat(userOAuthIdentityRepository.findByUserId(userId)).isEmpty();
    }

    private Business shop(String slug, String name) {
        Business business = new Business();
        business.setSlug(slug);
        business.setName(name);
        return businessRepository.save(business);
    }

    private void staffMember(String businessId, String email) {
        User user = new User();
        user.setBusinessId(businessId);
        user.setEmail(email);
        user.setName("Owner");
        user.setRoleId(UUID.randomUUID().toString());
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$placeholderplaceholderplaceholder");
        userRepository.save(user);
    }

    private void saveShopPick(String token, String binding, String email) {
        OAuthLoginState row = new OAuthLoginState();
        row.setStateHash(TokenHasher.sha256Hex(token));
        row.setCodeVerifier("shop-pick");
        row.setIntent(OAuthLoginState.INTENT_SHOP_PICK);
        row.setNextPath("/business");
        row.setBrowserBinding(binding);
        row.setNonce("nonce-nonce");
        row.setRedirectUri("http://localhost:3000/api/v1/auth/oauth/google/callback");
        row.setExpiresAt(Instant.now().plusSeconds(600));
        row.setOnboardDraftJson(
                "{\"email\":\"" + email + "\",\"subject\":\"google-sub-1\",\"name\":\"Owner\"}");
        oauthLoginStateRepository.save(row);
    }

    private static Cookie[] shopPickCookies(String token, String binding) {
        return new Cookie[] {
                new Cookie(GoogleOAuthService.SHOP_PICK_COOKIE, token),
                new Cookie(GoogleOAuthService.BIND_COOKIE, binding)
        };
    }

    private void enableGoogle() {
        platformIntegrationSettingsService.update(googleUpdate(true, CLIENT_ID, CLIENT_SECRET));
    }

    private static UpdatePlatformIntegrationsRequest googleUpdate(
            boolean enabled, String clientId, String clientSecret) {
        return new UpdatePlatformIntegrationsRequest(
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                enabled, clientId, clientSecret);
    }
}
