package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.pricing.api.dto.PostSellingPriceRequest;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.serviceops.application.StaffEarningsService;

/** Products bought on a visit: priced at the shelf price, paid with the services as one sale. */
class VisitProductsIT extends ServiceSaleTestSupport {

    @Autowired
    private PricingService pricingService;
    @Autowired
    private StaffEarningsService staffEarningsService;

    @BeforeEach
    void theOilCostsOneFifty() throws Exception {
        price("150");
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + john), TENANT, owner, ROLE_OWNER)
                        .content("{\"type\":\"PERCENT\",\"rate\":50}"))
                .andExpect(status().isOk());
    }

    @Test
    void aHaircutAndTwoBottlesOfOilAreOneSale() throws Exception {
        JsonNode visit = open();
        String id = visit.get("id").asText();
        String cut = visit.get("lines").get(0).get("id").asText();

        // The caller cannot name a price: only the product and how many.
        JsonNode one = product(id, "{\"itemId\":\"" + itemId + "\",\"quantity\":1,\"unitPrice\":1}", 200);
        assertThat(one.get("products")).hasSize(1);
        assertThat(one.get("products").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(one.get("total").decimalValue()).isEqualByComparingTo("450.00");
        // The same product again is more of it, not a second row.
        JsonNode two = product(id, "{\"itemId\":\"" + itemId + "\",\"quantity\":1}", 200);
        assertThat(two.get("products")).hasSize(1);
        assertThat(two.get("products").get(0).get("quantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(two.get("total").decimalValue()).isEqualByComparingTo("600.00");
        // The services are still the services: the product is not something to start or assign.
        assertThat(two.get("lines")).hasSize(1);
        String productLine = two.get("products").get(0).get("id").asText();
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/lines/" + productLine + "/start"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"performerStaffProfileId\":\"" + john + "\"}"))
                .andExpect(status().isNotFound());
        // Nothing has left the shelf yet.
        assertThat(inventoryBatchRepository.findAll().getFirst().getQuantityRemaining()).isEqualByComparingTo("10");

        // A product that does not exist, or belongs to another business, or a visit of another business.
        product(id, "{\"itemId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}", 400);
        product(id, "{\"itemId\":\"" + itemId + "\",\"quantity\":0}", 400);
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/products"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"itemId\":\"" + itemId + "\",\"quantity\":1}"))
                .andExpect(status().isNotFound());

        // The only service cannot be removed just because a product is also on the visit.
        mockMvc.perform(as(delete("/api/v1/service-tickets/" + id + "/lines/" + cut), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isBadRequest());

        assign(id, cut);
        JsonNode paid = pay(id, "600", 200);
        String saleId = paid.get("sale").get("id").asText();
        assertThat(paid.get("sale").get("grandTotal").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(saleItemRepository.findBySaleIdOrderByLineIndexAsc(saleId))
                .extracting(SaleItem::getLineKind).containsExactly("SERVICE", "ITEM");
        // Service revenue and product revenue stay apart; the oil left the shelf; John earns on the cut only.
        assertThat(accountBalance("4010")).isEqualByComparingTo("300.00");
        assertThat(inventoryBatchRepository.findAll().getFirst().getQuantityRemaining()).isEqualByComparingTo("8");
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("150.00");
        assertBalanced(journalLineRepository.findAll());
        assertThat(saleRepository.count()).isEqualTo(1);
    }

    @Test
    void aProductCanBeTakenOffAndAPriceChangeIsShownBeforeMoneyIsTaken() throws Exception {
        JsonNode visit = open();
        String id = visit.get("id").asText();
        assign(id, visit.get("lines").get(0).get("id").asText());
        String line = product(id, "{\"itemId\":\"" + itemId + "\",\"quantity\":1}", 200)
                .get("products").get(0).get("id").asText();

        // The shelf price goes up while the customer is in the chair.
        price("200");
        pay(id, "450", 409);
        assertThat(posDraftRepository.findById(id).orElseThrow().getGrandTotal()).isEqualByComparingTo("500.00");
        assertThat(posDraftRepository.findById(id).orElseThrow().getStatus()).isEqualTo("pending");
        assertThat(saleRepository.count()).isZero();

        // They change their mind about the oil.
        MvcResult removed = mockMvc.perform(as(delete("/api/v1/service-tickets/" + id + "/products/" + line), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn();
        JsonNode after = objectMapper.readTree(removed.getResponse().getContentAsString());
        assertThat(after.get("products")).isEmpty();
        assertThat(after.get("total").decimalValue()).isEqualByComparingTo("300.00");
        mockMvc.perform(as(delete("/api/v1/service-tickets/" + id + "/products/" + line), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isNotFound());
        pay(id, "300", 200);
        assertThat(inventoryBatchRepository.findAll().getFirst().getQuantityRemaining()).isEqualByComparingTo("10");
    }

    private void price(String amount) {
        pricingService.setSellingPrice(TENANT, new PostSellingPriceRequest(itemId, null, new BigDecimal(amount), java.time.LocalDate.now(java.time.ZoneOffset.UTC), null), owner.getId());
    }

    private JsonNode open() throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assign(String id, String lineId) throws Exception {
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/lines/" + lineId + "/assign"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"performerStaffProfileId\":\"" + john + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode product(String id, String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/products"), TENANT, cashier, ROLE_CASHIER).content(body))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode pay(String id, String cash, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "pay-" + UUID.randomUUID())
                        .content("{\"payments\":[{\"method\":\"cash\",\"amount\":" + cash + "}]}"))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
