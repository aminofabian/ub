package zelisline.ub.restaurant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.restaurant.repository.MenuItemModifierGroupRepository;
import zelisline.ub.restaurant.repository.MenuItemRepository;
import zelisline.ub.restaurant.repository.RecipeLineRepository;
import zelisline.ub.serviceops.api.ServiceSaleTestSupport;

/**
 * The pantry (ADR 0026): ingredients restated as portions, a planned service turned into what to
 * buy, and a loss written off through inventory's own path. The seeded ingredient "Hair oil" has
 * ten in stock; a stew takes two of it and a soup one.
 */
class RestaurantPantryIT extends ServiceSaleTestSupport {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private MenuItemModifierGroupRepository linkRepository;
    @Autowired
    private RecipeLineRepository recipeRepository;

    private String stew;
    private String soup;

    @AfterEach
    void clear() {
        recipeRepository.deleteAll();
        linkRepository.deleteAll();
        menuItemRepository.deleteAll();
    }

    @BeforeEach
    void seed() throws Exception {
        clear();
        String id = "5a1e1111-1111-4111-8111-111111111180";
        permissionRepository.save(perm(id, "restaurant.menu.manage"));
        grant(ROLE_OWNER, id);
        var oil = itemRepository.findById(itemId).orElseThrow();
        oil.setBuyingPrice(new BigDecimal("2.00"));
        itemRepository.save(oil);

        stew = dish("Stew", 2);
        soup = dish("Soup", 1);
    }

