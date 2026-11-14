package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.infrastructure.SmsMessagingClient;

/** A team member's sign-in is made for them and sent by email and text; the owner types no PIN. */
class ServiceTeamSignInIT extends ServiceSaleTestSupport {

    @MockitoBean
    private SmsMessagingClient smsMessagingClient;
    @MockitoBean
    private BusinessCreditMessagingSettingsService messagingSettingsService;

    private static final String ROLE_STAFF = "5a1e2222-2222-4222-8222-222222222203";

    private TenantMessagingConfig config;
    private String brian;

    @BeforeEach
    void aBarberAddedByNameOnly() throws Exception {
        config = mock(TenantMessagingConfig.class);
        when(config.enabled()).thenReturn(true);
        when(config.smsConfigured()).thenReturn(true);
        when(messagingSettingsService.resolveForDispatch(anyString(), any())).thenReturn(config);
        when(smsMessagingClient.sendText(any(), anyString(), anyString()))
                .thenReturn(new SmsMessagingClient.SendResult(true, false, false, "sms", "ok", "m1"));
        // Giving someone a sign-in makes them a user with the service staff role.
        role(ROLE_STAFF, "service_staff");
        permissionRepository.save(perm("5a1e1111-1111-4111-8111-111111111190", "users.create"));
        grant(ROLE_OWNER, "5a1e1111-1111-4111-8111-111111111190");
        brian = createStaff(TENANT, owner, "Brian Otieno");
    }

    @Test
    void thePinIsMadeForThemAndSentByTextAndEmail() throws Exception {
        JsonNode sent = send(brian, owner, ROLE_OWNER,
                "{\"email\":\"Brian@Example.test\",\"phone\":\"0700990003\"}", 200);
        String pin = sent.get("pin").asText();
        assertThat(pin).matches("\\d{4}");
        assertThat(sent.get("email").asText()).isEqualTo("brian@example.test");
        assertThat(sent.get("emailSent").asBoolean()).isTrue();
        assertThat(sent.get("smsSent").asBoolean()).isTrue();
        assertThat(sent.get("phoneMasked").asText()).endsWith("003").doesNotContain("0700990003");

        // The text goes to their number and carries what they need to sign in.
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(smsMessagingClient, times(1)).sendText(eq(config), eq("+254700990003"), text.capture());
        assertThat(text.getValue()).contains("Brian").contains("brian@example.test").contains(pin)
                .doesNotContain("Otieno");

        // Sending again makes a new PIN for the same sign-in; no email is needed this time.
        JsonNode again = send(brian, owner, ROLE_OWNER, "{}", 200);
        assertThat(again.get("email").asText()).isEqualTo("brian@example.test");
        assertThat(again.get("pin").asText()).matches("\\d{4}");
        verify(smsMessagingClient, times(2)).sendText(any(), anyString(), anyString());
    }

    @Test
    void itNeedsAnAddressTheFirstTimeAndWorksWithoutANumber() throws Exception {
        send(brian, owner, ROLE_OWNER, "{}", 400);
        send(brian, owner, ROLE_OWNER, "{\"email\":\"not-an-address\"}", 400);
        send(brian, owner, ROLE_OWNER, "{\"email\":\"brian@example.test\",\"phone\":\"12\"}", 400);

        JsonNode sent = send(brian, owner, ROLE_OWNER, "{\"email\":\"brian@example.test\"}", 200);
        assertThat(sent.get("emailSent").asBoolean()).isTrue();
        assertThat(sent.get("smsSent").asBoolean()).isFalse();
        assertThat(sent.get("phoneMasked").isNull()).isTrue();
        verify(smsMessagingClient, times(0)).sendText(any(), anyString(), anyString());
    }

    @Test
    void aTextThatFailsDoesNotUndoTheSignIn() throws Exception {
        when(smsMessagingClient.sendText(any(), anyString(), anyString())).thenThrow(new IllegalStateException("down"));
        JsonNode sent = send(brian, owner, ROLE_OWNER,
                "{\"email\":\"brian@example.test\",\"phone\":\"0700990003\"}", 200);
        assertThat(sent.get("smsSent").asBoolean()).isFalse();
        assertThat(sent.get("pin").asText()).matches("\\d{4}");
        // They can sign in: a second send finds them already set up.
        assertThat(send(brian, owner, ROLE_OWNER, "{}", 200).get("email").asText()).isEqualTo("brian@example.test");
    }

    @Test
    void onlySomeoneWhoAddsPeopleMaySendItAndOnlyInTheirOwnShop() throws Exception {
        send(brian, cashier, ROLE_CASHIER, "{\"email\":\"brian@example.test\"}", 403);
        mockMvc.perform(as(post("/api/v1/service-team/" + brian + "/send-sign-in"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"email\":\"brian@example.test\"}"))
                .andExpect(status().isNotFound());
        verify(smsMessagingClient, times(0)).sendText(any(), anyString(), anyString());
    }

    private JsonNode send(String staff, User user, String role, String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-team/" + staff + "/send-sign-in"), TENANT, user, role)
                        .content(body))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
