package zelisline.ub.restaurant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
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
import zelisline.ub.identity.domain.User;
import zelisline.ub.posdraft.PosDraftConstants;
import zelisline.ub.posdraft.application.PosDraftService;
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
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.serviceops.api.ServiceSaleTestSupport;
import zelisline.ub.serviceops.application.StaffEarningsService;
import zelisline.ub.serviceops.repository.ServiceResourceRepository;

/**
 * Restaurant phase 1b: an order that is paid after it is eaten. A table is seated, the order is
 * added to and sent to the kitchen in rounds, lines are cancelled with a reason, and the bill is
 * settled whole or split — each bill one ordinary sale.
 */
class RestaurantDineInIT extends ServiceSaleTestSupport {

    private static final String ROLE_WAITER = "5a1e2222-2222-4222-8222-222222222204";
    private static final String ROLE_KITCHEN = "5a1e2222-2222-4222-8222-222222222203";

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
    private PricingService pricingService;
    @Autowired
    private StaffEarningsService staffEarningsService;
    @Autowired
    private PosDraftService posDraftService;

    private User waiter;
    private User cook;
    private String burger;
    private String single;
    private String dbl;
    private String soda;
    private String table1;
    private String table2;

    @BeforeEach
    void seedFloor() throws Exception {
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

        role(ROLE_WAITER, "waiter_test");
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
        grant(ROLE_WAITER, "5a1e1111-1111-4111-8111-111111111182");
        grant(ROLE_CASHIER, "5a1e1111-1111-4111-8111-111111111182");
        grant(ROLE_KITCHEN, "5a1e1111-1111-4111-8111-111111111181");
        waiter = user(TENANT, "waiter@svc-sale.test", "Mike", ROLE_WAITER);
        cook = user(TENANT, "cook@svc-sale.test", "Cook", ROLE_KITCHEN);

        JsonNode patty = ownerPost("/api/v1/restaurant/menu/modifier-groups",
                "{\"name\":\"Patty\",\"minSelect\":1,\"maxSelect\":1,\"options\":["
                        + "{\"name\":\"Single\",\"priceDelta\":0,\"preselected\":true},"
                        + "{\"name\":\"Double\",\"priceDelta\":150}]}");
        single = patty.get("options").get(0).get("id").asText();
        dbl = patty.get("options").get(1).get("id").asText();
        burger = ownerPost("/api/v1/restaurant/menu/items",
                "{\"name\":\"Burger\",\"kind\":\"DISH\",\"price\":400,\"modifierGroupIds\":[\""
                        + patty.get("id").asText() + "\"]}").get("id").asText();
        // A bottle from the shelf, on the menu, with a shelf price of 80.
        setShelfPrice("80");
        soda = ownerPost("/api/v1/restaurant/menu/items", "{\"kind\":\"ITEM\",\"itemId\":\"" + itemId
                + "\",\"name\":\"Soda\"}").get("id").asText();

        table1 = ownerPost("/api/v1/restaurant/tables",
                "{\"branchId\":\"" + branchId + "\",\"name\":\"Table 1\",\"capacity\":4,\"area\":\"Inside\"}")
                .get("id").asText();
        table2 = ownerPost("/api/v1/restaurant/tables",
                "{\"branchId\":\"" + branchId + "\",\"name\":\"Table 2\",\"capacity\":2}").get("id").asText();
    }

    // ------------------------------------------------------------------ the whole evening

