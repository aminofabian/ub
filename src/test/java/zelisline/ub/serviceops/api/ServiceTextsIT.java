package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.infrastructure.SmsMessagingClient;
import zelisline.ub.payroll.domain.StaffProfile;
import zelisline.ub.posdraft.application.ServiceReminderService;

/** Booking reminders and the earnings text: sent once, to the right number, through the shop's SMS. */
@TestPropertySource(properties = "app.services.reminders.min-age-minutes=-5")
class ServiceTextsIT extends ServiceSaleTestSupport {

    @MockitoBean
    private SmsMessagingClient smsMessagingClient;
    @MockitoBean
    private BusinessCreditMessagingSettingsService messagingSettingsService;
    @Autowired
    private ServiceReminderService reminderService;

    private TenantMessagingConfig config;

    @BeforeEach
    void smsIsSetUp() {
        config = mock(TenantMessagingConfig.class);
        when(config.enabled()).thenReturn(true);
        when(config.smsConfigured()).thenReturn(true);
        when(messagingSettingsService.resolveForDispatch(anyString(), any())).thenReturn(config);
        when(smsMessagingClient.sendText(any(), anyString(), anyString()))
                .thenReturn(new SmsMessagingClient.SendResult(true, false, false, "sms", "ok", "m1"));
    }

    @Test
    void aReminderIsSentOnceAndOnlyWhenAskedFor() throws Exception {
        Instant soon = Instant.now().plus(60, ChronoUnit.MINUTES);
        String wanted = book(soon, true, "0700990003");
        book(soon.minus(40, ChronoUnit.MINUTES), false, "0700990004");
        // Too far ahead to remind yet.
        book(Instant.now().plus(2, ChronoUnit.DAYS), true, "0700990005");

        assertThat(reminderService.sendDue()).isEqualTo(1);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(smsMessagingClient, times(1)).sendText(eq(config), eq("+254700990003"), text.capture());
        assertThat(text.getValue()).contains("Hi Wanjiru").contains("with John")
                // "today" unless the test runs within an hour of midnight in Nairobi.
                .containsPattern("(today|on \\w+ \\d+ \\w+) at \\d");
        assertThat(posDraftRepository.findById(wanted).orElseThrow().getReminderSentAt()).isNotNull();

        // The next sweep has nothing to do.
        assertThat(reminderService.sendDue()).isZero();
        verify(smsMessagingClient, times(1)).sendText(any(), anyString(), anyString());

        // Moved to another time: reminded again for the new time.
        mockMvc.perform(as(post("/api/v1/service-tickets/" + wanted + "/reschedule"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"start\":\"" + soon.plus(20, ChronoUnit.MINUTES) + "\"}"))
                .andExpect(status().isOk());
        assertThat(reminderService.sendDue()).isEqualTo(1);

        // Arrived or cancelled bookings are not texted.
        String peter = createStaff(TENANT, owner, "Peter");
        String arrived = bookWith(peter, soon, true, "0700990006");
        mockMvc.perform(as(post("/api/v1/service-tickets/" + arrived + "/arrive"), TENANT, cashier, ROLE_CASHIER)
                        .content("{}"))
                .andExpect(status().isOk());
        assertThat(reminderService.sendDue()).isZero();
    }

    @Test
    void aShopWithoutSmsIsSkippedAndTheReminderIsNotUsedUp() throws Exception {
        when(config.smsConfigured()).thenReturn(false);
        String id = book(Instant.now().plus(60, ChronoUnit.MINUTES), true, "0700990003");
        assertThat(reminderService.sendDue()).isZero();
        verify(smsMessagingClient, never()).sendText(any(), anyString(), anyString());
        assertThat(posDraftRepository.findById(id).orElseThrow().getReminderSentAt()).isNull();
    }

    @Test
    void theEarningsTextGoesToTheStaffMembersOwnNumber() throws Exception {
        // No number on file: refused, nothing sent.
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/earnings-sms"), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isBadRequest());
        StaffProfile profile = staffProfileRepository.findById(john).orElseThrow();
        profile.setPhone("0711000111");
        staffProfileRepository.save(profile);
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + john), TENANT, owner, ROLE_OWNER)
                        .content("{\"type\":\"PERCENT\",\"rate\":40}"))
                .andExpect(status().isOk());
        postSale("k-" + UUID.randomUUID(), serviceLine(haircut, john, 1, "300"), "300", 201);

        MvcResult result = mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/earnings-sms"),
                        TENANT, owner, ROLE_OWNER))
                .andExpect(status().isOk()).andReturn();
        JsonNode sent = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(sent.get("sent").asBoolean()).isTrue();
        assertThat(sent.get("phoneMasked").asText()).endsWith("111").doesNotContain("254711000");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(smsMessagingClient).sendText(eq(config), eq("+254711000111"), text.capture());
        assertThat(text.getValue()).contains("John, today you earned KES 120 commission").contains("owed KES 120");

        // A seller cannot text earnings, and another business cannot reach this staff member.
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/earnings-sms"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/earnings-sms"), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isNotFound());
        verify(smsMessagingClient, times(1)).sendText(any(), anyString(), anyString());
    }

    private String book(Instant start, boolean remind, String phone) throws Exception {
        return bookWith(john, start, remind, phone);
    }

    private String bookWith(String staff, Instant start, boolean remind, String phone) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/appointments"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"staffProfileId\":\"" + staff + "\",\"start\":\"" + start
                                + "\",\"name\":\"Wanjiru Kamau\",\"phone\":\"" + phone + "\",\"sendReminder\":" + remind
                                + ",\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
