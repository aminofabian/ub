package zelisline.ub.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.restaurant.repository.MenuItemRepository;
import zelisline.ub.restaurant.repository.ModifierGroupRepository;
import zelisline.ub.restaurant.repository.ModifierOptionRepository;
import zelisline.ub.restaurant.repository.RecipeLineRepository;
import zelisline.ub.serviceops.api.ServiceSaleTestSupport;

/**
 * The bar usage report (ADR 0024): where a bottle's stock went over a day — poured, sold whole,
 * wasted — read from the stock-movement ledger, and the variance against a count. The key thing it
 * must get right, which the morning/evening reconciliation cannot, is pour depletion: a bottle
 * poured through a recipe is never a sale line.
 */
class BarUsageIT extends ServiceSaleTestSupport {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private ModifierGroupRepository modifierGroupRepository;
    @Autowired
    private ModifierOptionRepository modifierOptionRepository;
    @Autowired
    private RecipeLineRepository recipeRepository;
    @Autowired
    private zelisline.ub.pricing.application.PricingService pricingService;

    private String single;

    @BeforeEach
    void seed() throws Exception {
        recipeRepository.deleteAll();
        modifierOptionRepository.deleteAll();
        modifierGroupRepository.deleteAll();
        menuItemRepository.deleteAll();
        String[][] perms = {{"80", "restaurant.menu.manage"}, {"82", "restaurant.orders.write"},
                {"83", "stocktake.read"}, {"84", "inventory.write"}};
        for (String[] perm : perms) {
            String id = "5a1e1111-1111-4111-8111-1111111111" + perm[0];
            permissionRepository.save(perm(id, perm[1]));
            grant(ROLE_OWNER, id);
        }

        // The bottle: stocked, 10 on hand at 2.00 (seeded), and also sellable whole as a product.
        var bottle = itemRepository.findById(itemId).orElseThrow();
        bottle.setBuyingPrice(new BigDecimal("2.00"));
        itemRepository.save(bottle);

        // A measure drink and its single pour (25 ml of a 750 ml bottle = 0.0333).
        String drink = ownerPost("/api/v1/restaurant/menu/items",
                "{\"name\":\"House gin\",\"kind\":\"DISH\",\"price\":150}").get("id").asText();
        single = ownerPost("/api/v1/restaurant/menu/measures",
                "{\"bottleItemId\":\"" + itemId + "\",\"bottleMl\":750,\"dishMenuItemId\":\"" + drink + "\",\"pours\":["
                        + "{\"label\":\"Single\",\"pourMl\":25,\"price\":150}]}")
                .get("pours").get(0).get("optionId").asText();
        this.drink = drink;
    }

    private String drink;

    @Test
    void theReportSplitsPoursWholeSalesAndWasteFromTheLedger() throws Exception {
        // Pour two singles: 2 x 0.0333 = 0.0666 off the bottle.
        postSale("p1-" + UUID.randomUUID(), measure(drink, single), "150", 201);
        postSale("p2-" + UUID.randomUUID(), measure(drink, single), "150", 201);
        // Sell one whole bottle off the shelf (a direct sale, by item id).
        setShelfPrice("1500");
        postSale("s1-" + UUID.randomUUID(),
                "{\"itemId\":\"" + itemId + "\",\"quantity\":1,\"unitPrice\":1500}", "1500", 201);
        // Waste one bottle (a breakage).
        send(as(post("/api/v1/inventory/wastage"), TENANT, owner, ROLE_OWNER),
                "{\"branchId\":\"" + branchId + "\",\"itemId\":\"" + itemId + "\",\"quantity\":1,"
                        + "\"unitCost\":2,\"wastageReason\":\"BREAKAGE\"}", 201);

        LocalDate today = LocalDate.now(NAIROBI);
        JsonNode report = getJson(as(get("/api/v1/inventory/stock-take/bar-usage")
                .param("branchId", branchId).param("date", today.toString()), TENANT, owner, ROLE_OWNER), 200);

        JsonNode line = null;
        for (JsonNode l : report.get("lines")) {
            if (l.get("itemId").asText().equals(itemId)) {
                line = l;
            }
        }
        assertThat(line).isNotNull();
        assertThat(line.get("poured").decimalValue()).isEqualByComparingTo("0.0666");
        assertThat(line.get("directSold").decimalValue()).isEqualByComparingTo("1");
        assertThat(line.get("wasted").decimalValue()).isEqualByComparingTo("1");
        // On hand now: 10 − 0.0666 (poured) − 1 (sold) − 1 (wasted) = 7.9334.
        assertThat(line.get("systemOnHand").decimalValue()).isEqualByComparingTo("7.9334");
        // Opening is worked back to the seeded 10.
        assertThat(line.get("openingOnHand").decimalValue()).isEqualByComparingTo("10");
        // No count entered yet.
        assertThat(line.get("counted").isNull()).isTrue();
        assertThat(line.get("variance").isNull()).isTrue();
    }