    @Test
    void aTableIsSeatedServedInRoundsAndPaidWithATip() throws Exception {
        String clientOrderId = UUID.randomUUID().toString();
        JsonNode order = open(table1, clientOrderId, 2, 201);
        String orderId = order.get("id").asText();
        assertThat(order.get("status").asText()).isEqualTo("pending");
        assertThat(order.get("tableName").asText()).isEqualTo("Table 1");
        assertThat(order.get("servedByName").asText()).isEqualTo("Mike");
        // Seating the same table again from a retry is the same order.
        assertThat(open(table1, clientOrderId, 2, 201).get("id").asText()).isEqualTo(orderId);
        assertThat(posDraftRepository.count()).isEqualTo(1);

        // Round one: two burgers. The same request sent twice adds them once.
        String lineKey = UUID.randomUUID().toString();
        String body = "{\"lines\":[" + line(lineKey, burger, 2, dbl) + "]}";
        waiterPost("/open-orders/" + orderId + "/lines", body, 200);
        JsonNode added = waiterPost("/open-orders/" + orderId + "/lines", body, 200);
        assertThat(added.get("lines")).hasSize(1);
        assertThat(added.get("lines").get(0).get("stage").asText()).isEqualTo("DRAFT");
        assertThat(added.get("lines").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("550");
        assertThat(added.get("total").decimalValue()).isEqualByComparingTo("1100.00");
        // Nothing reaches the kitchen until it is sent.
        assertThat(ticketRepository.count()).isZero();

        JsonNode sent = waiterPost("/open-orders/" + orderId + "/send", "", 200);
        assertThat(sent.get("lines").get(0).get("stage").asText()).isEqualTo("NEW");
        assertThat(ticketRepository.count()).isEqualTo(1);
        // A second tap on Send sends nothing more.
        waiterPost("/open-orders/" + orderId + "/send", "", 200);
        assertThat(ticketRepository.count()).isEqualTo(1);

        // Round two: a soda. It needs no making, so it is ready for the waiter at once.
        waiterPost("/open-orders/" + orderId + "/lines",
                "{\"lines\":[" + line(UUID.randomUUID().toString(), soda, 1) + "]}", 200);
        JsonNode round2 = waiterPost("/open-orders/" + orderId + "/send", "", 200);
        assertThat(round2.get("lines").get(1).get("stage").asText()).isEqualTo("READY");
        assertThat(ticketRepository.count()).isEqualTo(1);

        // The floor shows the table as occupied with what is going on.
        JsonNode floor = getJson(as(get("/api/v1/restaurant/floor").param("branchId", branchId), TENANT, waiter, ROLE_WAITER), 200);
        JsonNode card = floor.get("tables").get(0).get("orders").get(0);
        assertThat(card.get("inKitchen").asInt()).isEqualTo(1);
        assertThat(card.get("ready").asInt()).isEqualTo(1);
        assertThat(card.get("total").decimalValue()).isEqualByComparingTo("1180.00");
        assertThat(card.get("unsettled").decimalValue()).isEqualByComparingTo("1180.00");
        assertThat(floor.get("tables").get(1).get("orders")).isEmpty();
        // A table's order is not called by number at the counter.
        assertThat(getJson(as(get("/api/v1/restaurant/orders").param("branchId", branchId), TENANT, cashier, ROLE_CASHIER), 200)
                .get("orders")).isEmpty();

        // The kitchen finishes the burgers; the waiter carries everything over.
        JsonNode ticket = getJson(as(get("/api/v1/restaurant/kitchen/tickets").param("branchId", branchId), TENANT, cook, ROLE_KITCHEN), 200)
                .get("tickets").get(0);
        assertThat(ticket.get("roundNo").asInt()).isEqualTo(1);
        mockMvc.perform(as(post("/api/v1/restaurant/kitchen/tickets/" + ticket.get("id").asText() + "/stage"), TENANT, cook, ROLE_KITCHEN)
                        .content("{\"stage\":\"READY\"}"))
                .andExpect(status().isOk());
        JsonNode served = waiterPost("/open-orders/" + orderId + "/served", "", 200);
        assertThat(served.get("lines").get(0).get("stage").asText()).isEqualTo("SERVED");
        assertThat(served.get("lines").get(1).get("stage").asText()).isEqualTo("SERVED");

        // The bill: everything, paid in cash and M-Pesa together, with a tip for the waiter.
        waiterPost("/open-orders/" + orderId + "/bill-requested", "{\"requested\":true}", 200);
        JsonNode billed = waiterPost("/open-orders/" + orderId + "/bills", "", 201);
        JsonNode bill = billed.get("bills").get(0);
        assertThat(bill.get("total").decimalValue()).isEqualByComparingTo("1180.00");
        assertThat(billed.get("unbilled").decimalValue()).isEqualByComparingTo("0.00");
        String billId = bill.get("id").asText();
        // Someone who only waits tables does not take the money.
        mockMvc.perform(as(post("/api/v1/restaurant/open-orders/" + orderId + "/bills/" + billId + "/pay"), TENANT, waiter, ROLE_WAITER)
                        .content("{\"payments\":[{\"method\":\"cash\",\"amount\":1180}]}"))
                .andExpect(status().isForbidden());
        String pay = "{\"payments\":[{\"method\":\"cash\",\"amount\":700},{\"method\":\"mpesa_manual\",\"amount\":580,\"reference\":\"QK1TEST\"}],\"tip\":100}";
        JsonNode paid = cashierPost("/open-orders/" + orderId + "/bills/" + billId + "/pay", pay, 200);
        assertThat(paid.get("status").asText()).isEqualTo("completed");
        assertThat(paid.get("bills").get(0).get("status").asText()).isEqualTo("PAID");
        assertThat(paid.get("bills").get(0).get("receiptNo").asLong()).isPositive();
        // Paying the same bill again rings nothing more.
        cashierPost("/open-orders/" + orderId + "/bills/" + billId + "/pay", pay, 200);
        assertThat(saleRepository.count()).isEqualTo(1);

        Sale sale = saleRepository.findAll().get(0);
        assertThat(sale.getGrandTotal()).isEqualByComparingTo("1180.00");
        assertThat(sale.getTipTotal()).isEqualByComparingTo("100.00");
        List<JournalLine> journal = journalLineRepository.findByJournalEntryId(sale.getJournalEntryId());
        assertBalanced(journal);
        assertThat(credit(journal, LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("1180.00");
        assertThat(credit(journal, LedgerAccountCodes.TIPS_PAYABLE)).isEqualByComparingTo("100.00");
        // The tip is owed to whoever served the table; the soda came off the shelf.
        String mikeProfile = staffProfileRepository.findAll().stream()
                .filter(p -> waiter.getId().equals(p.getUserId())).findFirst().orElseThrow().getId();
        assertThat(staffEarningsService.balance(TENANT, mikeProfile)).isEqualByComparingTo("100.00");
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock()).isEqualByComparingTo("9");
        // The table is free again and no second order was written for the sale.
        assertThat(getJson(as(get("/api/v1/restaurant/floor").param("branchId", branchId), TENANT, waiter, ROLE_WAITER), 200)
                .get("tables").get(0).get("orders")).isEmpty();
        assertThat(posDraftRepository.count()).isEqualTo(1);
        // Nothing more can be added to a settled order.
        waiterPost("/open-orders/" + orderId + "/lines",
                "{\"lines\":[" + line(UUID.randomUUID().toString(), soda, 1) + "]}", 409);
    }

    // ------------------------------------------------------------------ prices are fixed when sent

    @Test
    void whatWasOrderedIsChargedAsItWasOrderedWhateverTheMenuSaysLater() throws Exception {
        String orderId = open(table1, UUID.randomUUID().toString(), 1, 201).get("id").asText();
        waiterPost("/open-orders/" + orderId + "/lines", "{\"lines\":[" + line("a", burger, 1, single) + "]}", 200);
        waiterPost("/open-orders/" + orderId + "/send", "", 200);
        // The price goes up, the dish runs out and is then taken off the menu altogether.
        mockMvc.perform(as(patch("/api/v1/restaurant/menu/items/" + burger), TENANT, owner, ROLE_OWNER)
                        .content("{\"price\":900}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(delete("/api/v1/restaurant/menu/items/" + burger), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isNoContent());

        String billId = waiterPost("/open-orders/" + orderId + "/bills", "", 201).get("bills").get(0).get("id").asText();
        JsonNode paid = cashierPost("/open-orders/" + orderId + "/bills/" + billId + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":400}]}", 200);
        assertThat(paid.get("status").asText()).isEqualTo("completed");
        var saleLine = saleItemRepository.findAll().get(0);
        assertThat(saleLine.getLineKind()).isEqualTo("DISH");
        assertThat(saleLine.getLineLabel()).isEqualTo("Burger (Single)");
        assertThat(saleLine.getLineTotal()).isEqualByComparingTo("400.00");
        // A wrong amount is still refused: the payments must cover the bill.
        String second = open(table2, UUID.randomUUID().toString(), 1, 201).get("id").asText();
        waiterPost("/open-orders/" + second + "/lines", "{\"lines\":[" + line("b", soda, 1) + "]}", 200);
        waiterPost("/open-orders/" + second + "/send", "", 200);
        String bill2 = waiterPost("/open-orders/" + second + "/bills", "", 201).get("bills").get(0).get("id").asText();
        cashierPost("/open-orders/" + second + "/bills/" + bill2 + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":10}]}", 400);
    }

    @Test
    void aProductWhoseShelfPriceMovedIsRepricedBeforeItIsPaid() throws Exception {
        String orderId = open(table1, UUID.randomUUID().toString(), 1, 201).get("id").asText();
        waiterPost("/open-orders/" + orderId + "/lines", "{\"lines\":[" + line("a", soda, 2) + "]}", 200);
        waiterPost("/open-orders/" + orderId + "/send", "", 200);
        String billId = waiterPost("/open-orders/" + orderId + "/bills", "", 201).get("bills").get(0).get("id").asText();
        setShelfPrice("100");
        // The first attempt is refused and the order is brought up to date; it is not paid.
        cashierPost("/open-orders/" + orderId + "/bills/" + billId + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":160}]}", 409);
        assertThat(saleRepository.count()).isZero();
        JsonNode repriced = getJson(as(get("/api/v1/restaurant/open-orders/" + orderId), TENANT, waiter, ROLE_WAITER), 200);
        assertThat(repriced.get("bills").get(0).get("total").decimalValue()).isEqualByComparingTo("200.00");
        cashierPost("/open-orders/" + orderId + "/bills/" + billId + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":200}]}", 200);
        assertThat(saleRepository.findAll().get(0).getGrandTotal()).isEqualByComparingTo("200.00");
    }

    // ------------------------------------------------------------------ splitting the bill

    @Test
    void aBillIsSplitByItemsAndTheOrderStaysOpenUntilEveryPartIsSettled() throws Exception {
        String orderId = open(table1, UUID.randomUUID().toString(), 2, 201).get("id").asText();
        JsonNode added = waiterPost("/open-orders/" + orderId + "/lines", "{\"lines\":["
                + line("a", burger, 2, single) + "," + line("b", soda, 3) + "]}", 200);
        String burgers = added.get("lines").get(0).get("id").asText();
        String sodas = added.get("lines").get(1).get("id").asText();
        // A bill is for food that was ordered: unsent items block it.
        waiterPost("/open-orders/" + orderId + "/bills", "", 409);
        waiterPost("/open-orders/" + orderId + "/send", "", 200);

        // Mike pays for one burger and one soda.
        JsonNode first = waiterPost("/open-orders/" + orderId + "/bills", "{\"lines\":[{\"orderLineId\":\"" + burgers
                + "\",\"quantity\":1},{\"orderLineId\":\"" + sodas + "\",\"quantity\":1}]}", 201);
        assertThat(first.get("bills").get(0).get("total").decimalValue()).isEqualByComparingTo("480.00");
        assertThat(first.get("unbilled").decimalValue()).isEqualByComparingTo("560.00");
        assertThat(first.get("lines").get(1).get("billedQuantity").decimalValue()).isEqualByComparingTo("1");
        // More than is left, half a dish, or a line of another order cannot go on a bill.
        waiterPost("/open-orders/" + orderId + "/bills",
                "{\"lines\":[{\"orderLineId\":\"" + sodas + "\",\"quantity\":3}]}", 409);
        waiterPost("/open-orders/" + orderId + "/bills",
                "{\"lines\":[{\"orderLineId\":\"" + burgers + "\",\"quantity\":0.5}]}", 400);
        waiterPost("/open-orders/" + orderId + "/bills",
                "{\"lines\":[{\"orderLineId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}", 400);

        String firstBill = first.get("bills").get(0).get("id").asText();
        JsonNode afterFirst = cashierPost("/open-orders/" + orderId + "/bills/" + firstBill + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":480}]}", 200);
        assertThat(afterFirst.get("status").asText()).isEqualTo("pending");
        assertThat(afterFirst.get("settled").decimalValue()).isEqualByComparingTo("480.00");
        JsonNode partPaidCard = floorCard(
                getJson(as(get("/api/v1/restaurant/floor").param("branchId", branchId), TENANT, waiter, ROLE_WAITER), 200),
                orderId);
        assertThat(partPaidCard).isNotNull();
        assertThat(partPaidCard.get("total").decimalValue()).isEqualByComparingTo("1040.00");
        assertThat(partPaidCard.get("unsettled").decimalValue()).isEqualByComparingTo("560.00");

        // Lenah pays for the rest: "everything not yet on a bill".
        JsonNode second = waiterPost("/open-orders/" + orderId + "/bills", "", 201);
        assertThat(second.get("bills").get(1).get("billNo").asInt()).isEqualTo(2);
        assertThat(second.get("bills").get(1).get("total").decimalValue()).isEqualByComparingTo("560.00");
        // A bill nobody paid can be torn up and made again.
        String secondBill = second.get("bills").get(1).get("id").asText();
        mockMvc.perform(as(delete("/api/v1/restaurant/open-orders/" + orderId + "/bills/" + secondBill), TENANT, waiter, ROLE_WAITER))
                .andExpect(status().isOk());
        String again = waiterPost("/open-orders/" + orderId + "/bills", "", 201).get("bills").get(1).get("id").asText();
        JsonNode done = cashierPost("/open-orders/" + orderId + "/bills/" + again + "/pay",
                "{\"payments\":[{\"method\":\"mpesa_manual\",\"amount\":560}]}", 200);
        assertThat(done.get("status").asText()).isEqualTo("completed");

        // Two bills, two ordinary sales, and the shelf is three sodas lighter.
        assertThat(saleRepository.count()).isEqualTo(2);
        assertThat(accountBalance(LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("1040.00");
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock()).isEqualByComparingTo("7");
        // A paid bill cannot be torn up.
        mockMvc.perform(as(delete("/api/v1/restaurant/open-orders/" + orderId + "/bills/" + firstBill), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isConflict());
    }

    @Test
    void aBillCanBeMadeForOneSeat() throws Exception {
        String orderId = open(table1, UUID.randomUUID().toString(), 2, 201).get("id").asText();
        waiterPost("/open-orders/" + orderId + "/lines", "{\"lines\":["
                + lineForSeat("a", burger, 1, 1, single) + "," + lineForSeat("b", burger, 1, 2, dbl) + ","
                + lineForSeat("c", soda, 1, 2) + "]}", 200);
        waiterPost("/open-orders/" + orderId + "/send", "", 200);
        JsonNode seat2 = waiterPost("/open-orders/" + orderId + "/bills", "{\"seatNo\":2}", 201);
        assertThat(seat2.get("bills").get(0).get("total").decimalValue()).isEqualByComparingTo("630.00");
        assertThat(seat2.get("bills").get(0).get("lines")).hasSize(2);
        assertThat(seat2.get("unbilled").decimalValue()).isEqualByComparingTo("400.00");
        // A seat with nothing left makes no bill.
        waiterPost("/open-orders/" + orderId + "/bills", "{\"seatNo\":2}", 409);
    }

    // ------------------------------------------------------------------ changes after sending

    @Test
    void whatTheKitchenHasIsCancelledOpenlyNotEdited() throws Exception {
        String orderId = open(table1, UUID.randomUUID().toString(), 2, 201).get("id").asText();
        JsonNode added = waiterPost("/open-orders/" + orderId + "/lines", "{\"lines\":["
                + line("a", burger, 1, single) + "," + line("b", burger, 1, dbl) + "," + line("c", soda, 1) + "]}", 200);
        String first = added.get("lines").get(0).get("id").asText();
        String second = added.get("lines").get(1).get("id").asText();
        String third = added.get("lines").get(2).get("id").asText();

        // Before sending, a line is changed or removed freely and needs no reason.
        JsonNode changed = waiterPatch("/open-orders/" + orderId + "/lines/" + first, "{\"quantity\":3,\"note\":\"well done\"}", 200);
        assertThat(changed.get("lines").get(0).get("quantity").decimalValue()).isEqualByComparingTo("3");
        assertThat(changed.get("total").decimalValue()).isEqualByComparingTo("1830.00");
        waiterPatch("/open-orders/" + orderId + "/lines/" + first, "{\"quantity\":0.5}", 400);
        JsonNode removed = waiterPost("/open-orders/" + orderId + "/lines/" + third + "/cancel", "", 200);
        assertThat(removed.get("lines")).hasSize(2);

        waiterPost("/open-orders/" + orderId + "/send", "", 200);
        // After sending, it cannot be edited at all...
        waiterPatch("/open-orders/" + orderId + "/lines/" + first, "{\"quantity\":1}", 409);
        // ...and cancelling it needs the permission and a reason.
        waiterPost("/open-orders/" + orderId + "/lines/" + second + "/cancel", "{\"reason\":\"changed mind\"}", 403);
        mockMvc.perform(as(post("/api/v1/restaurant/open-orders/" + orderId + "/lines/" + second + "/cancel"), TENANT, owner, ROLE_OWNER)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        MvcResult result = mockMvc.perform(as(post("/api/v1/restaurant/open-orders/" + orderId + "/lines/" + second + "/cancel"), TENANT, owner, ROLE_OWNER)
                        .content("{\"reason\":\"customer changed their mind\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode after = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(after.get("lines").get(1).get("stage").asText()).isEqualTo("VOIDED");
        assertThat(after.get("lines").get(1).get("voidReason").asText()).isEqualTo("customer changed their mind");
        assertThat(after.get("total").decimalValue()).isEqualByComparingTo("1200.00");

        // The kitchen is told to stop making it, and its first ticket now waits only for the other line.
        JsonNode board = getJson(as(get("/api/v1/restaurant/kitchen/tickets").param("branchId", branchId), TENANT, cook, ROLE_KITCHEN), 200);
        assertThat(board.get("tickets")).hasSize(2);
        JsonNode voidTicket = board.get("tickets").get(1);
        assertThat(voidTicket.get("ticketType").asText()).isEqualTo("VOID");
        assertThat(voidTicket.get("lines").get(0).get("name").asText()).isEqualTo("Burger");
        assertThat(board.get("tickets").get(0).get("lines").get(1).get("stage").asText()).isEqualTo("VOIDED");

        // The bill carries only what was not cancelled, and a line on a bill cannot be cancelled.
        JsonNode billed = waiterPost("/open-orders/" + orderId + "/bills", "", 201);
        assertThat(billed.get("bills").get(0).get("total").decimalValue()).isEqualByComparingTo("1200.00");
        mockMvc.perform(as(post("/api/v1/restaurant/open-orders/" + orderId + "/lines/" + first + "/cancel"), TENANT, owner, ROLE_OWNER)
                        .content("{\"reason\":\"too late\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aMealOnTheHouseOrAWalkOutIsClosedWithAReasonNotMadeToDisappear() throws Exception {
        String orderId = open(table1, UUID.randomUUID().toString(), 1, 201).get("id").asText();
        waiterPost("/open-orders/" + orderId + "/lines", "{\"lines\":[" + line("a", burger, 1, single) + "]}", 200);
        waiterPost("/open-orders/" + orderId + "/send", "", 200);
        // Food has gone out: the order can no longer simply be cancelled.
        waiterPost("/open-orders/" + orderId + "/cancel", "{\"reason\":\"left\"}", 409);
        String billId = waiterPost("/open-orders/" + orderId + "/bills", "", 201).get("bills").get(0).get("id").asText();
        String close = "/api/v1/restaurant/open-orders/" + orderId + "/bills/" + billId + "/close";
        mockMvc.perform(as(post(close), TENANT, cashier, ROLE_CASHIER).content("{\"outcome\":\"UNPAID\",\"reason\":\"walked out\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post(close), TENANT, owner, ROLE_OWNER).content("{\"outcome\":\"FREE\",\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(post(close), TENANT, owner, ROLE_OWNER).content("{\"outcome\":\"UNPAID\"}"))
                .andExpect(status().isBadRequest());
        MvcResult result = mockMvc.perform(as(post(close), TENANT, owner, ROLE_OWNER)
                        .content("{\"outcome\":\"UNPAID\",\"reason\":\"walked out\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode closed = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(closed.get("status").asText()).isEqualTo("completed");
        assertThat(closed.get("bills").get(0).get("status").asText()).isEqualTo("UNPAID");
        assertThat(closed.get("bills").get(0).get("reason").asText()).isEqualTo("walked out");
        assertThat(saleRepository.count()).isZero();
    }

    @Test
    void voidingTheSaleOfAPaidBillOpensTheOrderAgain() throws Exception {
        String orderId = open(table1, UUID.randomUUID().toString(), 1, 201).get("id").asText();
        waiterPost("/open-orders/" + orderId + "/lines", "{\"lines\":[" + line("a", burger, 1, single) + "]}", 200);
        waiterPost("/open-orders/" + orderId + "/send", "", 200);
        String billId = waiterPost("/open-orders/" + orderId + "/bills", "", 201).get("bills").get(0).get("id").asText();
        cashierPost("/open-orders/" + orderId + "/bills/" + billId + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":400}]}", 200);
        String saleId = saleRepository.findAll().get(0).getId();
        mockMvc.perform(as(post("/api/v1/sales/" + saleId + "/void"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk());

        JsonNode reopened = getJson(as(get("/api/v1/restaurant/open-orders/" + orderId), TENANT, waiter, ROLE_WAITER), 200);
        assertThat(reopened.get("status").asText()).isEqualTo("pending");
        assertThat(reopened.get("bills").get(0).get("status").asText()).isEqualTo("VOIDED");
        assertThat(reopened.get("unbilled").decimalValue()).isEqualByComparingTo("400.00");
        // The food was eaten: the kitchen is not sent a cancellation for it.
        assertThat(ticketRepository.findAll()).allMatch(t -> "NEW".equals(t.getTicketType()));
        // It is billed and paid again as a new bill.
        String again = waiterPost("/open-orders/" + orderId + "/bills", "", 201).get("bills").get(1).get("id").asText();
        assertThat(cashierPost("/open-orders/" + orderId + "/bills/" + again + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":400}]}", 200).get("status").asText()).isEqualTo("completed");
        assertThat(accountBalance(LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("400.00");
    }

    // ------------------------------------------------------------------ tables, takeaway, bookings

    @Test
    void anOrderMovesTablesChangesHandsAndSurvivesAShiftClose() throws Exception {
        String orderId = open(table1, UUID.randomUUID().toString(), 2, 201).get("id").asText();
        waiterPost("/open-orders/" + orderId + "/lines", "{\"lines\":[" + line("a", burger, 1, single) + "]}", 200);
        waiterPost("/open-orders/" + orderId + "/send", "", 200);

        JsonNode moved = waiterPost("/open-orders/" + orderId + "/move", "{\"tableId\":\"" + table2 + "\"}", 200);
        assertThat(moved.get("tableName").asText()).isEqualTo("Table 2");
        assertThat(moved.get("lines")).hasSize(1);
        waiterPost("/open-orders/" + orderId + "/move", "{\"tableId\":\"" + UUID.randomUUID() + "\"}", 400);
        // A table taken out of use cannot be seated.
        mockMvc.perform(as(patch("/api/v1/restaurant/tables/" + table1), TENANT, owner, ROLE_OWNER).content("{\"active\":false}"))
                .andExpect(status().isOk());
        waiterPost("/open-orders/" + orderId + "/move", "{\"tableId\":\"" + table1 + "\"}", 400);

        JsonNode handed = waiterPatch("/open-orders/" + orderId, "{\"servedByStaffProfileId\":\"" + john + "\",\"guestCount\":3}", 200);
        assertThat(handed.get("servedByName").asText()).isEqualTo("John");
        assertThat(handed.get("guestCount").asInt()).isEqualTo(3);
        waiterPatch("/open-orders/" + orderId, "{\"servedByStaffProfileId\":\"" + UUID.randomUUID() + "\"}", 400);

        // The cashier hands over the till. The table is still eating.
        var shift = shiftRepository.findAll().get(0);
        assertThat(posDraftService.cancelPendingForClosedShift(TENANT, shift, cashier.getId())).isZero();
        assertThat(posDraftRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(PosDraftConstants.STATUS_PENDING);
    }

    @Test
    void tablesAreNotPlacesOnTheServiceFloor() throws Exception {
        // A shop with a salon floor and a dining room keeps them apart: the service side lists
        // chairs and rooms, never tables, and cannot make or change a table.
        JsonNode places = getJson(
                as(get("/api/v1/service-resources").param("branchId", branchId), TENANT, owner, ROLE_OWNER), 200);
        for (JsonNode place : places.get("resources")) {
            assertThat(place.get("resourceType").asText()).isNotEqualTo("TABLE");
            assertThat(place.get("id").asText()).isNotIn(table1, table2);
        }
        send(as(post("/api/v1/service-resources"), TENANT, owner, ROLE_OWNER),
                "{\"branchId\":\"" + branchId + "\",\"name\":\"Table 9\",\"resourceType\":\"TABLE\"}", 400);
        send(as(patch("/api/v1/service-resources/" + table1), TENANT, owner, ROLE_OWNER),
                "{\"name\":\"Chair X\"}", 404);
        // The restaurant floor still has both tables.
        assertThat(getJson(as(get("/api/v1/restaurant/tables").param("branchId", branchId), TENANT, owner, ROLE_OWNER), 200))
                .hasSize(2);
    }

    @Test
    void takeawayNeedsNoTableAndDeliveryNeedsAnAddress() throws Exception {
        JsonNode takeaway = waiterPost("/open-orders", "{\"branchId\":\"" + branchId + "\",\"channel\":\"TAKEAWAY\","
                + "\"customerName\":\"Lenah\",\"clientOrderId\":\"" + UUID.randomUUID() + "\"}", 201);
        assertThat(takeaway.get("channel").asText()).isEqualTo("TAKEAWAY");
        assertThat(takeaway.get("customerName").asText()).isEqualTo("Lenah");
        waiterPost("/open-orders", "{\"branchId\":\"" + branchId + "\",\"channel\":\"DELIVERY\",\"clientOrderId\":\""
                + UUID.randomUUID() + "\"}", 400);
        waiterPost("/open-orders", "{\"branchId\":\"" + branchId + "\",\"channel\":\"DINE_IN\",\"clientOrderId\":\""
                + UUID.randomUUID() + "\"}", 400);
        waiterPost("/open-orders", "{\"branchId\":\"" + branchId + "\",\"channel\":\"PICNIC\",\"clientOrderId\":\""
                + UUID.randomUUID() + "\"}", 400);
        JsonNode floor = getJson(as(get("/api/v1/restaurant/floor").param("branchId", branchId), TENANT, waiter, ROLE_WAITER), 200);
        assertThat(floor.get("otherOrders")).hasSize(1);
        // An order nothing was made for can simply be cancelled.
        JsonNode cancelled = waiterPost("/open-orders/" + takeaway.get("id").asText() + "/cancel", "{\"reason\":\"did not wait\"}", 200);
        assertThat(cancelled.get("status").asText()).isEqualTo("cancelled");
    }

    @Test
    void aTableIsBookedForLaterAndTwoBookingsCannotHoldItAtOnce() throws Exception {
        Instant start = Instant.now().plusSeconds(3600);
        String booking = "{\"branchId\":\"" + branchId + "\",\"channel\":\"DINE_IN\",\"tableId\":\"" + table1
                + "\",\"guestCount\":4,\"customerName\":\"Kevin\",\"customerPhone\":\"0700000000\",\"scheduledStart\":\""
                + start + "\",\"scheduledEnd\":\"" + start.plusSeconds(5400) + "\",\"clientOrderId\":\"%s\"}";
        JsonNode booked = waiterPost("/open-orders", booking.formatted(UUID.randomUUID()), 201);
        assertThat(booked.get("booked").asBoolean()).isTrue();
        String orderId = booked.get("id").asText();
        // The same table for an overlapping time is refused; another table, or a later time, is not.
        waiterPost("/open-orders", booking.formatted(UUID.randomUUID()), 409);
        waiterPost("/open-orders", booking.formatted(UUID.randomUUID()).replace(table1, table2), 201);
        waiterPost("/open-orders", booking.formatted(UUID.randomUUID())
                .replace(start.toString(), start.plusSeconds(7200).toString())
                .replace(start.plusSeconds(5400).toString(), start.plusSeconds(9000).toString()), 201);
        // An end before the start is not a booking.
        waiterPost("/open-orders", booking.formatted(UUID.randomUUID())
                .replace(start.plusSeconds(5400).toString(), start.minusSeconds(60).toString()), 400);

        // Nothing is sent for guests who are not there yet.
        waiterPost("/open-orders/" + orderId + "/lines", "{\"lines\":[" + line("a", burger, 1, single) + "]}", 200);
        waiterPost("/open-orders/" + orderId + "/send", "", 409);
        JsonNode seated = waiterPost("/open-orders/" + orderId + "/seat", "", 200);
        assertThat(seated.get("booked").asBoolean()).isFalse();
        assertThat(seated.get("servedByName").asText()).isEqualTo("Mike");
        waiterPost("/open-orders/" + orderId + "/send", "", 200);
        assertThat(ticketRepository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ another business, another role

    @Test
    void anotherBusinessSeesAndChangesNothingAndTheKitchenTakesNoOrders() throws Exception {
        String orderId = open(table1, UUID.randomUUID().toString(), 2, 201).get("id").asText();
        JsonNode added = waiterPost("/open-orders/" + orderId + "/lines", "{\"lines\":[" + line("a", burger, 1, single) + "]}", 200);
        String lineId = added.get("lines").get(0).get("id").asText();
        waiterPost("/open-orders/" + orderId + "/send", "", 200);
        String billId = waiterPost("/open-orders/" + orderId + "/bills", "", 201).get("bills").get(0).get("id").asText();
        String base = "/api/v1/restaurant/open-orders/" + orderId;

        assertThat(getJson(as(get("/api/v1/restaurant/floor").param("branchId", branchId), OTHER, otherOwner, ROLE_OWNER), 200)
                .get("tables")).isEmpty();
        assertThat(getJson(as(get("/api/v1/restaurant/tables").param("branchId", branchId), OTHER, otherOwner, ROLE_OWNER), 200))
                .isEmpty();
        mockMvc.perform(as(get(base), OTHER, otherOwner, ROLE_OWNER)).andExpect(status().isNotFound());
        for (String[] call : new String[][] {
                {"/lines", "{\"lines\":[" + line("z", burger, 1, single) + "]}"},
                {"/send", "{}"}, {"/served", "{}"}, {"/seat", "{}"},
                {"/move", "{\"tableId\":\"" + table2 + "\"}"},
                {"/bill-requested", "{\"requested\":true}"},
                {"/cancel", "{\"reason\":\"x\"}"},
                {"/lines/" + lineId + "/cancel", "{\"reason\":\"x\"}"},
                {"/bills", "{}"},
                {"/bills/" + billId + "/pay", "{\"payments\":[{\"method\":\"cash\",\"amount\":400}]}"},
                {"/bills/" + billId + "/close", "{\"outcome\":\"COMP\",\"reason\":\"x\"}"}}) {
            mockMvc.perform(as(post(base + call[0]), OTHER, otherOwner, ROLE_OWNER).content(call[1]))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(as(patch(base), OTHER, otherOwner, ROLE_OWNER).content("{\"guestCount\":9}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(delete(base + "/bills/" + billId), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(patch("/api/v1/restaurant/tables/" + table1), OTHER, otherOwner, ROLE_OWNER).content("{\"name\":\"Theirs\"}"))
                .andExpect(status().isNotFound());
        // Their own order cannot sit at this business's table.
        mockMvc.perform(as(post("/api/v1/restaurant/open-orders"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"channel\":\"DINE_IN\",\"tableId\":\"" + table1
                                + "\",\"clientOrderId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());

        // Someone who only works the kitchen sees no floor and takes no orders; only a manager makes tables.
        mockMvc.perform(as(get("/api/v1/restaurant/floor").param("branchId", branchId), TENANT, cook, ROLE_KITCHEN))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post(base + "/send"), TENANT, cook, ROLE_KITCHEN)).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/v1/restaurant/tables"), TENANT, waiter, ROLE_WAITER)
                        .content("{\"branchId\":\"" + branchId + "\",\"name\":\"Table 9\"}"))
                .andExpect(status().isForbidden());
        // Nothing changed.
        JsonNode same = getJson(as(get(base), TENANT, waiter, ROLE_WAITER), 200);
        assertThat(same.get("status").asText()).isEqualTo("pending");
        assertThat(same.get("bills").get(0).get("status").asText()).isEqualTo("OPEN");
        assertThat(same.get("tableName").asText()).isEqualTo("Table 1");
    }

    // ------------------------------------------------------------------ helpers

    private void setShelfPrice(String amount) {
        pricingService.setSellingPrice(TENANT, new PostSellingPriceRequest(itemId, null, new BigDecimal(amount),
                java.time.LocalDate.now(java.time.ZoneOffset.UTC), null), owner.getId());
    }

    private JsonNode open(String tableId, String clientOrderId, int guests, int expected) throws Exception {
        return waiterPost("/open-orders", "{\"branchId\":\"" + branchId + "\",\"channel\":\"DINE_IN\",\"tableId\":\""
                + tableId + "\",\"guestCount\":" + guests + ",\"clientOrderId\":\"" + clientOrderId + "\"}", expected);
    }

    private static String line(String clientLineId, String menuItemId, int qty, String... optionIds) {
        return lineForSeat(clientLineId, menuItemId, qty, 0, optionIds);
    }

    private static String lineForSeat(String clientLineId, String menuItemId, int qty, int seat, String... optionIds) {
        StringBuilder options = new StringBuilder();
        for (String option : optionIds) {
            if (options.length() > 0) {
                options.append(',');
            }
            options.append('"').append(option).append('"');
        }
        return "{\"clientLineId\":\"" + clientLineId + "\",\"menuItemId\":\"" + menuItemId + "\",\"quantity\":" + qty
                + ",\"modifierOptionIds\":[" + options + "]" + (seat > 0 ? ",\"seatNo\":" + seat : "") + "}";
    }

    private JsonNode waiterPost(String path, String body, int expected) throws Exception {
        return send(as(post("/api/v1/restaurant" + path), TENANT, waiter, ROLE_WAITER), body, expected);
    }

    private JsonNode waiterPatch(String path, String body, int expected) throws Exception {
        return send(as(patch("/api/v1/restaurant" + path), TENANT, waiter, ROLE_WAITER), body, expected);
    }

    private JsonNode cashierPost(String path, String body, int expected) throws Exception {
        return send(as(post("/api/v1/restaurant" + path), TENANT, cashier, ROLE_CASHIER), body, expected);
    }

    private JsonNode ownerPost(String path, String body) throws Exception {
        return send(as(post(path), TENANT, owner, ROLE_OWNER), body, 201);
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
        if (!body.isEmpty()) {
            request = request.content(body);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().is(expected)).andReturn();
        String text = result.getResponse().getContentAsString();
        return text.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(text);
    }

    /** The floor card of one order, from a floor response, or null when it is not listed. */
    private static JsonNode floorCard(JsonNode floor, String orderId) {
        for (JsonNode table : floor.get("tables")) {
            for (JsonNode order : table.get("orders")) {
                if (orderId.equals(order.get("orderId").asText())) {
                    return order;
                }
            }
        }
        for (JsonNode order : floor.get("otherOrders")) {
            if (orderId.equals(order.get("orderId").asText())) {
                return order;
            }
        }
        return null;
    }

    private JsonNode getJson(MockHttpServletRequestBuilder request, int expected) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().is(expected)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
