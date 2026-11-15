package zelisline.ub.restaurant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.restaurant.repository.KitchenStationRepository;
import zelisline.ub.restaurant.repository.KitchenTicketLineRepository;
import zelisline.ub.restaurant.repository.KitchenTicketRepository;
import zelisline.ub.restaurant.repository.MenuCategoryRepository;
import zelisline.ub.restaurant.repository.MenuItemModifierGroupRepository;
import zelisline.ub.restaurant.repository.MenuItemRepository;
import zelisline.ub.restaurant.repository.ModifierGroupRepository;
import zelisline.ub.restaurant.repository.ModifierOptionRepository;
import zelisline.ub.restaurant.repository.OrderBillLineRepository;
import zelisline.ub.restaurant.repository.OrderBillRepository;
import zelisline.ub.restaurant.repository.RecipeLineRepository;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.serviceops.api.ServiceSaleTestSupport;
import zelisline.ub.serviceops.repository.ServiceResourceRepository;

/**
 * Restaurant phase 1.5: a dish with a recipe uses up its ingredients when it is sold and costs
 * what they cost; a wrong count never stops the sale; a void puts the ingredients back.
 * The ingredient here is the test product "Hair oil": ten in stock at 2.00 each.
 */
class RestaurantRecipesIT extends ServiceSaleTestSupport {

    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private MenuCategoryRepository menuCategoryRepository;
    @Autowired
    private ModifierGroupRepository modifierGroupRepository;
    @Autowired
    private ModifierOptionRepository modifierOptionRepository;
    @Autowired
    private MenuItemModifierGroupRepository linkRepository;
    @Autowired
    private KitchenStationRepository stationRepository;
    @Autowired
    private KitchenTicketRepository ticketRepository;
    @Autowired
    private KitchenTicketLineRepository ticketLineRepository;
    @Autowired
    private OrderBillRepository billRepository;
    @Autowired
    private OrderBillLineRepository billLineRepository;
    @Autowired
    private ServiceResourceRepository resourceRepository;
    @Autowired
    private RecipeLineRepository recipeRepository;
    @Autowired
    private zelisline.ub.restaurant.repository.KitchenRequisitionRepository requisitionRepository;
    @Autowired
    private zelisline.ub.restaurant.repository.KitchenRequisitionLineRepository requisitionLineRepository;

    private String burger;
    private String single;
    private String dbl;
    private String chips;

    /** Recipes change what other test classes see (a report says cost is tracked), so leave none behind. */
    @org.junit.jupiter.api.AfterEach
    void clearRecipes() {
        requisitionLineRepository.deleteAll();
        requisitionRepository.deleteAll();
        recipeRepository.deleteAll();
    }