    @Test
    void thePantryStatesStockAsPortionsAndSaysHowLongItLasts() throws Exception {
        // Two stews sold: four of the oil gone, six left; over fourteen days that is 0.14 a day.
        postSale("p-" + UUID.randomUUID(),
                "{\"kind\":\"DISH\",\"menuItemId\":\"" + stew + "\",\"quantity\":2,\"unitPrice\":300}", "600", 201);
        // Four more arrive from a supplier at 3.50, and expire tomorrow.
        LocalDate tomorrow = LocalDate.now(NAIROBI).plusDays(1);
        batch("4", tomorrow, UUID.randomUUID().toString(), "3.50");

        JsonNode pantry = getJson(as(get("/api/v1/restaurant/pantry").param("branchId", branchId), TENANT, owner, ROLE_OWNER), 200);
        JsonNode stewRow = byId(pantry.get("dishes"), "menuItemId", stew);
        assertThat(stewRow.get("canMake").asLong()).isEqualTo(5);
        assertThat(stewRow.get("limitingIngredient").asText()).isEqualTo("Hair oil");
        assertThat(stewRow.get("soldPerDay").decimalValue()).isEqualByComparingTo("0.14");
        assertThat(stewRow.get("daysOfCover").decimalValue()).isEqualByComparingTo("35.7");
        // Nothing sold of the soup: no rate, so no days of cover.
        assertThat(byId(pantry.get("dishes"), "menuItemId", soup).get("daysOfCover").isNull()).isTrue();

        JsonNode oil = byId(pantry.get("ingredients"), "itemId", itemId);
        assertThat(oil.get("usable").decimalValue()).isEqualByComparingTo("10");
        assertThat(oil.get("dishes")).hasSize(2);
        // Stated in the dish that sells: ten of the oil is five stews.
        assertThat(oil.get("servingsDish").asText()).isEqualTo("Stew");
        assertThat(oil.get("servings").asLong()).isEqualTo(5);
        assertThat(oil.get("expiringSoon").decimalValue()).isEqualByComparingTo("4");
        assertThat(oil.get("firstExpiry").asText()).isEqualTo(tomorrow.toString());
        assertThat(oil.get("lastBoughtAt").isNull()).isFalse();
        assertThat(oil.get("lastUnitCost").decimalValue()).isEqualByComparingTo("3.50");

        // A cashier may not open the pantry; another business sees nothing of this one.
        mockMvc.perform(as(get("/api/v1/restaurant/pantry").param("branchId", branchId), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isForbidden());
        JsonNode other = getJson(as(get("/api/v1/restaurant/pantry").param("branchId", branchId), OTHER, otherOwner, ROLE_OWNER), 200);
        assertThat(other.get("dishes")).isEmpty();
        assertThat(other.get("ingredients")).isEmpty();
    }

    @Test
    void aPlannedServiceAddsUpSharedIngredientsAndSaysWhatToBuy() throws Exception {
        // Four stews and five soups take 8 + 5 = 13 of the oil; ten are in stock; three to buy at 2.00.
        JsonNode plan = send(as(post("/api/v1/restaurant/pantry/plan"), TENANT, owner, ROLE_OWNER),
                "{\"branchId\":\"" + branchId + "\",\"portions\":[{\"menuItemId\":\"" + stew + "\",\"portions\":4},"
                        + "{\"menuItemId\":\"" + soup + "\",\"portions\":5}]}", 200);
        assertThat(plan.get("lines")).hasSize(1);
        JsonNode line = plan.get("lines").get(0);
        assertThat(line.get("needed").decimalValue()).isEqualByComparingTo("13");
        assertThat(line.get("usable").decimalValue()).isEqualByComparingTo("10");
        assertThat(line.get("shortfall").decimalValue()).isEqualByComparingTo("3");
        assertThat(line.get("estimatedCost").decimalValue()).isEqualByComparingTo("6.00");
        assertThat(plan.get("estimatedTotal").decimalValue()).isEqualByComparingTo("6.00");
        assertThat(plan.get("costKnown").asBoolean()).isTrue();

        // Enough in stock: nothing to buy.
        JsonNode small = send(as(post("/api/v1/restaurant/pantry/plan"), TENANT, owner, ROLE_OWNER),
                "{\"branchId\":\"" + branchId + "\",\"portions\":[{\"menuItemId\":\"" + stew + "\",\"portions\":2}]}", 200);
        assertThat(small.get("lines").get(0).get("shortfall").decimalValue()).isEqualByComparingTo("0");
        assertThat(small.get("estimatedTotal").decimalValue()).isEqualByComparingTo("0");

        // A dish that is not on this menu, nothing asked for, and another business's dish are refused.
        String unknown = "{\"branchId\":\"" + branchId + "\",\"portions\":[{\"menuItemId\":\"" + UUID.randomUUID() + "\",\"portions\":1}]}";
        send(as(post("/api/v1/restaurant/pantry/plan"), TENANT, owner, ROLE_OWNER), unknown, 400);
        send(as(post("/api/v1/restaurant/pantry/plan"), TENANT, owner, ROLE_OWNER),
                "{\"branchId\":\"" + branchId + "\",\"portions\":[]}", 400);
        send(as(post("/api/v1/restaurant/pantry/plan"), OTHER, otherOwner, ROLE_OWNER),
                "{\"branchId\":\"" + branchId + "\",\"portions\":[{\"menuItemId\":\"" + stew + "\",\"portions\":1}]}", 400);
        mockMvc.perform(as(post("/api/v1/restaurant/pantry/plan"), TENANT, cashier, ROLE_CASHIER).content(unknown))
                .andExpect(status().isForbidden());
    }

    @Test
    void aLossIsWrittenOffAndSaidInPortions() throws Exception {
        JsonNode loss = send(as(post("/api/v1/restaurant/pantry/losses"), TENANT, owner, ROLE_OWNER),
                lossBody(itemId, "4", "SPOILAGE"), 201);
        // Nothing has sold, so the loss is stated in the first dish by name: four of the oil is four soups.
        assertThat(loss.get("quantity").decimalValue()).isEqualByComparingTo("4");
        assertThat(loss.get("cost").decimalValue()).isEqualByComparingTo("8.00");
        assertThat(loss.get("servingsDish").asText()).isEqualTo("Soup");
        assertThat(loss.get("servingsLost").asLong()).isEqualTo(4);

        // The stock is gone through inventory's own write-off, and the count of stews falls with it.
        assertThat(inventoryBatchRepository.findAll().get(0).getQuantityRemaining()).isEqualByComparingTo("6");
        assertThat(stockMovementRepository.findAll().stream()
                .filter(m -> PurchasingConstants.MOVEMENT_WASTAGE.equals(m.getMovementType())).count()).isEqualTo(1);
        JsonNode pantry = getJson(as(get("/api/v1/restaurant/pantry").param("branchId", branchId), TENANT, owner, ROLE_OWNER), 200);
        assertThat(byId(pantry.get("dishes"), "menuItemId", stew).get("canMake").asLong()).isEqualTo(3);
        assertThat(pantry.get("losses")).hasSize(1);
        assertThat(pantry.get("losses").get(0).get("reason").asText()).isEqualTo("SPOILAGE");
        assertThat(pantry.get("losses").get(0).get("quantity").decimalValue()).isEqualByComparingTo("4");

        // More than is there, a reason that is not one, a product that is no ingredient: refused.
        send(as(post("/api/v1/restaurant/pantry/losses"), TENANT, owner, ROLE_OWNER), lossBody(itemId, "100", "SPOILAGE"), 400);
        send(as(post("/api/v1/restaurant/pantry/losses"), TENANT, owner, ROLE_OWNER), lossBody(itemId, "1", "THEFT"), 400);
        send(as(post("/api/v1/restaurant/pantry/losses"), TENANT, owner, ROLE_OWNER),
                lossBody(UUID.randomUUID().toString(), "1", "SPOILAGE"), 400);
        // Another business cannot write off this one's stock; a cashier may not write off at all.
        send(as(post("/api/v1/restaurant/pantry/losses"), OTHER, otherOwner, ROLE_OWNER), lossBody(itemId, "1", "SPOILAGE"), 400);
        mockMvc.perform(as(post("/api/v1/restaurant/pantry/losses"), TENANT, cashier, ROLE_CASHIER)
                        .content(lossBody(itemId, "1", "SPOILAGE")))
                .andExpect(status().isForbidden());
        assertThat(inventoryBatchRepository.findAll().get(0).getQuantityRemaining()).isEqualByComparingTo("6");
    }

    @Test
    void expiredStockCanBeWrittenOffAndALossCostsWhatItsBatchCost() throws Exception {
        // Three that expired yesterday sit on the shelf: they make nothing, and can be written off.
        batch("3", LocalDate.now(NAIROBI).minusDays(1), UUID.randomUUID().toString(), "5.00");
        JsonNode oil = byId(getJson(as(get("/api/v1/restaurant/pantry").param("branchId", branchId), TENANT, owner, ROLE_OWNER), 200)
                .get("ingredients"), "itemId", itemId);
        assertThat(oil.get("usable").decimalValue()).isEqualByComparingTo("10");
        assertThat(oil.get("expired").decimalValue()).isEqualByComparingTo("3");

        // Inventory takes the expired batch first and prices the loss at what that batch cost (5.00),
        // not at the product's buying price (2.00).
        JsonNode loss = send(as(post("/api/v1/restaurant/pantry/losses"), TENANT, owner, ROLE_OWNER),
                lossBody(itemId, "3", "EXPIRED"), 201);
        assertThat(loss.get("cost").decimalValue()).isEqualByComparingTo("15.00");
        JsonNode after = getJson(as(get("/api/v1/restaurant/pantry").param("branchId", branchId), TENANT, owner, ROLE_OWNER), 200);
        assertThat(byId(after.get("ingredients"), "itemId", itemId).get("expired").decimalValue()).isEqualByComparingTo("0");
        assertThat(after.get("losses").get(0).get("cost").decimalValue()).isEqualByComparingTo("15.00");
        assertThat(byId(after.get("dishes"), "menuItemId", stew).get("canMake").asLong()).isEqualTo(5);
    }

    private String lossBody(String item, String quantity, String reason) {
        return "{\"branchId\":\"" + branchId + "\",\"itemId\":\"" + item + "\",\"quantity\":" + quantity
                + ",\"reason\":\"" + reason + "\"}";
    }

    private String dish(String name, int oilPerPortion) throws Exception {
        String id = send(as(post("/api/v1/restaurant/menu/items"), TENANT, owner, ROLE_OWNER),
                "{\"name\":\"" + name + "\",\"kind\":\"DISH\",\"price\":300}", 201).get("id").asText();
        send(as(put("/api/v1/restaurant/menu/items/" + id + "/recipe"), TENANT, owner, ROLE_OWNER),
                "{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":" + oilPerPortion + "}]}", 200);
        return id;
    }

    private static JsonNode byId(JsonNode rows, String field, String id) {
        for (JsonNode row : rows) {
            if (row.get(field).asText().equals(id)) {
                return row;
            }
        }
        throw new AssertionError("No row for " + id);
    }

    private void batch(String quantity, LocalDate expiry, String supplierId, String unitCost) {
        InventoryBatch batch = new InventoryBatch();
        batch.setId(UUID.randomUUID().toString());
        batch.setBusinessId(TENANT);
        batch.setBranchId(branchId);
        batch.setItemId(itemId);
        batch.setSupplierId(supplierId);
        batch.setBatchNumber("BN-" + UUID.randomUUID().toString().substring(0, 8));
        batch.setSourceType("test");
        batch.setSourceId(UUID.randomUUID().toString());
        batch.setInitialQuantity(new BigDecimal(quantity));
        batch.setQuantityRemaining(new BigDecimal(quantity));
        batch.setUnitCost(new BigDecimal(unitCost));
        batch.setReceivedAt(Instant.now());
        batch.setExpiryDate(expiry);
        batch.setStatus("active");
        inventoryBatchRepository.save(batch);
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
