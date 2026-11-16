package zelisline.ub.restaurant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.identity.domain.User;
import zelisline.ub.pricing.api.dto.PostSellingPriceRequest;
import zelisline.ub.pricing.application.PricingService;
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
import zelisline.ub.restaurant.repository.RestaurantSettingsRepository;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.receipt.SaleReceiptService;
import zelisline.ub.serviceops.api.ServiceSaleTestSupport;
import zelisline.ub.serviceops.application.StaffEarningsService;
import zelisline.ub.serviceops.repository.ServiceResourceRepository;

/**
 * The rest of the restaurant scope: service charge and pooled tips, packaging and delivery,
 * joining orders, wastage of cooked food, prepared batches, menus by time and happy hour, the
 * settings behind them, and ordering from a table's QR code with nobody signed in.
 */
class RestaurantExtrasIT extends ServiceSaleTestSupport {

    private static final String ROLE_KITCHEN = "5a1e2222-2222-4222-8222-222222222203";
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

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
    private RestaurantSettingsRepository settingsRepository;
    @Autowired
    private PricingService pricingService;
    @Autowired
    private StaffEarningsService staffEarningsService;
    @Autowired
    private SaleReceiptService saleReceiptService;

    private User cook;
    private String burger;
    private String table1;
    private String table2;

    @BeforeEach
    void seedExtras() throws Exception {
        settingsRepository.deleteAll();
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
        role(ROLE_KITCHEN, "kitchen_test");
        String[][] perms = {
                {"80", "restaurant.menu.manage"}, {"81", "restaurant.kitchen.update"},
                {"82", "restaurant.orders.write"}, {"83", "restaurant.orders.void_sent"},
                {"84", "restaurant.bills.comp"}};
        for (String[] perm : perms) {
            String id = "5a1e1111-1111-4111-8111-1111111111" + perm[0];
            permissionRepository.save(perm(id, perm[1]));
            grant(ROLE_OWNER, id);
        }
        grant(ROLE_CASHIER, "5a1e1111-1111-4111-8111-111111111182");
        grant(ROLE_KITCHEN, "5a1e1111-1111-4111-8111-111111111181");
        cook = user(TENANT, "cook@svc-sale.test", "Cook", ROLE_KITCHEN);

        burger = ownerPost("/api/v1/restaurant/menu/items", "{\"name\":\"Burger\",\"kind\":\"DISH\",\"price\":400}", 201)
                .get("id").asText();
        table1 = ownerPost("/api/v1/restaurant/tables", "{\"branchId\":\"" + branchId + "\",\"name\":\"Table 1\"}", 201)
                .get("id").asText();
        table2 = ownerPost("/api/v1/restaurant/tables", "{\"branchId\":\"" + branchId + "\",\"name\":\"Table 2\"}", 201)
                .get("id").asText();
    }

    // ------------------------------------------------------------------ settings

