package zelisline.ub.messaging.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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

import zelisline.ub.credits.domain.BusinessCreditSettings;
import zelisline.ub.credits.repository.BusinessCreditSettingsRepository;
import zelisline.ub.storefront.WebOrderCodes;
import zelisline.ub.storefront.WebOrderFulfillmentStatuses;
import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * V2 slice (scope §19): merchant WhatsApp replies ({@code CONFIRM <code>}) drive
 * fulfillment status through the real Meta webhook endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class WhatsAppOrderReplyIT {

    private static final String TENANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String SLUG = "wa-reply-it";
    private static final String PHONE_NUMBER_ID = "phone-id-12345";
    private static final String SHOP_DIGITS = "254712345678";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private BusinessCreditSettingsRepository creditSettingsRepository;

    @Autowired
    private WebOrderRepository webOrderRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String branchId;
    private String orderId;

    @BeforeEach
    void seed() {
        creditSettingsRepository.deleteAll();
        webOrderRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Reply Shop");
        b.setSlug(SLUG);
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        br.setActive(true);
        branchId = branchRepository.save(br).getId();

        b.setSettings(
                "{\"storefront\":{\"enabled\":true,\"catalogBranchId\":\"%s\",\"whatsappCheckout\":"
                        + "{\"number\":\"0712 345 678\",\"mode\":\"fallback\"}}}"
                        .formatted(branchId));
        businessRepository.save(b);

        BusinessCreditSettings credit = new BusinessCreditSettings();
        credit.setBusinessId(TENANT);
        credit.setWhatsappMetaPhoneNumberId(PHONE_NUMBER_ID);
        creditSettingsRepository.save(credit);

        WebOrder order = new WebOrder();
        order.setBusinessId(TENANT);
        order.setCartId(UUID.randomUUID().toString());
        order.setCatalogBranchId(branchId);
        order.setStatus(WebOrderStatuses.PENDING_PAYMENT);
        order.setChannel("WHATSAPP");
        order.setFulfillmentStatus(WebOrderFulfillmentStatuses.AWAITING_CONFIRMATION);
        order.setCurrency("KES");
        order.setGrandTotal(new BigDecimal("1250.00"));
        order.setCustomerName("Wanjiku");
        order.setCustomerPhone("0711222333");
        webOrderRepository.save(order);
        orderId = order.getId();
    }

    @Test
    void confirmCommand_advancesOrder() throws Exception {
        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType("application/json")
                        .content(payload("CONFIRM " + WebOrderCodes.code(orderId), SHOP_DIGITS)))
                .andExpect(status().isOk());

        WebOrder reloaded = webOrderRepository.findById(orderId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getFulfillmentStatus())
                .isEqualTo(WebOrderFulfillmentStatuses.CONFIRMED);
    }

    @Test
    void unknownCode_ignored() throws Exception {
        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType("application/json")
                        .content(payload("CONFIRM 00000000", SHOP_DIGITS)))
                .andExpect(status().isOk());

        WebOrder reloaded = webOrderRepository.findById(orderId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getFulfillmentStatus())
                .isEqualTo(WebOrderFulfillmentStatuses.AWAITING_CONFIRMATION);
    }

    @Test
    void wrongSender_ignored() throws Exception {
        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType("application/json")
                        .content(payload("CONFIRM " + WebOrderCodes.code(orderId), "254799999999")))
                .andExpect(status().isOk());

        WebOrder reloaded = webOrderRepository.findById(orderId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getFulfillmentStatus())
                .isEqualTo(WebOrderFulfillmentStatuses.AWAITING_CONFIRMATION);
    }

    @Test
    void plainText_notACommand_ignored() throws Exception {
        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType("application/json")
                        .content(payload("Hi, are you open today?", SHOP_DIGITS)))
                .andExpect(status().isOk());

        WebOrder reloaded = webOrderRepository.findById(orderId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getFulfillmentStatus())
                .isEqualTo(WebOrderFulfillmentStatuses.AWAITING_CONFIRMATION);
    }

    private static String payload(String body, String from) {
        return """
                {
                  "object": "whatsapp_business_account",
                  "entry": [{
                    "id": "waba-1",
                    "changes": [{
                      "value": {
                        "messaging_product": "whatsapp",
                        "metadata": {"display_phone_number": "0712345678", "phone_number_id": "%s"},
                        "contacts": [{"profile": {"name": "Merchant"}, "wa_id": "%s"}],
                        "messages": [{
                          "from": "%s",
                          "id": "wamid.test",
                          "timestamp": "1730000000",
                          "type": "text",
                          "text": {"body": "%s"}
                        }]
                      }
                    }]
                  }]
                }
                """.formatted(PHONE_NUMBER_ID, from, from, body);
    }
}
