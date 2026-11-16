package zelisline.ub.restaurant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.restaurant.repository.MenuItemModifierGroupRepository;
import zelisline.ub.restaurant.repository.MenuItemRepository;
import zelisline.ub.restaurant.repository.ModifierGroupRepository;
import zelisline.ub.restaurant.repository.ModifierOptionRepository;
import zelisline.ub.restaurant.repository.RecipeLineRepository;
import zelisline.ub.serviceops.api.ServiceSaleTestSupport;

/**
 * Bar pour measures (ADR 0024). The measures helper turns a bottle and its pour sizes into a
 * "choose one" modifier group whose options each take their fraction of the bottle off the count
 * when sold. The seeded ingredient "Hair oil" (ten in stock at 2.00) stands in for a 750 ml bottle.
 */
class RestaurantMeasuresIT extends ServiceSaleTestSupport {

    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private ModifierGroupRepository modifierGroupRepository;
    @Autowired
    private ModifierOptionRepository modifierOptionRepository;
    @Autowired
    private MenuItemModifierGroupRepository linkRepository;
    @Autowired
    private RecipeLineRepository recipeRepository;

    private String gin;

    @AfterEach
    void clear() {
        recipeRepository.deleteAll();
        linkRepository.deleteAll();
    }

    @BeforeEach
    void seed() throws Exception {
        recipeRepository.deleteAll();
        linkRepository.deleteAll();
        modifierOptionRepository.deleteAll();
        modifierGroupRepository.deleteAll();
        menuItemRepository.deleteAll();
        String[][] perms = {{"80", "restaurant.menu.manage"}, {"82", "restaurant.orders.write"}};
        for (String[] perm : perms) {
            String id = "5a1e1111-1111-4111-8111-1111111111" + perm[0];
            permissionRepository.save(perm(id, perm[1]));
            grant(ROLE_OWNER, id);
        }
        grant(ROLE_CASHIER, "5a1e1111-1111-4111-8111-111111111182");

        // The bottle is stocked but not for sale, as a spirit behind the bar would be.
        var bottle = itemRepository.findById(itemId).orElseThrow();
        bottle.setSellable(false);
        bottle.setBuyingPrice(new BigDecimal("1500.00"));
        itemRepository.save(bottle);

        // A dish to hang the measures on. Its base price is the single; the pour adds the rest.
        gin = ownerPost("/api/v1/restaurant/menu/items",
                "{\"name\":\"House gin\",\"kind\":\"DISH\",\"price\":150}").get("id").asText();
    }