    @Test
    void settingsStartEmptyAreValidatedAndBelongToOneBusiness() throws Exception {
        JsonNode blank = getJson(as(get("/api/v1/restaurant/settings"), TENANT, cashier, ROLE_CASHIER), 200);
        assertThat(blank.get("serviceChargePercent").isNull()).isTrue();
        assertThat(blank.get("tipsPooled").asBoolean()).isFalse();
        assertThat(blank.get("qrOrdering").asBoolean()).isFalse();

        mockMvc.perform(as(put("/api/v1/restaurant/settings"), TENANT, cashier, ROLE_CASHIER).content("{\"tipsPooled\":true}"))
                .andExpect(status().isForbidden());
        for (String bad : List.of("{\"serviceChargePercent\":45}", "{\"serviceChargePercent\":-1}",
                "{\"dayStartsAtMinute\":1500}", "{\"packagingCharge\":-5}")) {
            mockMvc.perform(as(put("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER).content(bad))
                    .andExpect(status().isBadRequest());
        }
        JsonNode saved = send(as(put("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER),
                "{\"serviceChargePercent\":10,\"packagingCharge\":20}", 200);
        assertThat(saved.get("serviceChargePercent").decimalValue()).isEqualByComparingTo("10");
        // A field left out is unchanged; zero switches a charge off.
        JsonNode again = send(as(put("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER),
                "{\"serviceChargePercent\":0}", 200);
        assertThat(again.get("serviceChargePercent").isNull()).isTrue();
        assertThat(again.get("packagingCharge").decimalValue()).isEqualByComparingTo("20");
        assertThat(getJson(as(get("/api/v1/restaurant/settings"), OTHER, otherOwner, ROLE_OWNER), 200)
                .get("packagingCharge").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ charges and tips

    @Test
    void aServiceChargeIsAddedToATablesBillAsItsOwnLineOnTheSale() throws Exception {
        send(as(put("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER), "{\"serviceChargePercent\":10}", 200);
        String orderId = seat(table1);
        addAndSend(orderId, burger, 3);
        JsonNode bill = cashierPost("/open-orders/" + orderId + "/bills", "", 201).get("bills").get(0);
        assertThat(bill.get("total").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(bill.get("serviceCharge").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(bill.get("toPay").decimalValue()).isEqualByComparingTo("1320.00");
        // Paying only for the food is refused: the bill is 1,320.
        cashierPost("/open-orders/" + orderId + "/bills/" + bill.get("id").asText() + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":1200}]}", 400);
        JsonNode paid = cashierPost("/open-orders/" + orderId + "/bills/" + bill.get("id").asText() + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":1320}]}", 200);
        assertThat(paid.get("status").asText()).isEqualTo("completed");
        assertThat(paid.get("bills").get(0).get("serviceCharge").decimalValue()).isEqualByComparingTo("120.00");

        Sale sale = saleRepository.findAll().get(0);
        assertThat(sale.getGrandTotal()).isEqualByComparingTo("1320.00");
        var charge = saleItemRepository.findAll().stream().filter(i -> "CHARGE".equals(i.getLineKind())).findFirst().orElseThrow();
        assertThat(charge.getLineLabel()).isEqualTo("Service charge");
        assertThat(charge.getLineTotal()).isEqualByComparingTo("120.00");
        List<JournalLine> journal = journalLineRepository.findByJournalEntryId(sale.getJournalEntryId());
        assertBalanced(journal);
        assertThat(credit(journal, LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("1320.00");
        String receipt = new String(saleReceiptService.buildEscPos(TENANT, sale.getId(), 80), StandardCharsets.ISO_8859_1);
        assertThat(receipt).contains("Service charge").contains("Order " + paid.get("orderNumber").asLong());

        // A changed setting does not rewrite a bill already paid, and a takeaway carries no service charge.
        send(as(put("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER), "{\"serviceChargePercent\":15}", 200);
        assertThat(getJson(as(get("/api/v1/restaurant/open-orders/" + orderId), TENANT, cashier, ROLE_CASHIER), 200)
                .get("bills").get(0).get("serviceCharge").decimalValue()).isEqualByComparingTo("120.00");

        // Nobody can add a charge of their own through the ordinary sale.
        postSale("ch-" + UUID.randomUUID(), "{\"kind\":\"CHARGE\",\"label\":\"Service charge\",\"quantity\":1,\"unitPrice\":500}",
                "500", 400);
    }

    @Test
    void pooledTipsAreSharedAmongEveryoneWhoServesAndAddUpToTheTip() throws Exception {
        String mary = createStaff(TENANT, owner, "Mary");
        String peter = createStaff(TENANT, owner, "Peter");
        send(as(put("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER), "{\"tipsPooled\":true}", 200);
        String orderId = seat(table1);
        // John serves the table; John, Mary and Peter all serve customers.
        send(as(patch("/api/v1/restaurant/open-orders/" + orderId), TENANT, cashier, ROLE_CASHIER),
                "{\"servedByStaffProfileId\":\"" + john + "\"}", 200);
        addAndSend(orderId, burger, 1);
        String billId = cashierPost("/open-orders/" + orderId + "/bills", "", 201).get("bills").get(0).get("id").asText();
        cashierPost("/open-orders/" + orderId + "/bills/" + billId + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":500}],\"tip\":100}", 200);
        // 100 between three is 33.33 each; the cent left over goes to whoever served.
        assertThat(staffEarningsService.balance(TENANT, mary)).isEqualByComparingTo("33.33");
        assertThat(staffEarningsService.balance(TENANT, peter)).isEqualByComparingTo("33.33");
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("33.34");
        assertThat(saleRepository.findAll().get(0).getTipTotal()).isEqualByComparingTo("100.00");
    }

    @Test
    void aServiceChargeMeantForStaffIsSharedLikeATipAndIsNotTheShopsIncome() throws Exception {
        send(as(put("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER),
                "{\"serviceChargePercent\":10,\"serviceChargeTo\":\"STAFF\"}", 200);
        assertThat(getJson(as(get("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER), 200)
                .get("serviceChargeTo").asText()).isEqualTo("STAFF");
        send(as(put("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER), "{\"serviceChargeTo\":\"EVERYONE\"}", 400);

        String orderId = seat(table1);
        addAndSend(orderId, burger, 1);
        JsonNode bill = cashierPost("/open-orders/" + orderId + "/bills", "", 201).get("bills").get(0);
        // The customer is asked for the same amount whoever the charge is for.
        assertThat(bill.get("serviceCharge").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(bill.get("toPay").decimalValue()).isEqualByComparingTo("440.00");
        send(as(patch("/api/v1/restaurant/open-orders/" + orderId), TENANT, cashier, ROLE_CASHIER),
                "{\"servedByStaffProfileId\":\"" + john + "\"}", 200);
        // With a tip on top: the charge and the tip both go to whoever served.
        cashierPost("/open-orders/" + orderId + "/bills/" + bill.get("id").asText() + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":460}],\"tip\":20}", 200);
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("60.00");
        // The shop's income is the food alone; the bill still remembers what it charged.
        assertThat(accountBalance(LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("400.00");
        assertThat(saleRepository.findAll().get(0).getTipTotal()).isEqualByComparingTo("60.00");
        assertThat(getJson(as(get("/api/v1/restaurant/open-orders/" + orderId), TENANT, cashier, ROLE_CASHIER), 200)
                .get("bills").get(0).get("serviceCharge").decimalValue()).isEqualByComparingTo("40.00");
    }

    @Test
    void aTakeawayIsChargedForPackagingOnceAndADeliveryForItsFee() throws Exception {
        send(as(put("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER),
                "{\"packagingCharge\":20,\"deliveryFee\":150,\"serviceChargePercent\":10}", 200);
        // A takeaway wanted for a time is open at once, not a booking.
        String wanted = java.time.Instant.now().plusSeconds(1800).toString();
        JsonNode takeaway = cashierPost("/open-orders", "{\"branchId\":\"" + branchId + "\",\"channel\":\"TAKEAWAY\","
                + "\"customerName\":\"Lenah\",\"scheduledStart\":\"" + wanted + "\",\"clientOrderId\":\"" + UUID.randomUUID() + "\"}", 201);
        assertThat(takeaway.get("booked").asBoolean()).isFalse();
        String orderId = takeaway.get("id").asText();
        JsonNode added = cashierPost("/open-orders/" + orderId + "/lines", "{\"lines\":[{\"clientLineId\":\"a\",\"menuItemId\":\""
                + burger + "\",\"quantity\":2}]}", 200);
        String lineId = added.get("lines").get(0).get("id").asText();
        cashierPost("/open-orders/" + orderId + "/send", "", 200);
        // Split in two: the packaging rides on the first bill only, and there is no service charge.
        JsonNode first = cashierPost("/open-orders/" + orderId + "/bills",
                "{\"lines\":[{\"orderLineId\":\"" + lineId + "\",\"quantity\":1}]}", 201);
        JsonNode second = cashierPost("/open-orders/" + orderId + "/bills", "", 201);
        assertThat(second.get("bills").get(0).get("toPay").decimalValue()).isEqualByComparingTo("420.00");
        assertThat(second.get("bills").get(0).get("serviceCharge").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(second.get("bills").get(1).get("toPay").decimalValue()).isEqualByComparingTo("400.00");
        cashierPost("/open-orders/" + orderId + "/bills/" + first.get("bills").get(0).get("id").asText() + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":420}]}", 200);
        JsonNode done = cashierPost("/open-orders/" + orderId + "/bills/" + second.get("bills").get(1).get("id").asText() + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":400}]}", 200);
        assertThat(done.get("status").asText()).isEqualTo("completed");
        assertThat(accountBalance(LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("820.00");

        // A delivery: its own fee beats the usual one; a rider is named; it leaves and arrives.
        JsonNode delivery = cashierPost("/open-orders", "{\"branchId\":\"" + branchId + "\",\"channel\":\"DELIVERY\","
                + "\"deliveryAddress\":\"Mirema Drive, gate 4\",\"deliveryFee\":200,\"clientOrderId\":\"" + UUID.randomUUID() + "\"}", 201);
        String deliveryId = delivery.get("id").asText();
        assertThat(delivery.get("deliveryFee").decimalValue()).isEqualByComparingTo("200");
        cashierPost("/open-orders/" + deliveryId + "/lines", "{\"lines\":[{\"clientLineId\":\"a\",\"menuItemId\":\""
                + burger + "\",\"quantity\":1}]}", 200);
        cashierPost("/open-orders/" + deliveryId + "/send", "", 200);
        JsonNode bill = cashierPost("/open-orders/" + deliveryId + "/bills", "", 201).get("bills").get(0);
        assertThat(bill.get("packagingCharge").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(bill.get("deliveryFee").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(bill.get("toPay").decimalValue()).isEqualByComparingTo("620.00");
        // Paid when it was ordered, as most deliveries are: the order is closed, but it stays on
        // the floor until someone marks it delivered, so it can still be sent out.
        cashierPost("/open-orders/" + deliveryId + "/bills/" + bill.get("id").asText() + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":620}]}", 200);
        JsonNode paidCard = deliveryCard(deliveryId);
        assertThat(paidCard).isNotNull();
        assertThat(paidCard.get("settled").asBoolean()).isTrue();
        assertThat(paidCard.get("dispatchedAt").isNull()).isTrue();
        // Another branch's floor, and another business, never see it.
        assertThat(getJson(as(get("/api/v1/restaurant/floor").param("branchId", branchId), OTHER, otherOwner, ROLE_OWNER), 200)
                .toString()).doesNotContain(deliveryId);
        JsonNode out = cashierPost("/open-orders/" + deliveryId + "/delivery", "{\"riderName\":\"Otieno\",\"dispatched\":true}", 200);
        assertThat(out.get("riderName").asText()).isEqualTo("Otieno");
        assertThat(out.get("dispatchedAt").isNull()).isFalse();
        assertThat(deliveryCard(deliveryId).get("dispatchedAt").isNull()).isFalse();
        assertThat(cashierPost("/open-orders/" + deliveryId + "/delivery", "{\"delivered\":true}", 200)
                .get("deliveredAt").isNull()).isFalse();
        // Delivered: now it is done, and off the floor.
        assertThat(deliveryCard(deliveryId)).isNull();
        // A table's order has no rider.
        cashierPost("/open-orders/" + seat(table1) + "/delivery", "{\"riderName\":\"X\"}", 409);
    }

    // ------------------------------------------------------------------ joining orders

    @Test
    void twoOrdersAreJoinedAndTheKitchensTicketsFollow() throws Exception {
        String a = seat(table1);
        String b = seat(table2);
        addAndSend(a, burger, 1);
        addAndSend(b, burger, 2);
        long intoNumber = getJson(as(get("/api/v1/restaurant/open-orders/" + b), TENANT, cashier, ROLE_CASHIER), 200)
                .get("orderNumber").asLong();

        JsonNode joined = cashierPost("/open-orders/" + a + "/merge", "{\"intoOrderId\":\"" + b + "\"}", 200);
        assertThat(joined.get("id").asText()).isEqualTo(b);
        assertThat(joined.get("lines")).hasSize(2);
        assertThat(joined.get("total").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(joined.get("lines").get(1).get("stage").asText()).isEqualTo("NEW");
        JsonNode gone = getJson(as(get("/api/v1/restaurant/open-orders/" + a), TENANT, cashier, ROLE_CASHIER), 200);
        assertThat(gone.get("status").asText()).isEqualTo("cancelled");
        assertThat(gone.get("lines")).isEmpty();
        // Both kitchen tickets now belong to the order that remains.
        assertThat(ticketRepository.findAll()).allMatch(t -> b.equals(t.getOrderId()) && t.getOrderNumber() == intoNumber);
        // The joined order is billed as one.
        assertThat(cashierPost("/open-orders/" + b + "/bills", "", 201).get("bills").get(0).get("total").decimalValue())
                .isEqualByComparingTo("1200.00");

        // An order with a bill, an order with itself, and another business's order are refused.
        String c = seat(table1);
        addAndSend(c, burger, 1);
        cashierPost("/open-orders/" + b + "/merge", "{\"intoOrderId\":\"" + c + "\"}", 409);
        cashierPost("/open-orders/" + c + "/merge", "{\"intoOrderId\":\"" + c + "\"}", 400);
        cashierPost("/open-orders/" + c + "/merge", "{\"intoOrderId\":\"" + UUID.randomUUID() + "\"}", 404);
        mockMvc.perform(as(post("/api/v1/restaurant/open-orders/" + c + "/merge"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"intoOrderId\":\"" + b + "\"}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ wastage and prepared batches

    @Test
    void foodThatWasCookedAndThenCancelledComesOffTheCountAsWastage() throws Exception {
        ownerPut("/api/v1/restaurant/menu/items/" + burger + "/recipe",
                "{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":2}]}", 200);
        var ingredient = itemRepository.findById(itemId).orElseThrow();
        ingredient.setBuyingPrice(new BigDecimal("2.00"));
        itemRepository.save(ingredient);
        String orderId = seat(table1);
        JsonNode added = cashierPost("/open-orders/" + orderId + "/lines", "{\"lines\":[{\"clientLineId\":\"a\",\"menuItemId\":\""
                + burger + "\",\"quantity\":1},{\"clientLineId\":\"b\",\"menuItemId\":\"" + burger + "\",\"quantity\":2}]}", 200);
        String untouched = added.get("lines").get(0).get("id").asText();
        String cooked = added.get("lines").get(1).get("id").asText();
        cashierPost("/open-orders/" + orderId + "/send", "", 200);
        // The kitchen has made the second line; it has not started the first.
        JsonNode ticket = getJson(as(get("/api/v1/restaurant/kitchen/tickets").param("branchId", branchId), TENANT, cook, ROLE_KITCHEN), 200)
                .get("tickets").get(0);
        send(as(post("/api/v1/restaurant/kitchen/lines/" + ticket.get("lines").get(1).get("id").asText() + "/stage"),
                TENANT, cook, ROLE_KITCHEN), "{\"stage\":\"READY\"}", 200);

        ownerPost("/api/v1/restaurant/open-orders/" + orderId + "/lines/" + untouched + "/cancel", "{\"reason\":\"changed mind\"}", 200);
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock()).isEqualByComparingTo("10");
        ownerPost("/api/v1/restaurant/open-orders/" + orderId + "/lines/" + cooked + "/cancel", "{\"reason\":\"customer left\"}", 200);
        // Two burgers were cooked: four of the ingredient are gone, at 2.00 each, as shrinkage not cost of sales.
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock()).isEqualByComparingTo("6");
        assertThat(accountBalance(LedgerAccountCodes.INVENTORY_SHRINKAGE)).isEqualByComparingTo("-8.00");
        assertThat(accountBalance(LedgerAccountCodes.INVENTORY)).isEqualByComparingTo("8.00");
        assertThat(accountBalance(LedgerAccountCodes.COST_OF_GOODS_SOLD)).isEqualByComparingTo("0.00");
        // Cancelling it again wastes nothing more.
        ownerPost("/api/v1/restaurant/open-orders/" + orderId + "/lines/" + cooked + "/cancel", "{\"reason\":\"again\"}", 200);
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock()).isEqualByComparingTo("6");
    }

    @Test
    void aPreparedBatchTurnsIngredientsIntoStockOfWhatWasMade() throws Exception {
        var ingredient = itemRepository.findById(itemId).orElseThrow();
        ingredient.setBuyingPrice(new BigDecimal("2.00"));
        itemRepository.save(ingredient);
        String goodsTypeId = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();
        String soup = itemCatalogService.createItem(TENANT, new CreateItemRequest(
                "SKU-SOUP", null, "Soup base", null, goodsTypeId, null, null, null, false, false, true,
                null, null, null, null, null, null, null, null, null, null, true, null, null, null, null), null).body().id();

        // No recipe yet: nothing can be made.
        send(as(post("/api/v1/restaurant/prep/" + soup + "/batches"), TENANT, cook, ROLE_KITCHEN),
                "{\"branchId\":\"" + branchId + "\",\"quantity\":3}", 400);
        // One unit of soup takes two of the ingredient. It cannot be made from itself.
        ownerPut("/api/v1/restaurant/prep/" + soup + "/recipe", "{\"ingredients\":[{\"itemId\":\"" + soup + "\",\"quantity\":1}]}", 400);
        ownerPut("/api/v1/restaurant/prep/" + soup + "/recipe", "{\"ingredients\":[{\"itemId\":\"" + itemId + "\",\"quantity\":2}]}", 200);
        mockMvc.perform(as(put("/api/v1/restaurant/prep/" + soup + "/recipe"), TENANT, cook, ROLE_KITCHEN)
                        .content("{\"ingredients\":[]}"))
                .andExpect(status().isForbidden());

        JsonNode made = send(as(post("/api/v1/restaurant/prep/" + soup + "/batches"), TENANT, cook, ROLE_KITCHEN),
                "{\"branchId\":\"" + branchId + "\",\"quantity\":3}", 201);
        assertThat(made.get("cost").decimalValue()).isEqualByComparingTo("12.00");
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock()).isEqualByComparingTo("4");
        assertThat(itemRepository.findById(soup).orElseThrow().getCurrentStock()).isEqualByComparingTo("3");
        // Value moved from one product to the other: nothing earned, nothing lost, equity untouched.
        assertThat(accountBalance(LedgerAccountCodes.INVENTORY)).isEqualByComparingTo("0.00");
        assertThat(accountBalance(LedgerAccountCodes.OPENING_BALANCE_EQUITY)).isEqualByComparingTo("0.00");

        // The batch is then an ingredient like any other, costed at what it took to make.
        ownerPut("/api/v1/restaurant/menu/items/" + burger + "/recipe", "{\"ingredients\":[{\"itemId\":\"" + soup + "\",\"quantity\":1}]}", 200);
        JsonNode sale = postSale("pb-" + UUID.randomUUID(), "{\"kind\":\"DISH\",\"menuItemId\":\"" + burger
                + "\",\"quantity\":1,\"unitPrice\":400,\"modifierOptionIds\":[]}", "400", 201);
        assertThat(saleItemRepository.findById(sale.get("items").get(0).get("id").asText()).orElseThrow().getCostTotal())
                .isEqualByComparingTo("4.00");
        mockMvc.perform(as(post("/api/v1/restaurant/prep/" + soup + "/batches"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"quantity\":1}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ menus by time, happy hour

    @Test
    void aDishHasServingHoursAndAHappyHourPrice() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(NAIROBI);
        int minute = now.getHour() * 60 + now.getMinute();
        int before = (minute + 1440 - 120) % 1440;
        int after = (minute + 120) % 1440;
        int later = (minute + 240) % 1440;
        String patchPath = "/api/v1/restaurant/menu/items/" + burger;

        // Happy hour is running: the dish costs 300, and the till must charge 300.
        JsonNode happy = send(as(patch(patchPath), TENANT, owner, ROLE_OWNER),
                "{\"happyPrice\":300,\"happyFromMinute\":" + before + ",\"happyToMinute\":" + after + "}", 200);
        assertThat(happy.get("happyNow").asBoolean()).isTrue();
        assertThat(happy.get("price").decimalValue()).isEqualByComparingTo("300");
        assertThat(happy.get("regularPrice").decimalValue()).isEqualByComparingTo("400");
        postSale("h1-" + UUID.randomUUID(), dish(burger, "400"), "400", 409);
        postSale("h2-" + UUID.randomUUID(), dish(burger, "300"), "300", 201);
        // On a day it does not run, the ordinary price applies.
        int otherDay = now.getDayOfWeek().getValue() % 7 + 1;
        send(as(patch(patchPath), TENANT, owner, ROLE_OWNER), "{\"happyPrice\":300,\"happyFromMinute\":" + before
                + ",\"happyToMinute\":" + after + ",\"happyDays\":\"" + otherDay + "\"}", 200);
        postSale("h3-" + UUID.randomUUID(), dish(burger, "400"), "400", 201);
        // Half a happy hour is refused, and it is cleared in one go.
        send(as(patch(patchPath), TENANT, owner, ROLE_OWNER), "{\"happyPrice\":250}", 400);
        assertThat(send(as(patch(patchPath), TENANT, owner, ROLE_OWNER), "{\"clearHappyHour\":true}", 200)
                .get("happyPrice").isNull()).isTrue();

        // Outside its serving hours it cannot be ordered, at the counter or at a table.
        JsonNode closed = send(as(patch(patchPath), TENANT, owner, ROLE_OWNER),
                "{\"serveFromMinute\":" + after + ",\"serveToMinute\":" + later + "}", 200);
        assertThat(closed.get("servedNow").asBoolean()).isFalse();
        assertThat(postSale("s1-" + UUID.randomUUID(), dish(burger, "400"), "400", 400).toString()).contains("not served");
        String orderId = seat(table1);
        cashierPost("/open-orders/" + orderId + "/lines", "{\"lines\":[{\"clientLineId\":\"a\",\"menuItemId\":\"" + burger
                + "\",\"quantity\":1}]}", 400);
        send(as(patch(patchPath), TENANT, owner, ROLE_OWNER), "{\"serveFromMinute\":10}", 400);
        send(as(patch(patchPath), TENANT, owner, ROLE_OWNER), "{\"clearServingHours\":true}", 200);
        postSale("s2-" + UUID.randomUUID(), dish(burger, "400"), "400", 201);
    }

    @Test
    void aNamedMenuHidesItsDishesOutsideItsHoursEvenWhenTheDishHasNoHours() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(NAIROBI);
        int minute = now.getHour() * 60 + now.getMinute();
        // A window that does not contain now: starts two hours from now, ends four.
        int closedFrom = (minute + 120) % 1440;
        int closedTo = (minute + 240) % 1440;
        // A window that does contain now: started two hours ago, ends two hours on.
        int openFrom = (minute + 1440 - 120) % 1440;
        int openTo = (minute + 120) % 1440;

        // A "Breakfast" heading, closed right now, with the burger under it. The burger keeps no
        // serving hours of its own, so only the heading decides whether it can be ordered.
        String breakfast = ownerPost("/api/v1/restaurant/menu/categories",
                "{\"name\":\"Breakfast\",\"serveFromMinute\":" + closedFrom + ",\"serveToMinute\":" + closedTo + "}", 201)
                .get("id").asText();
        String patchPath = "/api/v1/restaurant/menu/items/" + burger;
        JsonNode moved = send(as(patch(patchPath), TENANT, owner, ROLE_OWNER),
                "{\"categoryId\":\"" + breakfast + "\"}", 200);
        assertThat(moved.get("servedNow").asBoolean()).isFalse();

        // Off at the counter and at a table, though the dish itself has no hours.
        assertThat(postSale("nm1-" + UUID.randomUUID(), dish(burger, "400"), "400", 400).toString())
                .contains("not served");
        String orderId = seat(table1);
        cashierPost("/open-orders/" + orderId + "/lines", "{\"lines\":[{\"clientLineId\":\"a\",\"menuItemId\":\""
                + burger + "\",\"quantity\":1}]}", 400);

        // Open the heading's window to cover now, and the same dish can be ordered again.
        JsonNode open = send(as(put("/api/v1/restaurant/menu/categories/" + breakfast), TENANT, owner, ROLE_OWNER),
                "{\"name\":\"Breakfast\",\"serveFromMinute\":" + openFrom + ",\"serveToMinute\":" + openTo + "}", 200);
        assertThat(open.get("serveFromMinute").asInt()).isEqualTo(openFrom);
        assertThat(postSale("nm2-" + UUID.randomUUID(), dish(burger, "400"), "400", 201)).isNotNull();

        // A half-set window is refused; clearing it leaves the heading all day.
        send(as(put("/api/v1/restaurant/menu/categories/" + breakfast), TENANT, owner, ROLE_OWNER),
                "{\"name\":\"Breakfast\",\"serveFromMinute\":10}", 400);
        JsonNode cleared = send(as(put("/api/v1/restaurant/menu/categories/" + breakfast), TENANT, owner, ROLE_OWNER),
                "{\"name\":\"Breakfast\",\"clearServingHours\":true}", 200);
        assertThat(cleared.get("serveFromMinute").isNull()).isTrue();
        postSale("nm3-" + UUID.randomUUID(), dish(burger, "400"), "400", 201);

        // Another business cannot set hours on this one's heading, though its owner holds the same
        // permission: the heading is not found for them.
        send(as(put("/api/v1/restaurant/menu/categories/" + breakfast), OTHER, otherOwner, ROLE_OWNER),
                "{\"name\":\"Breakfast\",\"serveFromMinute\":" + openFrom + ",\"serveToMinute\":" + openTo + "}", 404);
    }

    @Test
    void aBusinessDayCanStartAfterMidnight() throws Exception {
        postSale("d1-" + UUID.randomUUID(), dish(burger, "400"), "400", 201);
        ZonedDateTime now = ZonedDateTime.now(NAIROBI);
        int minute = now.getHour() * 60 + now.getMinute();
        if (minute >= 1438) {
            return; // too close to midnight to set a day start after "now"
        }
        // The day starts later than now, so a sale made now belongs to yesterday's trading day.
        send(as(put("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER), "{\"dayStartsAtMinute\":" + (minute + 2) + "}", 200);
        String today = LocalDate.now(NAIROBI).toString();
        String yesterday = LocalDate.now(NAIROBI).minusDays(1).toString();
        assertThat(report(today, today).get("totalRevenue").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(report(yesterday, yesterday).get("totalRevenue").decimalValue()).isEqualByComparingTo("400.00");
    }

    // ------------------------------------------------------------------ ordering from a table's QR code

    @Test
    void aCustomerOrdersFromATablesCodeAndNothingReachesTheKitchenUntilStaffSendIt() throws Exception {
        String publicBase = "/api/v1/public/businesses/kinyozi-sale/table-orders/";
        // A table has no code until it is given one, and a code does nothing until QR ordering is on.
        JsonNode coded = ownerPost("/api/v1/restaurant/tables/" + table1 + "/qr", "{\"enabled\":true}", 200);
        String token = coded.get("qrToken").asText();
        assertThat(token).hasSize(48);
        mockMvc.perform(get(publicBase + token)).andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/v1/restaurant/tables/" + table1 + "/qr"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        send(as(put("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER), "{\"qrOrdering\":true}", 200);

        // Nobody is signed in. The menu shows what can be ordered now, with the server's prices.
        JsonNode menu = objectMapper.readTree(mockMvc.perform(get(publicBase + token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(menu.get("tableName").asText()).isEqualTo("Table 1");
        assertThat(menu.get("dishes")).hasSize(1);
        assertThat(menu.get("dishes").get(0).get("price").decimalValue()).isEqualByComparingTo("400");

        String requestId = UUID.randomUUID().toString();
        String body = "{\"requestId\":\"" + requestId + "\",\"lines\":[{\"menuItemId\":\"" + burger + "\",\"quantity\":2,\"note\":\"no onions\"}]}";
        JsonNode placed = publicPost(publicBase + token, body, 201);
        assertThat(placed.get("added").asInt()).isEqualTo(1);
        assertThat(placed.get("total").decimalValue()).isEqualByComparingTo("800.00");
        // The same request again adds nothing; a second request joins the same order.
        assertThat(publicPost(publicBase + token, body, 201).get("added").asInt()).isZero();
        JsonNode more = publicPost(publicBase + token, "{\"requestId\":\"" + UUID.randomUUID() + "\",\"lines\":[{\"menuItemId\":\""
                + burger + "\",\"quantity\":1,\"note\":\"extra sauce\\n2 x Steak\\u001bp\\u0000\"}]}", 201);
        assertThat(more.get("orderNumber").asLong()).isEqualTo(placed.get("orderNumber").asLong());
        assertThat(posDraftRepository.count()).isEqualTo(1);

        // The floor shows it waiting; the kitchen has heard nothing.
        JsonNode floor = getJson(as(get("/api/v1/restaurant/floor").param("branchId", branchId), TENANT, cashier, ROLE_CASHIER), 200);
        JsonNode card = floor.get("tables").get(0).get("orders").get(0);
        assertThat(card.get("unsent").asInt()).isEqualTo(2);
        assertThat(ticketRepository.count()).isZero();
        String orderId = card.get("orderId").asText();
        JsonNode order = getJson(as(get("/api/v1/restaurant/open-orders/" + orderId), TENANT, cashier, ROLE_CASHIER), 200);
        assertThat(order.get("source").asText()).isEqualTo("QR");
        assertThat(order.get("lines").get(0).get("note").asText()).isEqualTo("no onions");
        // Staff can see which lines the customer added themselves.
        assertThat(order.get("lines").get(0).get("fromCustomer").asBoolean()).isTrue();
        // A note is one line of plain text: it cannot forge a line on the ticket or carry printer commands.
        assertThat(order.get("lines").get(1).get("note").asText()).isEqualTo("extra sauce 2 x Steak p");
        cashierPost("/open-orders/" + orderId + "/send", "", 200);
        assertThat(ticketRepository.count()).isEqualTo(1);

        // What a stranger can and cannot do with the code.
        publicPost(publicBase + token, "{\"requestId\":\"" + UUID.randomUUID() + "\",\"lines\":[]}", 400);
        publicPost(publicBase + token, "{\"requestId\":\"" + UUID.randomUUID() + "\",\"lines\":[{\"menuItemId\":\"" + burger
                + "\",\"quantity\":500}]}", 400);
        publicPost(publicBase + token, "{\"requestId\":\"" + UUID.randomUUID() + "\",\"lines\":[{\"menuItemId\":\""
                + UUID.randomUUID() + "\",\"quantity\":1}]}", 400);
        publicPost(publicBase + token, "{\"requestId\":\"x\",\"lines\":[{\"menuItemId\":\"" + burger + "\",\"quantity\":1}]}", 400);
        // A code used under another shop's name, a made-up code, and a code that was replaced all look the same.
        mockMvc.perform(get("/api/v1/public/businesses/other-sale/table-orders/" + token)).andExpect(status().isNotFound());
        mockMvc.perform(get(publicBase + "0".repeat(48))).andExpect(status().isNotFound());
        String fresh = ownerPost("/api/v1/restaurant/tables/" + table1 + "/qr", "{\"enabled\":true}", 200).get("qrToken").asText();
        assertThat(fresh).isNotEqualTo(token);
        mockMvc.perform(get(publicBase + token)).andExpect(status().isNotFound());
        publicPost(publicBase + token, body, 404);
        mockMvc.perform(get(publicBase + fresh)).andExpect(status().isOk());
        // Switched off again, the new code stops too.
        send(as(put("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER), "{\"qrOrdering\":false}", 200);
        mockMvc.perform(get(publicBase + fresh)).andExpect(status().isNotFound());
    }

    @Test
    void theListOfPeopleWhoCanServeIsThoseMarkedAsServingCustomers() throws Exception {
        JsonNode servers = getJson(as(get("/api/v1/restaurant/servers"), TENANT, cashier, ROLE_CASHIER), 200);
        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).get("name").asText()).isEqualTo("John");
        assertThat(getJson(as(get("/api/v1/restaurant/servers"), OTHER, otherOwner, ROLE_OWNER), 200)).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    /** The floor's card for an order with no table, or null when the floor does not list it. */
    private JsonNode deliveryCard(String orderId) throws Exception {
        JsonNode floor = getJson(as(get("/api/v1/restaurant/floor").param("branchId", branchId), TENANT, cashier, ROLE_CASHIER), 200);
        for (JsonNode card : floor.get("otherOrders")) {
            if (orderId.equals(card.get("orderId").asText())) {
                return card;
            }
        }
        return null;
    }

    private static String dish(String menuItemId, String unitPrice) {
        return "{\"kind\":\"DISH\",\"menuItemId\":\"" + menuItemId + "\",\"quantity\":1,\"unitPrice\":" + unitPrice
                + ",\"modifierOptionIds\":[]}";
    }

    private String seat(String tableId) throws Exception {
        return cashierPost("/open-orders", "{\"branchId\":\"" + branchId + "\",\"channel\":\"DINE_IN\",\"tableId\":\""
                + tableId + "\",\"clientOrderId\":\"" + UUID.randomUUID() + "\"}", 201).get("id").asText();
    }

    private void addAndSend(String orderId, String menuItemId, int quantity) throws Exception {
        cashierPost("/open-orders/" + orderId + "/lines", "{\"lines\":[{\"clientLineId\":\"" + UUID.randomUUID()
                + "\",\"menuItemId\":\"" + menuItemId + "\",\"quantity\":" + quantity + "}]}", 200);
        cashierPost("/open-orders/" + orderId + "/send", "", 200);
    }

    private JsonNode report(String from, String to) throws Exception {
        return getJson(as(get("/api/v1/restaurant/reports/menu-sales").param("branchId", branchId)
                .param("from", from).param("to", to), TENANT, owner, ROLE_OWNER), 200);
    }

    private JsonNode publicPost(String path, String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
        String text = result.getResponse().getContentAsString();
        return text.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(text);
    }

    private JsonNode cashierPost(String path, String body, int expected) throws Exception {
        return send(as(post("/api/v1/restaurant" + path), TENANT, cashier, ROLE_CASHIER), body, expected);
    }

    private JsonNode ownerPost(String path, String body, int expected) throws Exception {
        return send(as(post(path), TENANT, owner, ROLE_OWNER), body, expected);
    }

    private JsonNode ownerPut(String path, String body, int expected) throws Exception {
        return send(as(put(path), TENANT, owner, ROLE_OWNER), body, expected);
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