    @Test
    void aVoidedPourIsNotCountedAsPoured() throws Exception {
        JsonNode sale = postSale("v1-" + UUID.randomUUID(), measure(drink, single), "150", 201);
        postSale("v2-" + UUID.randomUUID(), measure(drink, single), "150", 201);
        // Void the first pour; its 0.0333 goes back.
        send(as(post("/api/v1/sales/" + sale.get("id").asText() + "/void"), TENANT, owner, ROLE_OWNER),
                "{\"reason\":\"test\"}", 200);

        LocalDate today = LocalDate.now(NAIROBI);
        JsonNode report = getJson(as(get("/api/v1/inventory/stock-take/bar-usage")
                .param("branchId", branchId).param("date", today.toString()), TENANT, owner, ROLE_OWNER), 200);
        JsonNode line = null;
        for (JsonNode l : report.get("lines")) {
            if (l.get("itemId").asText().equals(itemId)) {
                line = l;
            }
        }
        assertThat(line).isNotNull();
        // One pour of 0.0333 remains net of the voided one.
        assertThat(line.get("poured").decimalValue()).isEqualByComparingTo("0.0333");
        assertThat(line.get("systemOnHand").decimalValue()).isEqualByComparingTo("9.9667");
    }

    @Test
    void theReportNeedsABranchAndRefusesAnotherTenantsBranch() throws Exception {
        LocalDate today = LocalDate.now(NAIROBI);
        // Another tenant asking for this branch is refused.
        mockMvc.perform(as(get("/api/v1/inventory/stock-take/bar-usage")
                        .param("branchId", branchId).param("date", today.toString()), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().is4xxClientError());
        // An unknown branch is a bad request.
        mockMvc.perform(as(get("/api/v1/inventory/stock-take/bar-usage")
                        .param("branchId", UUID.randomUUID().toString()), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isBadRequest());
    }

    private String measure(String dishId, String optionId) {
        return "{\"kind\":\"DISH\",\"menuItemId\":\"" + dishId + "\",\"quantity\":1,\"unitPrice\":150,"
                + "\"modifierOptionIds\":[\"" + optionId + "\"]}";
    }

    private void setShelfPrice(String amount) {
        pricingService.setSellingPrice(TENANT, new zelisline.ub.pricing.api.dto.PostSellingPriceRequest(
                itemId, null, new BigDecimal(amount), LocalDate.now(java.time.ZoneOffset.UTC), null), owner.getId());
    }

    private JsonNode ownerPost(String path, String body) throws Exception {
        return send(as(post(path), TENANT, owner, ROLE_OWNER), body, path.endsWith("/items") ? 201 : 200);
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(request.content(body)).andExpect(status().is(expected)).andReturn();
        String text = result.getResponse().getContentAsString();
        return text.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(text);
    }

    private JsonNode getJson(MockHttpServletRequestBuilder request, int expected) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().is(expected)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
