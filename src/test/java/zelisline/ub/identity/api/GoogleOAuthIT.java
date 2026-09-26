package zelisline.ub.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

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

import zelisline.ub.identity.domain.OAuthLoginState;
import zelisline.ub.identity.domain.UserOAuthIdentity;
import zelisline.ub.identity.repository.OAuthLoginStateRepository;
import zelisline.ub.identity.repository.UserOAuthIdentityRepository;
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