    @Test
    void measuresBuildAGroupWhoseOptionsTakeTheirFractionOfTheBottleAndRingAtTheirPrice() throws Exception {
        // 750 ml bottle; single 25 ml at 150, double 50 ml at 280. The owner gives whole prices.
        JsonNode out = ownerPost("/api/v1/restaurant/menu/measures",
                "{\"bottleItemId\":\"" + itemId + "\",\"bottleMl\":750,\"groupName\":\"Measure\","
                        + "\"dishMenuItemId\":\"" + gin + "\",\"pours\":["
                        + "{\"label\":\"Single\",\"pourMl\":25,\"price\":150,\"preselected\":true},"
                        + "{\"label\":\"Double\",\"pourMl\":50,\"price\":280}]}");
        assertThat(out.get("pours")).hasSize(2);
        // 25/750 = 0.0333, 50/750 = 0.0667 (4 dp, half up); the response echoes the whole prices.
        assertThat(out.get("pours").get(0).get("quantity").decimalValue()).isEqualByComparingTo("0.0333");
        assertThat(out.get("pours").get(1).get("quantity").decimalValue()).isEqualByComparingTo("0.0667");
        assertThat(out.get("pours").get(0).get("price").decimalValue()).isEqualByComparingTo("150");
        assertThat(out.get("pours").get(1).get("price").decimalValue()).isEqualByComparingTo("280");
        String groupId = out.get("groupId").asText();
        String single = out.get("pours").get(0).get("optionId").asText();

        // The group is a "choose one" and is hung on the drink; the dish base is the cheapest pour.
        assertThat(modifierGroupRepository.findByIdAndBusinessId(groupId, TENANT).orElseThrow().getMinSelect())
                .isEqualTo(1);
        assertThat(linkRepository.findByBusinessIdAndMenuItemIdOrderBySortOrderAsc(TENANT, gin)).hasSize(1);
        assertThat(menuItemRepository.findByIdAndBusinessId(gin, TENANT).orElseThrow().getPrice())
                .isEqualByComparingTo("150");
        // Each option carries its own one-line recipe against the bottle.
        assertThat(recipeRepository.findByBusinessIdAndModifierOptionIdOrderBySortOrderAsc(TENANT, single)).hasSize(1);

        // A double rings at exactly its price (base 150 + 130 add-on = 280) and takes 0.0667 off the bottle.
        postSale("m1-" + UUID.randomUUID(),
                "{\"kind\":\"DISH\",\"menuItemId\":\"" + gin + "\",\"quantity\":1,\"unitPrice\":280,"
                        + "\"modifierOptionIds\":[\"" + out.get("pours").get(1).get("optionId").asText() + "\"]}",
                "280", 201);
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock())
                .isEqualByComparingTo("9.9333");
    }

    @Test
    void reRunningWithTheSameGroupNameReplacesThePoursInPlace() throws Exception {
        String body = "{\"bottleItemId\":\"" + itemId + "\",\"bottleMl\":750,\"groupName\":\"Measure\","
                + "\"dishMenuItemId\":\"" + gin + "\",\"pours\":["
                + "{\"label\":\"Single\",\"pourMl\":25,\"price\":150},"
                + "{\"label\":\"Double\",\"pourMl\":50,\"price\":280}]}";
        String firstGroup = ownerPost("/api/v1/restaurant/menu/measures", body).get("groupId").asText();
        String firstSingleId = ownerPost("/api/v1/restaurant/menu/measures", body)
                .get("pours").get(0).get("optionId").asText();

        // Same group, the Single option kept by its label (its id is stable), no pile-up of groups.
        assertThat(modifierGroupRepository.findByBusinessIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(TENANT))
                .hasSize(1);
        assertThat(ownerPost("/api/v1/restaurant/menu/measures", body).get("groupId").asText())
                .isEqualTo(firstGroup);
        assertThat(ownerPost("/api/v1/restaurant/menu/measures", body).get("pours").get(0).get("optionId").asText())
                .isEqualTo(firstSingleId);
        // Exactly the two live options remain.
        assertThat(modifierOptionRepository
                .findByBusinessIdAndGroupIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(TENANT, firstGroup))
                .hasSize(2);
    }

    @Test
    void anUnrelatedGroupOfTheSameNameOnAnotherDishIsLeftAlone() throws Exception {
        // A coffee already has a "Measure" group (Small/Large) — unrelated to any bottle.
        JsonNode coffeeGroup = ownerPost("/api/v1/restaurant/menu/modifier-groups",
                "{\"name\":\"Measure\",\"minSelect\":1,\"maxSelect\":1,\"options\":["
                        + "{\"name\":\"Small\",\"priceDelta\":0},{\"name\":\"Large\",\"priceDelta\":50}]}");
        String coffeeGroupId = coffeeGroup.get("id").asText();
        String coffee = ownerPost("/api/v1/restaurant/menu/items",
                "{\"name\":\"Latte\",\"kind\":\"DISH\",\"price\":200,\"modifierGroupIds\":[\"" + coffeeGroupId + "\"]}")
                .get("id").asText();

        // Setting measures on the gin with the default "Measure" name must NOT touch the coffee's group.
        JsonNode out = ownerPost("/api/v1/restaurant/menu/measures",
                "{\"bottleItemId\":\"" + itemId + "\",\"bottleMl\":750,\"dishMenuItemId\":\"" + gin + "\",\"pours\":["
                        + "{\"label\":\"Single\",\"pourMl\":25,\"price\":150}]}");
        assertThat(out.get("groupId").asText()).isNotEqualTo(coffeeGroupId);

        // The coffee's group still has Small and Large, unchanged.
        assertThat(modifierOptionRepository
                .findByBusinessIdAndGroupIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(TENANT, coffeeGroupId)
                .stream().map(o -> o.getName()).toList())
                .containsExactlyInAnyOrder("Small", "Large");
        // The coffee still carries its own group.
        assertThat(linkRepository.findByBusinessIdAndMenuItemIdOrderBySortOrderAsc(TENANT, coffee)).hasSize(1);
    }

    @Test
    void settingMeasuresClearsAnyHappyHourSoPoursRingAtTheirPrice() throws Exception {
        // The gin dish is given a happy-hour price up front.
        var withHappy = menuItemRepository.findByIdAndBusinessId(gin, TENANT).orElseThrow();
        withHappy.setHappyPrice(new BigDecimal("90.00"));
        withHappy.setHappyFromMinute(0);
        withHappy.setHappyToMinute(1439);
        menuItemRepository.save(withHappy);

        ownerPost("/api/v1/restaurant/menu/measures",
                "{\"bottleItemId\":\"" + itemId + "\",\"bottleMl\":750,\"dishMenuItemId\":\"" + gin + "\",\"pours\":["
                        + "{\"label\":\"Single\",\"pourMl\":25,\"price\":150}]}");
        // Measures clears happy hour, so a single always rings at 150.
        var dish = menuItemRepository.findByIdAndBusinessId(gin, TENANT).orElseThrow();
        assertThat(dish.getHappyPrice()).isNull();
        assertThat(dish.getPrice()).isEqualByComparingTo("150");
    }

    @Test
    void twoPoursWithTheSameNameAreRefused() throws Exception {
        ownerPostExpecting("/api/v1/restaurant/menu/measures",
                "{\"bottleItemId\":\"" + itemId + "\",\"bottleMl\":750,\"dishMenuItemId\":\"" + gin + "\",\"pours\":["
                        + "{\"label\":\"Single\",\"pourMl\":25,\"price\":150},"
                        + "{\"label\":\"single\",\"pourMl\":50,\"price\":280}]}", 400);
    }

    @Test
    void aPourBiggerThanTheBottleOrAnUnstockedBottleIsRefused() throws Exception {
        // A pour larger than the whole bottle.
        ownerPostExpecting("/api/v1/restaurant/menu/measures",
                "{\"bottleItemId\":\"" + itemId + "\",\"bottleMl\":750,\"dishMenuItemId\":\"" + gin + "\",\"pours\":["
                        + "{\"label\":\"Huge\",\"pourMl\":800,\"price\":500}]}", 400);
        // An unknown bottle.
        ownerPostExpecting("/api/v1/restaurant/menu/measures",
                "{\"bottleItemId\":\"" + UUID.randomUUID() + "\",\"bottleMl\":750,\"dishMenuItemId\":\"" + gin + "\",\"pours\":["
                        + "{\"label\":\"Single\",\"pourMl\":25,\"price\":150}]}", 400);
        // A bottle that is not stocked cannot be poured from.
        var notStocked = itemRepository.findById(itemId).orElseThrow();
        notStocked.setStocked(false);
        itemRepository.save(notStocked);
        ownerPostExpecting("/api/v1/restaurant/menu/measures",
                "{\"bottleItemId\":\"" + itemId + "\",\"bottleMl\":750,\"dishMenuItemId\":\"" + gin + "\",\"pours\":["
                        + "{\"label\":\"Single\",\"pourMl\":25,\"price\":150}]}", 400);
    }

    @Test
    void anotherBusinessCannotPourFromThisBottleOrSeeTheHelper() throws Exception {
        String body = "{\"bottleItemId\":\"" + itemId + "\",\"bottleMl\":750,\"dishMenuItemId\":\"" + gin + "\",\"pours\":["
                + "{\"label\":\"Single\",\"pourMl\":25,\"price\":150}]}";
        // Another tenant's owner is told the bottle is not theirs (400), not shown it.
        mockMvc.perform(as(post("/api/v1/restaurant/menu/measures"), OTHER, otherOwner, ROLE_OWNER).content(body))
                .andExpect(status().isBadRequest());
        // A cashier without menu-manage is forbidden.
        mockMvc.perform(as(post("/api/v1/restaurant/menu/measures"), TENANT, cashier, ROLE_CASHIER).content(body))
                .andExpect(status().isForbidden());
        assertThat(modifierGroupRepository.count()).isZero();
    }

    private JsonNode ownerPost(String path, String body) throws Exception {
        // The measures helper returns 200; creating a menu item or a modifier group returns 201.
        return send(as(post(path), TENANT, owner, ROLE_OWNER), body, path.endsWith("/measures") ? 200 : 201);
    }

    private void ownerPostExpecting(String path, String body, int expected) throws Exception {
        send(as(post(path), TENANT, owner, ROLE_OWNER), body, expected);
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(request.content(body)).andExpect(status().is(expected)).andReturn();
        String text = result.getResponse().getContentAsString();
        return text.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(text);
    }
}
