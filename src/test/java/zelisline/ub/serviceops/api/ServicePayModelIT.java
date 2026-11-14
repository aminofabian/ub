package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.identity.domain.User;
import zelisline.ub.serviceops.application.StaffEarningsService;

/**
 * The two ways a shop pays commission (ADR 0019): on the whole price, or after the service's
 * product cost. A 1,000 gel set that uses 300 of product, done by someone on 50%.
 */
class ServicePayModelIT extends ServiceSaleTestSupport {

    private static final String MODEL = "/api/v1/commissions/pay-model";

    @Autowired
    private StaffEarningsService staffEarningsService;

    private String gel;

    @BeforeEach
    void aGelSetThatUsesProduct() throws Exception {
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + john), TENANT, owner, ROLE_OWNER)
                        .content("{\"type\":\"PERCENT\",\"rate\":50}"))
                .andExpect(status().isOk());
        gel = createService(TENANT, owner,
                "{\"name\":\"Builder gel\",\"durationMinutes\":60,\"price\":1000,\"productCost\":300}");
    }

    @Test
    void theOwnerChoosesTheModelAndItStartsOnTheWholePrice() throws Exception {
        assertThat(model(TENANT, cashier, ROLE_CASHIER)).isEqualTo("GROSS");

        // Choosing it is a commission decision, and only the two models exist.
        setModel(cashier, ROLE_CASHIER, "NET_OF_PRODUCT", 403);
        setModel(owner, ROLE_OWNER, "HOURLY", 400);
        setModel(owner, ROLE_OWNER, "NET_OF_PRODUCT", 200);
        assertThat(model(TENANT, owner, ROLE_OWNER)).isEqualTo("NET_OF_PRODUCT");
        // One shop's choice is not another's.
        assertThat(model(OTHER, otherOwner, ROLE_OWNER)).isEqualTo("GROSS");

        setModel(owner, ROLE_OWNER, "GROSS", 200);
        assertThat(model(TENANT, owner, ROLE_OWNER)).isEqualTo("GROSS");
    }

    @Test
    void onTheWholePriceTheProductCostIsNotTakenOff() throws Exception {
        String id = open(gel);
        JsonNode preview = earnings(id);
        // Half of 1,000: the 300 of product comes out of the shop's half.
        assertThat(preview.get("commissionTotal").decimalValue()).isEqualByComparingTo("500");
        assertThat(preview.get("shopShare").decimalValue()).isEqualByComparingTo("500");
        pay(id, 1000);
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("500.00");
    }

    @Test
    void afterProductCostTheCostComesOffFirstAndStaysWithTheShop() throws Exception {
        setModel(owner, ROLE_OWNER, "NET_OF_PRODUCT", 200);
        String id = open(gel);
        JsonNode preview = earnings(id);
        // 1,000 less 300 of product is 700; half of that each way, and the shop also keeps the 300.
        assertThat(preview.get("commissionTotal").decimalValue()).isEqualByComparingTo("350");
        assertThat(preview.get("shopShare").decimalValue()).isEqualByComparingTo("650");
        assertThat(preview.get("lines").get(0).get("shares").get(0).get("rule").asText())
                .isEqualTo("PERCENT 50.00 (staff) after 300 product cost");
        pay(id, 1000);
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("350.00");
        assertBalanced(journalLineRepository.findAll());

        // A service with no product cost is paid on its whole price under either model.
        String cut = open(haircut);
        assertThat(earnings(cut).get("commissionTotal").decimalValue()).isEqualByComparingTo("150");

        // The cost can be changed or cleared on the service, in whole shillings, never negative.
        patchService("{\"productCost\":250.50}", 400);
        patchService("{\"productCost\":-1}", 400);
        assertThat(patchService("{\"productCost\":400}", 200).get("productCost").decimalValue()).isEqualByComparingTo("400");
        assertThat(earnings(open(gel)).get("commissionTotal").decimalValue()).isEqualByComparingTo("300");
        assertThat(patchService("{\"productCost\":0}", 200).get("productCost").isNull()).isTrue();
        assertThat(earnings(open(gel)).get("commissionTotal").decimalValue()).isEqualByComparingTo("500");
    }

    private String model(String tenant, User user, String role) throws Exception {
        return objectMapper.readTree(mockMvc.perform(as(get(MODEL), tenant, user, role))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("model").asText();
    }

    private void setModel(User user, String role, String model, int expected) throws Exception {
        mockMvc.perform(as(put(MODEL), TENANT, user, role).content("{\"model\":\"" + model + "\"}"))
                .andExpect(status().is(expected));
    }

    private JsonNode patchService(String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(patch("/api/v1/services/" + gel), TENANT, owner, ROLE_OWNER).content(body))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** A visit for one service, led by John. */
    private String open(String serviceId) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"lines\":[{\"serviceId\":\"" + serviceId + "\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode visit = objectMapper.readTree(result.getResponse().getContentAsString());
        String id = visit.get("id").asText();
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/lines/" + visit.get("lines").get(0).get("id").asText()
                        + "/team"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"members\":[{\"staffProfileId\":\"" + john + "\"}]}"))
                .andExpect(status().isOk());
        return id;
    }

    private JsonNode earnings(String id) throws Exception {
        return objectMapper.readTree(mockMvc.perform(as(get("/api/v1/service-tickets/" + id + "/earnings"), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void pay(String id, int cash) throws Exception {
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "pay-" + UUID.randomUUID())
                        .content("{\"payments\":[{\"method\":\"cash\",\"amount\":" + cash + "}]}"))
                .andExpect(status().isOk());
    }
}