    @BeforeEach
    void seedRecipes() throws Exception {
        requisitionLineRepository.deleteAll();
        requisitionRepository.deleteAll();
        recipeRepository.deleteAll();
        billLineRepository.deleteAll();
        billRepository.deleteAll();
        ticketLineRepository.deleteAll();
        ticketRepository.deleteAll();
        linkRepository.deleteAll();
        modifierOptionRepository.deleteAll();
        modifierGroupRepository.deleteAll();
        menuItemRepository.deleteAll();
        menuCategoryRepository.deleteAll();
        stationRepository.deleteAll();
        resourceRepository.deleteAll();
        String[][] perms = {{"80", "restaurant.menu.manage"}, {"81", "restaurant.kitchen.update"},
                {"82", "restaurant.orders.write"}};
        for (String[] perm : perms) {
            String id = "5a1e1111-1111-4111-8111-1111111111" + perm[0];
            permissionRepository.save(perm(id, perm[1]));
            grant(ROLE_OWNER, id);
        }
        grant(ROLE_CASHIER, "5a1e1111-1111-4111-8111-111111111182");
        grant(ROLE_CASHIER, "5a1e1111-1111-4111-8111-111111111181");

        // The ingredient is stocked but not for sale, as rice or cooking oil would be.
        var ingredient = itemRepository.findById(itemId).orElseThrow();
        ingredient.setSellable(false);
        ingredient.setBuyingPrice(new BigDecimal("2.00"));
        itemRepository.save(ingredient);

        JsonNode patty = ownerPost("/api/v1/restaurant/menu/modifier-groups",
                "{\"name\":\"Patty\",\"minSelect\":1,\"maxSelect\":1,\"options\":["
                        + "{\"name\":\"Single\",\"priceDelta\":0,\"preselected\":true},"
                        + "{\"name\":\"Double\",\"priceDelta\":150}]}");
        single = patty.get("options").get(0).get("id").asText();
        dbl = patty.get("options").get(1).get("id").asText();
        burger = ownerPost("/api/v1/restaurant/menu/items",
                "{\"name\":\"Burger\",\"kind\":\"DISH\",\"price\":400,\"modifierGroupIds\":[\""
                        + patty.get("id").asText() + "\"]}").get("id").asText();
        chips = ownerPost("/api/v1/restaurant/menu/items", "{\"name\":\"Chips\",\"kind\":\"DISH\",\"price\":150}")
                .get("id").asText();

        // A burger takes 2 of the ingredient; a double patty takes 1 more.
        ownerPut("/api/v1/restaurant/menu/items/" + burger + "/recipe",
                "{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":2}]}", 200);
        ownerPut("/api/v1/restaurant/menu/modifier-options/" + dbl + "/recipe",
                "{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":1}]}", 200);
    }

    @Test
    void aRecipeSaysWhatADishTakesFromStockAndWhatItCosts() throws Exception {
        JsonNode recipe = getJson(as(get("/api/v1/restaurant/menu/items/" + burger + "/recipe"), TENANT, owner, ROLE_OWNER), 200);
        assertThat(recipe.get("ingredients")).hasSize(1);
        assertThat(recipe.get("ingredients").get(0).get("name").asText()).isEqualTo("Hair oil");
        assertThat(recipe.get("estimatedCost").decimalValue()).isEqualByComparingTo("4.00");
        assertThat(recipe.get("costKnown").asBoolean()).isTrue();
        assertThat(recipe.get("ingredients").get(0).get("onHand").decimalValue()).isEqualByComparingTo("10");

        // Three quarters survive trimming: 3 on the plate takes 4 from stock.
        JsonNode trimmed = ownerPut("/api/v1/restaurant/menu/items/" + chips + "/recipe",
                "{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":3,\"yieldPercent\":75}]}", 200);
        assertThat(trimmed.get("ingredients").get(0).get("takenFromStock").decimalValue()).isEqualByComparingTo("4");
        assertThat(trimmed.get("estimatedCost").decimalValue()).isEqualByComparingTo("8.00");

        for (String bad : List.of(
                "{\"ingredients\":[{\"itemId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}",
                "{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":0}]}",
                "{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":1,\"yieldPercent\":150}]}",
                "{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":1},{\"itemId\":\"" + itemId + "\",\"quantity\":2}]}",
                "{}")) {
            ownerPut("/api/v1/restaurant/menu/items/" + chips + "/recipe", bad, 400);
        }
        // An empty recipe removes it.
        assertThat(ownerPut("/api/v1/restaurant/menu/items/" + chips + "/recipe", "{\"ingredients\":[]}", 200)
                .get("ingredients")).isEmpty();

        // Only whoever manages the menu sees or sets recipes; another business finds nothing.
        mockMvc.perform(as(get("/api/v1/restaurant/menu/items/" + burger + "/recipe"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/v1/restaurant/menu/items/" + burger + "/recipe"), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(put("/api/v1/restaurant/menu/modifier-options/" + dbl + "/recipe"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"ingredients\":[]}"))
                .andExpect(status().isNotFound());
        assertThat(recipeRepository.count()).isEqualTo(2);
    }

    @Test
    void sellingADishUsesItsIngredientsAndGivesItARealCost() throws Exception {
        // Two burgers, double patty: 2 x (2 + 1) = 6 of the ingredient, at 2.00 each.
        JsonNode sale = postSale("r1-" + UUID.randomUUID(), dish(burger, 2, "550", dbl), "1100", 201);
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock()).isEqualByComparingTo("4");
        assertThat(inventoryBatchRepository.findAll().get(0).getQuantityRemaining()).isEqualByComparingTo("4");

        SaleItem line = saleItemRepository.findById(sale.get("items").get(0).get("id").asText()).orElseThrow();
        assertThat(line.getCostTotal()).isEqualByComparingTo("12.00");
        assertThat(line.getUnitCost()).isEqualByComparingTo("6.0000");
        assertThat(line.getProfit()).isEqualByComparingTo("1088.00");
        assertThat(line.getBatchId()).isNull();

        Sale saved = saleRepository.findById(sale.get("id").asText()).orElseThrow();
        List<JournalLine> journal = journalLineRepository.findByJournalEntryId(saved.getJournalEntryId());
        assertBalanced(journal);
        assertThat(debit(journal, LedgerAccountCodes.COST_OF_GOODS_SOLD)).isEqualByComparingTo("12.00");
        assertThat(credit(journal, LedgerAccountCodes.INVENTORY)).isEqualByComparingTo("12.00");

        // A dish with no recipe still costs nothing and moves no stock.
        JsonNode plain = postSale("r2-" + UUID.randomUUID(), dish(chips, 1, "150"), "150", 201);
        assertThat(saleItemRepository.findById(plain.get("items").get(0).get("id").asText()).orElseThrow().getCostTotal())
                .isEqualByComparingTo("0.00");
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock()).isEqualByComparingTo("4");
    }

    @Test
    void aWrongCountNeverStopsAPlateBeingSold() throws Exception {
        // Ten in stock; five double burgers need fifteen.
        JsonNode sale = postSale("s1-" + UUID.randomUUID(), dish(burger, 5, "550", dbl), "2750", 201);
        var ingredient = itemRepository.findById(itemId).orElseThrow();
        assertThat(ingredient.getCurrentStock()).isEqualByComparingTo("-5");
        assertThat(inventoryBatchRepository.findAll().get(0).getQuantityRemaining()).isEqualByComparingTo("0");
        // The part beyond the count is costed at the last known cost.
        SaleItem line = saleItemRepository.findById(sale.get("items").get(0).get("id").asText()).orElseThrow();
        assertThat(line.getCostTotal()).isEqualByComparingTo("30.00");
        // And with nothing counted at all it still sells.
        postSale("s2-" + UUID.randomUUID(), dish(burger, 1, "400", single), "400", 201);
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock()).isEqualByComparingTo("-7");
    }

    @Test
    void voidingTheSalePutsTheIngredientsBack() throws Exception {
        JsonNode sale = postSale("v1-" + UUID.randomUUID(), dish(burger, 2, "550", dbl), "1100", 201);
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock()).isEqualByComparingTo("4");
        mockMvc.perform(as(post("/api/v1/sales/" + sale.get("id").asText() + "/void"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk());
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock()).isEqualByComparingTo("10");
        assertThat(inventoryBatchRepository.findAll().get(0).getQuantityRemaining()).isEqualByComparingTo("10");
        Sale voided = saleRepository.findById(sale.get("id").asText()).orElseThrow();
        assertBalanced(journalLineRepository.findByJournalEntryId(voided.getVoidJournalEntryId()));
        // Stock and books agree again: nothing sold, nothing used.
        assertThat(accountBalance(LedgerAccountCodes.COST_OF_GOODS_SOLD)).isEqualByComparingTo("0.00");
        assertThat(accountBalance(LedgerAccountCodes.INVENTORY)).isEqualByComparingTo("0.00");
    }

    @Test
    void aTablesBillUsesIngredientsWhenItIsPaidNotBefore() throws Exception {
        String table = ownerPost("/api/v1/restaurant/tables", "{\"branchId\":\"" + branchId + "\",\"name\":\"T1\"}")
                .get("id").asText();
        String orderId = cashierPost("/api/v1/restaurant/open-orders", "{\"branchId\":\"" + branchId
                + "\",\"channel\":\"DINE_IN\",\"tableId\":\"" + table + "\",\"clientOrderId\":\"" + UUID.randomUUID() + "\"}", 201)
                .get("id").asText();
        cashierPost("/api/v1/restaurant/open-orders/" + orderId + "/lines", "{\"lines\":[{\"clientLineId\":\"a\",\"menuItemId\":\""
                + burger + "\",\"quantity\":1,\"modifierOptionIds\":[\"" + dbl + "\"]}]}", 200);
        cashierPost("/api/v1/restaurant/open-orders/" + orderId + "/send", "", 200);
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock()).isEqualByComparingTo("10");
        String billId = cashierPost("/api/v1/restaurant/open-orders/" + orderId + "/bills", "", 201)
                .get("bills").get(0).get("id").asText();
        cashierPost("/api/v1/restaurant/open-orders/" + orderId + "/bills/" + billId + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":550}]}", 200);
        // The double patty's extra ingredient is found through the choice kept on the order line.
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock()).isEqualByComparingTo("7");
        assertThat(saleItemRepository.findAll().get(0).getCostTotal()).isEqualByComparingTo("6.00");
    }

    @Test
    void theReportShowsFoodCostAndSortsDishesByHowTheySellAndEarn() throws Exception {
        postSale("m1-" + UUID.randomUUID(), dish(burger, 3, "400", single), "1200", 201);
        postSale("m2-" + UUID.randomUUID(), dish(chips, 4, "150"), "600", 201);
        String today = LocalDate.now(ZoneId.of("Africa/Nairobi")).toString();
        JsonNode report = getJson(as(get("/api/v1/restaurant/reports/menu-sales").param("branchId", branchId)
                .param("from", today).param("to", today), TENANT, owner, ROLE_OWNER), 200);
        assertThat(report.get("costTracked").asBoolean()).isTrue();
        assertThat(report.get("totalCost").decimalValue()).isEqualByComparingTo("12.00");
        JsonNode burgerRow = report.get("items").get(0);
        assertThat(burgerRow.get("name").asText()).isEqualTo("Burger");
        assertThat(burgerRow.get("cost").decimalValue()).isEqualByComparingTo("12.00");
        assertThat(burgerRow.get("margin").decimalValue()).isEqualByComparingTo("1188.00");
        assertThat(burgerRow.get("costPercent").decimalValue()).isEqualByComparingTo("1.0");
        // The only dish with a cost is compared with itself: it sells and it earns.
        assertThat(burgerRow.get("engineering").asText()).isEqualTo("STAR");
        // Chips has no recipe: no cost is claimed for it and it is not classed.
        JsonNode chipsRow = report.get("items").get(1);
        assertThat(chipsRow.get("cost").isNull()).isTrue();
        assertThat(chipsRow.get("engineering").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ stand-ins

    @Test
    void aStandInIsUsedOnlyWhenTheIngredientHasRunOutAndComesBackWithAVoid() throws Exception {
        String sunflower = stocked("SKU-SUN", "Sunflower oil", "5", "3.00");
        // Chips take 4 of the ingredient, or 4 of the stand-in when the ingredient cannot cover it.
        JsonNode saved = ownerPut("/api/v1/restaurant/menu/items/" + chips + "/recipe",
                "{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":4,\"substituteItemId\":\"" + sunflower + "\"}]}", 200);
        assertThat(saved.get("ingredients").get(0).get("substituteName").asText()).isEqualTo("Sunflower oil");
        assertThat(saved.get("ingredients").get(0).get("substituteOnHand").decimalValue()).isEqualByComparingTo("5");

        // Ten of the ingredient: two plates use it, and the stand-in is not touched.
        postSale("sub1-" + UUID.randomUUID(), dish(chips, 2, "150"), "300", 201);
        assertThat(stock(itemId)).isEqualByComparingTo("2");
        assertThat(stock(sunflower)).isEqualByComparingTo("5");

        // Two left, four needed: the stand-in covers this plate, at what the stand-in cost.
        JsonNode second = postSale("sub2-" + UUID.randomUUID(), dish(chips, 1, "150"), "150", 201);
        assertThat(stock(itemId)).isEqualByComparingTo("2");
        assertThat(stock(sunflower)).isEqualByComparingTo("1");
        assertThat(saleItemRepository.findById(second.get("items").get(0).get("id").asText()).orElseThrow().getCostTotal())
                .isEqualByComparingTo("12.00");

        // Neither can cover the next one: it still sells, and the shortage shows on the ingredient the recipe names.
        postSale("sub3-" + UUID.randomUUID(), dish(chips, 1, "150"), "150", 201);
        assertThat(stock(itemId)).isEqualByComparingTo("-2");
        assertThat(stock(sunflower)).isEqualByComparingTo("1");

        // Voiding the plate that used the stand-in puts the stand-in back, not the ingredient.
        mockMvc.perform(as(post("/api/v1/sales/" + second.get("id").asText() + "/void"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk());
        assertThat(stock(sunflower)).isEqualByComparingTo("5");
        assertThat(stock(itemId)).isEqualByComparingTo("-2");
    }

    @Test
    void aStandInMustBeAnotherStockedProductOfTheSameBusiness() throws Exception {
        ownerPut("/api/v1/restaurant/menu/items/" + chips + "/recipe",
                "{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":1,\"substituteItemId\":\"" + itemId + "\"}]}", 400);
        ownerPut("/api/v1/restaurant/menu/items/" + chips + "/recipe",
                "{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":1,\"substituteItemId\":\"" + UUID.randomUUID() + "\"}]}", 400);
        // Left out, there is simply no stand-in.
        JsonNode plain = ownerPut("/api/v1/restaurant/menu/items/" + chips + "/recipe",
                "{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":1}]}", 200);
        assertThat(plain.get("ingredients").get(0).get("substituteItemId").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ kitchen requisitions

    @Test
    void theKitchenAsksTheStoreAndIsGivenSomeOrAllOfIt() throws Exception {
        String sunflower = stocked("SKU-SUN", "Sunflower oil", "5", "3.00");
        String key = UUID.randomUUID().toString();
        String ask = "{\"branchId\":\"" + branchId + "\",\"note\":\"for lunch\",\"clientKey\":\"" + key + "\",\"lines\":["
                + "{\"itemId\":\"" + itemId + "\",\"quantity\":6},{\"itemId\":\"" + sunflower + "\",\"quantity\":2}]}";
        // The cashier stands in for the kitchen here: they may ask, but not give out.
        JsonNode asked = cashierPost("/api/v1/restaurant/requisitions", ask, 201);
        String id = asked.get("id").asText();
        assertThat(asked.get("number").asLong()).isEqualTo(1);
        assertThat(asked.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(asked.get("requestedByName").asText()).isNotBlank();
        assertThat(asked.get("lines")).hasSize(2);
        assertThat(asked.get("lines").get(0).get("onHand").decimalValue()).isEqualByComparingTo("10");
        // The same request sent again is the same request.
        assertThat(cashierPost("/api/v1/restaurant/requisitions", ask, 201).get("id").asText()).isEqualTo(id);
        JsonNode list = getJson(as(get("/api/v1/restaurant/requisitions").param("branchId", branchId), TENANT, cashier, ROLE_CASHIER), 200);
        assertThat(list.get("waiting")).hasSize(1);
        assertThat(list.get("recent")).isEmpty();

        cashierPost("/api/v1/restaurant/requisitions/" + id + "/issue", "", 403);
        // Another business sees none of it and cannot touch it.
        assertThat(getJson(as(get("/api/v1/restaurant/requisitions").param("branchId", branchId), OTHER, otherOwner, ROLE_OWNER), 200)
                .get("waiting")).isEmpty();
        send(as(post("/api/v1/restaurant/requisitions/" + id + "/issue"), OTHER, otherOwner, ROLE_OWNER), "", 404);
        send(as(post("/api/v1/restaurant/requisitions/" + id + "/refuse"), OTHER, otherOwner, ROLE_OWNER), "{\"reason\":\"no\"}", 404);

        // The store gives four of the six and all of the other.
        String firstLine = asked.get("lines").get(0).get("id").asText();
        send(as(post("/api/v1/restaurant/requisitions/" + id + "/issue"), TENANT, owner, ROLE_OWNER),
                "{\"lines\":[{\"lineId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}", 400);
        JsonNode issued = send(as(post("/api/v1/restaurant/requisitions/" + id + "/issue"), TENANT, owner, ROLE_OWNER),
                "{\"lines\":[{\"lineId\":\"" + firstLine + "\",\"quantity\":4}],\"note\":\"rest tomorrow\"}", 200);
        assertThat(issued.get("status").asText()).isEqualTo("ISSUED");
        assertThat(issued.get("lines").get(0).get("quantityIssued").decimalValue()).isEqualByComparingTo("4");
        assertThat(issued.get("lines").get(1).get("quantityIssued").decimalValue()).isEqualByComparingTo("2");
        assertThat(issued.get("decisionNote").asText()).isEqualTo("rest tomorrow");
        // It is a record: the stock count has not moved. The ingredient leaves when the dish is sold.
        assertThat(stock(itemId)).isEqualByComparingTo("10");
        assertThat(stock(sunflower)).isEqualByComparingTo("5");
        // Issuing again changes nothing; it can no longer be refused or withdrawn.
        assertThat(send(as(post("/api/v1/restaurant/requisitions/" + id + "/issue"), TENANT, owner, ROLE_OWNER), "", 200)
                .get("lines").get(0).get("quantityIssued").decimalValue()).isEqualByComparingTo("4");
        send(as(post("/api/v1/restaurant/requisitions/" + id + "/refuse"), TENANT, owner, ROLE_OWNER), "{\"reason\":\"late\"}", 409);
        cashierPost("/api/v1/restaurant/requisitions/" + id + "/cancel", "", 409);
        JsonNode after = getJson(as(get("/api/v1/restaurant/requisitions").param("branchId", branchId), TENANT, owner, ROLE_OWNER), 200);
        assertThat(after.get("waiting")).isEmpty();
        assertThat(after.get("recent")).hasSize(1);
    }

    @Test
    void aRequestCanBeRefusedOrWithdrawnAndBadOnesAreTurnedAway() throws Exception {
        String base = "{\"branchId\":\"" + branchId + "\",\"clientKey\":\"";
        String one = "\",\"lines\":[{\"itemId\":\"" + itemId + "\",\"quantity\":1}]}";
        String refusedId = cashierPost("/api/v1/restaurant/requisitions", base + UUID.randomUUID() + one, 201).get("id").asText();
        send(as(post("/api/v1/restaurant/requisitions/" + refusedId + "/refuse"), TENANT, owner, ROLE_OWNER), "{\"reason\":\"\"}", 400);
        JsonNode refused = send(as(post("/api/v1/restaurant/requisitions/" + refusedId + "/refuse"), TENANT, owner, ROLE_OWNER),
                "{\"reason\":\"none left\"}", 200);
        assertThat(refused.get("status").asText()).isEqualTo("REFUSED");
        assertThat(refused.get("decisionNote").asText()).isEqualTo("none left");

        JsonNode second = cashierPost("/api/v1/restaurant/requisitions", base + UUID.randomUUID() + one, 201);
        assertThat(second.get("number").asLong()).isEqualTo(2);
        // Giving nothing at all is a refusal and must be said as one.
        send(as(post("/api/v1/restaurant/requisitions/" + second.get("id").asText() + "/issue"), TENANT, owner, ROLE_OWNER),
                "{\"lines\":[{\"lineId\":\"" + second.get("lines").get(0).get("id").asText() + "\",\"quantity\":0}]}", 400);
        assertThat(cashierPost("/api/v1/restaurant/requisitions/" + second.get("id").asText() + "/cancel", "", 200)
                .get("status").asText()).isEqualTo("CANCELLED");

        // Nothing asked for, the same product twice, a product that does not exist, a quantity of nothing.
        cashierPost("/api/v1/restaurant/requisitions", base + UUID.randomUUID() + "\",\"lines\":[]}", 400);
        cashierPost("/api/v1/restaurant/requisitions", base + UUID.randomUUID() + "\",\"lines\":[{\"itemId\":\"" + itemId
                + "\",\"quantity\":1},{\"itemId\":\"" + itemId + "\",\"quantity\":2}]}", 400);
        cashierPost("/api/v1/restaurant/requisitions", base + UUID.randomUUID() + "\",\"lines\":[{\"itemId\":\"" + UUID.randomUUID()
                + "\",\"quantity\":1}]}", 400);
        cashierPost("/api/v1/restaurant/requisitions", base + UUID.randomUUID() + "\",\"lines\":[{\"itemId\":\"" + itemId
                + "\",\"quantity\":0}]}", 400);
    }

    @Test
    void theKitchenFindsStockedProductsToAskForAndIsNotShownPrices() throws Exception {
        String sunflower = stocked("SKU-SUN", "Sunflower oil", "5", "3.00");

        // A cook holds only restaurant.kitchen.update, as the seeded kitchen_staff role does.
        String cookRole = "5a1e3333-3333-4333-8333-333333333301";
        role(cookRole, "kitchen_only_recipes");
        grant(cookRole, "5a1e1111-1111-4111-8111-111111111181");
        var cook = user(TENANT, "cook-recipes@svc-sale.test", "Cook", cookRole);

        JsonNode found = getJson(as(get("/api/v1/restaurant/requisitions/products").param("search", "sunflower"),
                TENANT, cook, cookRole), 200);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("id").asText()).isEqualTo(sunflower);
        assertThat(found.get(0).get("name").asText()).isEqualTo("Sunflower oil");
        assertThat(found.get(0).get("unit").asText()).isNotBlank();
        assertThat(found.get(0).has("price")).isFalse();

        // The catalogue list still needs catalog.items.read: the smaller answer is deliberate.
        mockMvc.perform(as(get("/api/v1/items").param("search", "sunflower"), TENANT, cook, cookRole))
                .andExpect(status().isForbidden());

        // A term shorter than two letters finds nothing; another business has none of it.
        assertThat(getJson(as(get("/api/v1/restaurant/requisitions/products").param("search", "s"),
                TENANT, cook, cookRole), 200)).isEmpty();
        assertThat(getJson(as(get("/api/v1/restaurant/requisitions/products").param("search", "sunflower"),
                OTHER, otherOwner, ROLE_OWNER), 200)).isEmpty();

        // Someone who may take orders but not ask the store is refused.
        String takerRole = "5a1e3333-3333-4333-8333-333333333302";
        role(takerRole, "recipes_ask_denied");
        grant(takerRole, "5a1e1111-1111-4111-8111-111111111182");
        var taker = user(TENANT, "taker-recipes@svc-sale.test", "Taker", takerRole);
        mockMvc.perform(as(get("/api/v1/restaurant/requisitions/products").param("search", "sunflower"),
                TENANT, taker, takerRole)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    /** A second stocked product, with a batch of its own, as the first one has. */
    private String stocked(String sku, String name, String quantity, String unitCost) {
        String goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();
        String id = itemCatalogService.createItem(TENANT,
                new zelisline.ub.catalog.api.dto.CreateItemRequest(
                        sku, null, name, null, goodsTypeId, null, null, null,
                        false, false, true,
                        null, null, null, null, null, null, null, null, null, null, true, null, null, null, null),
                null).body().id();
        zelisline.ub.purchasing.domain.InventoryBatch batch = new zelisline.ub.purchasing.domain.InventoryBatch();
        batch.setId(UUID.randomUUID().toString());
        batch.setBusinessId(TENANT);
        batch.setBranchId(branchId);
        batch.setItemId(id);
        batch.setBatchNumber("BN-" + sku);
        batch.setSourceType("test");
        batch.setSourceId(UUID.randomUUID().toString());
        batch.setInitialQuantity(new BigDecimal(quantity));
        batch.setQuantityRemaining(new BigDecimal(quantity));
        batch.setUnitCost(new BigDecimal(unitCost));
        batch.setReceivedAt(java.time.Instant.parse("2026-02-10T12:00:00Z"));
        batch.setExpiryDate(java.time.LocalDate.of(2030, 4, 1));
        batch.setStatus("active");
        inventoryBatchRepository.save(batch);
        var item = itemRepository.findById(id).orElseThrow();
        item.setCurrentStock(new BigDecimal(quantity));
        item.setBuyingPrice(new BigDecimal(unitCost));
        itemRepository.save(item);
        return id;
    }

    private BigDecimal stock(String id) {
        return itemRepository.findById(id).orElseThrow().getCurrentStock();
    }

    private static String dish(String menuItemId, int qty, String unitPrice, String... optionIds) {
        StringBuilder options = new StringBuilder();
        for (String option : optionIds) {
            if (options.length() > 0) {
                options.append(',');
            }
            options.append('"').append(option).append('"');
        }
        return "{\"kind\":\"DISH\",\"menuItemId\":\"" + menuItemId + "\",\"quantity\":" + qty + ",\"unitPrice\":" + unitPrice
                + ",\"modifierOptionIds\":[" + options + "]}";
    }

    private JsonNode ownerPost(String path, String body) throws Exception {
        return send(as(post(path), TENANT, owner, ROLE_OWNER), body, 201);
    }

    private JsonNode ownerPut(String path, String body, int expected) throws Exception {
        return send(as(put(path), TENANT, owner, ROLE_OWNER), body, expected);
    }

    private JsonNode cashierPost(String path, String body, int expected) throws Exception {
        return send(as(post(path), TENANT, cashier, ROLE_CASHIER), body, expected);
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
        if (!body.isEmpty()) {
            request = request.content(body);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().is(expected)).andReturn();
        String text = result.getResponse().getContentAsString();
        return text.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(text);
    }

    private JsonNode getJson(MockHttpServletRequestBuilder request, int expected) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().is(expected)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
