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

import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.restaurant.repository.MenuItemModifierGroupRepository;
import zelisline.ub.restaurant.repository.MenuItemRepository;
import zelisline.ub.restaurant.repository.RecipeLineRepository;
import zelisline.ub.serviceops.api.ServiceSaleTestSupport;

/**
 * The servings forecast (ADR 0026): how many of a dish the branch's usable stock can still make.
 * The seeded ingredient "Hair oil" has ten in stock at the branch, in a batch that has not expired.
 */
class RestaurantMakeableIT extends ServiceSaleTestSupport {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private MenuItemModifierGroupRepository linkRepository;
    @Autowired
    private RecipeLineRepository recipeRepository;

    private String stew;

    @AfterEach
    void clear() {
        recipeRepository.deleteAll();
        linkRepository.deleteAll();
        menuItemRepository.deleteAll();
    }

    @BeforeEach
    void seed() throws Exception {
        recipeRepository.deleteAll();
        linkRepository.deleteAll();
        menuItemRepository.deleteAll();
        String id = "5a1e1111-1111-4111-8111-111111111180";
        permissionRepository.save(perm(id, "restaurant.menu.manage"));
        grant(ROLE_OWNER, id);

        stew = send(as(post("/api/v1/restaurant/menu/items"), TENANT, owner, ROLE_OWNER),
                "{\"name\":\"Stew\",\"kind\":\"DISH\",\"price\":300}", 201).get("id").asText();
        // One stew takes 2 of the ingredient: ten in stock makes five.
        recipe("{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":2}]}");
    }

    @Test
    void aDishShowsHowManyTheStockCanMakeAndWhatRunsOutFirst() throws Exception {
        JsonNode dish = dishOnMenu(TENANT, branchId);
        assertThat(dish.get("canMake").asLong()).isEqualTo(5);
        assertThat(dish.get("limitingIngredient").asText()).isEqualTo("Hair oil");
        assertThat(dish.get("mustUseToday").asLong()).isZero();

        // Three on the plate at 75% usable takes four from stock: ten makes two, not three.
        recipe("{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":3,\"yieldPercent\":75}]}");
        assertThat(dishOnMenu(TENANT, branchId).get("canMake").asLong()).isEqualTo(2);

        // With no branch there is no stock to count from, so no figure is given.
        JsonNode noBranch = firstDish(getJson(as(get("/api/v1/restaurant/menu"), TENANT, owner, ROLE_OWNER)));
        assertThat(noBranch.get("canMake").isNull()).isTrue();
        assertThat(noBranch.get("limitingIngredient").isNull()).isTrue();
    }

    @Test
    void onlyUsableStockAtThisBranchCounts() throws Exception {
        LocalDate today = LocalDate.now(NAIROBI);
        // Expired stock and stock at another branch make nothing here.
        batch(itemId, branchId, "100", today.minusDays(1));
        batch(itemId, UUID.randomUUID().toString(), "100", null);
        assertThat(dishOnMenu(TENANT, branchId).get("canMake").asLong()).isEqualTo(5);

        // Four more that expire today: seven can be made, two of them only today.
        batch(itemId, branchId, "4", today);
        JsonNode dish = dishOnMenu(TENANT, branchId);
        assertThat(dish.get("canMake").asLong()).isEqualTo(7);
        assertThat(dish.get("mustUseToday").asLong()).isEqualTo(2);
    }

    @Test
    void anEmptyShelfMakesNoneAndAnUncountedIngredientNeverLimits() throws Exception {
        for (InventoryBatch b : inventoryBatchRepository.findAll()) {
            b.setQuantityRemaining(BigDecimal.ZERO);
            inventoryBatchRepository.save(b);
        }
        JsonNode out = dishOnMenu(TENANT, branchId);
        assertThat(out.get("canMake").asLong()).isZero();
        assertThat(out.get("limitingIngredient").asText()).isEqualTo("Hair oil");

        // Once the ingredient is no longer counted (salt, water), the dish has no figure at all.
        var ingredient = itemRepository.findById(itemId).orElseThrow();
        ingredient.setStocked(false);
        itemRepository.save(ingredient);
        assertThat(dishOnMenu(TENANT, branchId).get("canMake").isNull()).isTrue();
    }

    @Test
    void aStandInsStockCountsWithTheIngredientItStandsInFor() throws Exception {
        String goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();
        String standIn = itemCatalogService.createItem(TENANT,
                new zelisline.ub.catalog.api.dto.CreateItemRequest(
                        "SKU-OIL-2", null, "Coconut oil", null, goodsTypeId, null, null, null,
                        false, true, true,
                        null, null, null, null, null, null, null, null, null, null, true, null, null, null, null),
                null).body().id();
        batch(standIn, branchId, "4", null);
        recipe("{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":2,\"substituteItemId\":\"" + standIn + "\"}]}");
        // Ten of the ingredient and four of its stand-in: fourteen, at two a portion, makes seven.
        assertThat(dishOnMenu(TENANT, branchId).get("canMake").asLong()).isEqualTo(7);

        // A portion is never split between an ingredient and its stand-in.
        recipe("{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":6,\"substituteItemId\":\"" + standIn + "\"}]}");
        // Ten gives one portion of six; four of the stand-in gives none: one, not two (14 / 6).
        assertThat(dishOnMenu(TENANT, branchId).get("canMake").asLong()).isEqualTo(1);

        // The stand-in is an ingredient in its own right too: its stock is not counted twice.
        // Each portion takes 2 of the oil and 2 of the stand-in; the stand-in's four make two.
        recipe("{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":2,\"substituteItemId\":\"" + standIn + "\"},"
                + "{\"itemId\":\"" + standIn + "\",\"quantity\":2}]}");
        JsonNode both = dishOnMenu(TENANT, branchId);
        assertThat(both.get("canMake").asLong()).isEqualTo(2);
        assertThat(both.get("limitingIngredient").asText()).isEqualTo("Coconut oil");
    }

    @Test
    void anotherBusinessSeesNoneOfIt() throws Exception {
        JsonNode menu = getJson(as(get("/api/v1/restaurant/menu").param("branchId", branchId), OTHER, otherOwner, ROLE_OWNER));
        assertThat(menu.get("items")).isEmpty();
    }

    private void recipe(String body) throws Exception {
        send(as(put("/api/v1/restaurant/menu/items/" + stew + "/recipe"), TENANT, owner, ROLE_OWNER), body, 200);
    }

    private JsonNode dishOnMenu(String tenant, String branch) throws Exception {
        return firstDish(getJson(as(get("/api/v1/restaurant/menu").param("branchId", branch), tenant, owner, ROLE_OWNER)));
    }

    private JsonNode firstDish(JsonNode menu) {
        for (JsonNode item : menu.get("items")) {
            if (item.get("id").asText().equals(stew)) {
                return item;
            }
        }
        throw new AssertionError("The stew is not on the menu");
    }

    private void batch(String item, String branch, String quantity, LocalDate expiry) {
        InventoryBatch batch = new InventoryBatch();
        batch.setId(UUID.randomUUID().toString());
        batch.setBusinessId(TENANT);
        batch.setBranchId(branch);
        batch.setItemId(item);
        batch.setBatchNumber("BN-" + UUID.randomUUID().toString().substring(0, 8));
        batch.setSourceType("test");
        batch.setSourceId(UUID.randomUUID().toString());
        batch.setInitialQuantity(new BigDecimal(quantity));
        batch.setQuantityRemaining(new BigDecimal(quantity));
        batch.setUnitCost(new BigDecimal("2.00"));
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

    private JsonNode getJson(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
