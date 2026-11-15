package zelisline.ub.restaurant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import zelisline.ub.identity.domain.User;
import zelisline.ub.posdraft.PosDraftConstants;
import zelisline.ub.posdraft.application.PosDraftService;
import zelisline.ub.posdraft.domain.PosDraft;
import zelisline.ub.pricing.domain.TaxRate;
import zelisline.ub.pricing.repository.TaxRateRepository;
import zelisline.ub.restaurant.repository.KitchenStationRepository;
import zelisline.ub.restaurant.repository.KitchenTicketLineRepository;
import zelisline.ub.restaurant.repository.KitchenTicketRepository;
import zelisline.ub.restaurant.repository.MenuCategoryRepository;
import zelisline.ub.restaurant.repository.MenuItemModifierGroupRepository;
import zelisline.ub.restaurant.repository.MenuItemRepository;
import zelisline.ub.restaurant.repository.ModifierGroupRepository;
import zelisline.ub.restaurant.repository.ModifierOptionRepository;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.receipt.SaleReceiptService;
import zelisline.ub.serviceops.api.ServiceSaleTestSupport;

/**
 * Restaurant phase 1a (counter service): a dish with modifiers is priced by the server and sold
 * through the ordinary sale, the kitchen is told in the same transaction, marks it ready, and
 * the counter hands it over. Plus the failure paths: wrong price, bad choices, replay, void,
 * another business, someone without the permission.
 */
class RestaurantCounterIT extends ServiceSaleTestSupport {

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
    private TaxRateRepository taxRateRepository;
    @Autowired
    private SaleReceiptService saleReceiptService;
    @Autowired
    private PosDraftService posDraftService;

    private User cook;
    private String burger;
    private String single;
    private String dbl;
    private String extraCheese;
    private String cheeseGroup;

    @BeforeEach
    void seedMenu() throws Exception {
        ticketLineRepository.deleteAll();
        ticketRepository.deleteAll();
        linkRepository.deleteAll();
        modifierOptionRepository.deleteAll();
        modifierGroupRepository.deleteAll();
        menuItemRepository.deleteAll();
        menuCategoryRepository.deleteAll();
        stationRepository.deleteAll();
        taxRateRepository.deleteAll();

        role(ROLE_KITCHEN, "kitchen_test");
        permissionRepository.save(perm("5a1e1111-1111-4111-8111-111111111180", "restaurant.menu.manage"));
        permissionRepository.save(perm("5a1e1111-1111-4111-8111-111111111181", "restaurant.kitchen.update"));
        grant(ROLE_OWNER, "5a1e1111-1111-4111-8111-111111111180");
        grant(ROLE_OWNER, "5a1e1111-1111-4111-8111-111111111181");
        grant(ROLE_KITCHEN, "5a1e1111-1111-4111-8111-111111111181");
        cook = user(TENANT, "cook@svc-sale.test", "Cook", ROLE_KITCHEN);

        JsonNode patty = ownerPost("/api/v1/restaurant/menu/modifier-groups",
                "{\"name\":\"Patty\",\"minSelect\":1,\"maxSelect\":1,\"options\":["
                        + "{\"name\":\"Single\",\"priceDelta\":0,\"preselected\":true},"
                        + "{\"name\":\"Double\",\"priceDelta\":150}]}");
        single = patty.get("options").get(0).get("id").asText();
        dbl = patty.get("options").get(1).get("id").asText();
        JsonNode cheese = ownerPost("/api/v1/restaurant/menu/modifier-groups",
                "{\"name\":\"Cheese\",\"minSelect\":0,\"maxSelect\":1,\"options\":["
                        + "{\"name\":\"Extra cheese\",\"priceDelta\":50}]}");
        cheeseGroup = cheese.get("id").asText();
        extraCheese = cheese.get("options").get(0).get("id").asText();
        burger = ownerPost("/api/v1/restaurant/menu/items",
                "{\"name\":\"Burger\",\"kind\":\"DISH\",\"price\":400,\"modifierGroupIds\":[\""
                        + patty.get("id").asText() + "\",\"" + cheeseGroup + "\"]}").get("id").asText();
    }

    // ------------------------------------------------------------------ selling a dish

    @Test
    void aDishWithModifiersIsPricedByTheMenuSoldAndSentToTheKitchen() throws Exception {
        JsonNode sale = postSale("d1-" + UUID.randomUUID(), dish(burger, 2, "600", dbl, extraCheese, "no onions"),
                "1200", 201);
        assertThat(sale.get("grandTotal").decimalValue()).isEqualByComparingTo("1200.00");
        JsonNode line = sale.get("items").get(0);
        assertThat(line.get("lineKind").asText()).isEqualTo("DISH");
        assertThat(line.get("lineLabel").asText()).isEqualTo("Burger (Double, Extra cheese)");
        assertThat(line.get("displayName").asText()).isEqualTo("Burger");
        assertThat(line.get("menuItemId").asText()).isEqualTo(burger);
        assertThat(line.get("lineNote").asText()).isEqualTo("no onions");
        assertThat(line.get("unitPrice").decimalValue()).isEqualByComparingTo("600");

        // No product behind it: no stock moved, no batch, and the money is ordinary sales revenue.
        SaleItem stored = saleItemRepository.findById(line.get("id").asText()).orElseThrow();
        assertThat(stored.getItemId()).isNull();
        assertThat(stored.getBatchId()).isNull();
        assertThat(stockMovementRepository.count()).isZero();
        Sale saved = saleRepository.findById(sale.get("id").asText()).orElseThrow();
        List<JournalLine> journal = journalLineRepository.findByJournalEntryId(saved.getJournalEntryId());
        assertBalanced(journal);
        assertThat(credit(journal, LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("1200.00");

        // The kitchen was told, once, with what was chosen and no prices.
        JsonNode board = board(cook, ROLE_KITCHEN, null);
        assertThat(board.get("tickets")).hasSize(1);
        JsonNode ticket = board.get("tickets").get(0);
        assertThat(ticket.get("ticketType").asText()).isEqualTo("NEW");
        assertThat(ticket.get("status").asText()).isEqualTo("OPEN");
        JsonNode kitchenLine = ticket.get("lines").get(0);
        assertThat(kitchenLine.get("name").asText()).isEqualTo("Burger");
        assertThat(kitchenLine.get("quantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(kitchenLine.get("note").asText()).isEqualTo("no onions");
        assertThat(kitchenLine.get("stage").asText()).isEqualTo("NEW");
        assertThat(kitchenLine.get("modifiers").get(0).get("option").asText()).isEqualTo("Double");
        assertThat(kitchenLine.has("unitPrice")).isFalse();

        // The counter can read the order number for that sale.
        JsonNode order = getJson(as(get("/api/v1/restaurant/orders/by-sale/" + sale.get("id").asText()),
                TENANT, cashier, ROLE_CASHIER), 200);
        assertThat(order.get("orderNumber").asLong()).isEqualTo(ticket.get("orderNumber").asLong());
        assertThat(order.get("ready").asBoolean()).isFalse();

        // It is a held ticket of kind ORDER, already paid, linked to the sale.
        PosDraft stored2 = posDraftRepository.findById(order.get("orderId").asText()).orElseThrow();
        assertThat(stored2.getDraftKind()).isEqualTo(PosDraftConstants.KIND_ORDER);
        assertThat(stored2.getStatus()).isEqualTo(PosDraftConstants.STATUS_COMPLETED);
        assertThat(stored2.getSaleId()).isEqualTo(sale.get("id").asText());

        String receipt = new String(saleReceiptService.buildEscPos(TENANT, sale.get("id").asText(), 80),
                StandardCharsets.ISO_8859_1);
        assertThat(receipt).contains("Burger (Double, Extra cheese)");
    }

    @Test
    void theClientsPriceIsCheckedNotTrusted() throws Exception {
        // Double patty is 550; a client claiming 400 is refused and nothing is sold or sent.
        JsonNode refused = postSale("p1-" + UUID.randomUUID(), dish(burger, 1, "400", dbl), "400", 409);
        assertThat(refused.toString()).contains("550");
        assertThat(saleRepository.count()).isZero();
        assertThat(ticketRepository.count()).isZero();
        postSale("p2-" + UUID.randomUUID(), dish(burger, 1, "550", dbl), "550", 201);
    }

    @Test
    void choicesMustFitTheDish() throws Exception {
        // Patty is required.
        postSale("c1-" + UUID.randomUUID(), dish(burger, 1, "400"), "400", 400);
        // Only one patty.
        postSale("c2-" + UUID.randomUUID(), dish(burger, 1, "550", single, dbl), "550", 400);
        // The same option twice.
        postSale("c3-" + UUID.randomUUID(), dish(burger, 1, "450", single, extraCheese, extraCheese), "450", 400);
        // An option that is not this dish's.
        postSale("c4-" + UUID.randomUUID(), dish(burger, 1, "400", single, UUID.randomUUID().toString()), "400", 400);
        // Half a burger.
        postSale("c5-" + UUID.randomUUID(), "{\"kind\":\"DISH\",\"menuItemId\":\"" + burger
                + "\",\"quantity\":0.5,\"unitPrice\":400,\"modifierOptionIds\":[\"" + single + "\"]}", "200", 400);
        // Modifiers on a shelf product.
        postSale("c6-" + UUID.randomUUID(), "{\"itemId\":\"" + itemId
                + "\",\"quantity\":1,\"unitPrice\":5,\"modifierOptionIds\":[\"" + single + "\"]}", "5", 400);
        // A dish line naming a product.
        postSale("c7-" + UUID.randomUUID(), "{\"kind\":\"DISH\",\"itemId\":\"" + itemId + "\",\"menuItemId\":\"" + burger
                + "\",\"quantity\":1,\"unitPrice\":400,\"modifierOptionIds\":[\"" + single + "\"]}", "400", 400);
        assertThat(saleRepository.count()).isZero();
    }

    @Test
    void aDishThatHasRunOutOrLeftTheMenuCannotBeSold() throws Exception {
        // The cook marks it as run out; someone who only sells may not.
        mockMvc.perform(as(put("/api/v1/restaurant/menu/items/" + burger + "/availability"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"available\":false}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(put("/api/v1/restaurant/menu/items/" + burger + "/availability"), TENANT, cook, ROLE_KITCHEN)
                        .content("{\"available\":false}"))
                .andExpect(status().isOk());
        JsonNode refused = postSale("r1-" + UUID.randomUUID(), dish(burger, 1, "400", single), "400", 400);
        assertThat(refused.toString()).contains("run out");
        mockMvc.perform(as(put("/api/v1/restaurant/menu/items/" + burger + "/availability"), TENANT, cook, ROLE_KITCHEN)
                        .content("{\"available\":true}"))
                .andExpect(status().isOk());
        postSale("r2-" + UUID.randomUUID(), dish(burger, 1, "400", single), "400", 201);

        mockMvc.perform(as(delete("/api/v1/restaurant/menu/items/" + burger), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isNoContent());
        postSale("r3-" + UUID.randomUUID(), dish(burger, 1, "400", single), "400", 400);
        // The sale already made still reads as it was sold.
        assertThat(saleItemRepository.findAll().get(0).getLineLabel()).isEqualTo("Burger (Single)");
    }

    @Test
    void renamingAnOptionDoesNotChangeWhatWasSold() throws Exception {
        JsonNode sale = postSale("n1-" + UUID.randomUUID(), dish(burger, 1, "450", single, extraCheese), "450", 201);
        mockMvc.perform(as(put("/api/v1/restaurant/menu/modifier-groups/" + cheeseGroup), TENANT, owner, ROLE_OWNER)
                        .content("{\"name\":\"Cheese\",\"minSelect\":0,\"maxSelect\":1,\"options\":[{\"id\":\""
                                + extraCheese + "\",\"name\":\"Cheddar\",\"priceDelta\":80}]}"))
                .andExpect(status().isOk());
        JsonNode read = getJson(as(get("/api/v1/sales/" + sale.get("id").asText()), TENANT, cashier, ROLE_CASHIER), 200);
        assertThat(read.get("items").get(0).get("lineLabel").asText()).isEqualTo("Burger (Single, Extra cheese)");
        assertThat(board(cook, ROLE_KITCHEN, null).get("tickets").get(0).get("lines").get(0).get("modifiers").get(1)
                .get("option").asText()).isEqualTo("Extra cheese");
        // The next one sells at the new price under the new name.
        postSale("n2-" + UUID.randomUUID(), dish(burger, 1, "450", single, extraCheese), "450", 409);
        postSale("n3-" + UUID.randomUUID(), dish(burger, 1, "480", single, extraCheese), "480", 201);
    }

    @Test
    void replayingTheSameSaleTellsTheKitchenOnce() throws Exception {
        String key = "rp-" + UUID.randomUUID();
        JsonNode first = postSale(key, dish(burger, 1, "400", single), "400", 201);
        MvcResult again = mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", key)
                        .content(saleBody(dish(burger, 1, "400", single), "400")))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        assertThat(objectMapper.readTree(again.getResponse().getContentAsString()).get("id").asText())
                .isEqualTo(first.get("id").asText());
        assertThat(saleRepository.count()).isEqualTo(1);
        assertThat(ticketRepository.count()).isEqualTo(1);
        assertThat(posDraftRepository.count()).isEqualTo(1);
    }

    @Test
    void aSaleMadeOfflineKeepsThePriceTheCustomerPaid() throws Exception {
        // The till rang a burger at 400 while offline. Since then the price went up and it ran out.
        mockMvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/restaurant/menu/items/" + burger), TENANT, owner, ROLE_OWNER)
                        .content("{\"price\":450}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(put("/api/v1/restaurant/menu/items/" + burger + "/availability"), TENANT, cook, ROLE_KITCHEN)
                        .content("{\"available\":false}"))
                .andExpect(status().isOk());
        String soldAt = java.time.Instant.now().minusSeconds(600).toString();
        String body = "{\"branchId\":\"" + branchId + "\",\"clientSoldAt\":\"" + soldAt + "\",\"lines\":["
                + dish(burger, 1, "400", single) + "],\"payments\":[{\"method\":\"cash\",\"amount\":400}]}";
        MvcResult result = mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "off-" + UUID.randomUUID()).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode sale = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(sale.get("grandTotal").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(sale.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("400");
        // The kitchen still hears about it, late.
        assertThat(ticketRepository.count()).isEqualTo(1);

        // Choices that never fitted the dish are refused even offline, and so is a sale sent now.
        String badChoices = body.replace("\"" + single + "\"", "\"" + single + "\",\"" + dbl + "\"");
        mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "off2-" + UUID.randomUUID()).content(badChoices))
                .andExpect(status().isBadRequest());
        postSale("off3-" + UUID.randomUUID(), dish(burger, 1, "400", single), "400", 400);
    }

    // ------------------------------------------------------------------ stations and products

    @Test
    void eachStationGetsItsOwnTicketAndABottleFromTheShelfGoesNowhere() throws Exception {
        String bar = ownerPost("/api/v1/restaurant/stations", "{\"name\":\"Bar\"}").get("id").asText();
        String grill = ownerPost("/api/v1/restaurant/stations", "{\"name\":\"Grill\"}").get("id").asText();
        String drinks = ownerPost("/api/v1/restaurant/menu/categories",
                "{\"name\":\"Drinks\",\"stationId\":\"" + bar + "\"}").get("id").asText();
        String juice = ownerPost("/api/v1/restaurant/menu/items",
                "{\"name\":\"Fresh juice\",\"kind\":\"DISH\",\"price\":150,\"categoryId\":\"" + drinks + "\"}")
                .get("id").asText();
        mockMvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/restaurant/menu/items/" + burger), TENANT, owner, ROLE_OWNER)
                        .content("{\"stationId\":\"" + grill + "\"}"))
                .andExpect(status().isOk());
        // A product on the menu with no station: sold from the shelf, handed over at the counter.
        String oil = ownerPost("/api/v1/restaurant/menu/items",
                "{\"kind\":\"ITEM\",\"itemId\":\"" + itemId + "\"}").get("id").asText();

        JsonNode sale = postSale("st-" + UUID.randomUUID(),
                dish(burger, 1, "400", single) + "," + dish(juice, 2, "150") + ",{\"itemId\":\"" + itemId
                        + "\",\"menuItemId\":\"" + oil + "\",\"quantity\":1,\"unitPrice\":5}",
                "705", 201);
        assertThat(sale.get("items")).hasSize(3);
        // The product still came off the shelf.
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock()).isEqualByComparingTo("9");

        assertThat(board(cook, ROLE_KITCHEN, null).get("tickets")).hasSize(2);
        JsonNode barBoard = board(cook, ROLE_KITCHEN, bar);
        assertThat(barBoard.get("tickets")).hasSize(1);
        assertThat(barBoard.get("tickets").get(0).get("lines")).hasSize(1);
        assertThat(barBoard.get("tickets").get(0).get("lines").get(0).get("name").asText()).isEqualTo("Fresh juice");
        JsonNode grillBoard = board(cook, ROLE_KITCHEN, grill);
        assertThat(grillBoard.get("tickets").get(0).get("lines").get(0).get("name").asText()).isEqualTo("Burger");
        assertThat(board(cook, ROLE_KITCHEN, "none").get("tickets")).isEmpty();

        // The order is ready only when both stations are done.
        String orderId = barBoard.get("tickets").get(0).get("orderId").asText();
        stage("tickets", barBoard.get("tickets").get(0).get("id").asText(), "READY", cook, ROLE_KITCHEN, 200);
        assertThat(orders().get("orders").get(0).get("ready").asBoolean()).isFalse();
        mockMvc.perform(as(post("/api/v1/restaurant/orders/" + orderId + "/handed-over"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isConflict());
        stage("tickets", grillBoard.get("tickets").get(0).get("id").asText(), "READY", cook, ROLE_KITCHEN, 200);
        JsonNode ready = orders().get("orders").get(0);
        assertThat(ready.get("ready").asBoolean()).isTrue();
        assertThat(ready.get("linesTotal").asInt()).isEqualTo(2);
    }

    @Test
    void aSaleOfShelfGoodsOnlyCreatesNoOrder() throws Exception {
        postSale("sg-" + UUID.randomUUID(), "{\"itemId\":\"" + itemId + "\",\"quantity\":1,\"unitPrice\":5}", "5", 201);
        assertThat(ticketRepository.count()).isZero();
        assertThat(posDraftRepository.count()).isZero();
    }

    // ------------------------------------------------------------------ kitchen progress

    @Test
    void theKitchenMarksLinesReadyAndTheCounterHandsTheOrderOver() throws Exception {
        String chips = ownerPost("/api/v1/restaurant/menu/items", "{\"name\":\"Chips\",\"kind\":\"DISH\",\"price\":150}")
                .get("id").asText();
        postSale("k1-" + UUID.randomUUID(), dish(burger, 1, "400", single) + "," + dish(chips, 1, "150"), "550", 201);
        JsonNode ticket = board(cook, ROLE_KITCHEN, null).get("tickets").get(0);
        String ticketId = ticket.get("id").asText();
        String orderId = ticket.get("orderId").asText();
        String burgerLine = ticket.get("lines").get(0).get("id").asText();
        String chipsLine = ticket.get("lines").get(1).get("id").asText();

        // Someone who only sells cannot change kitchen progress; the cook can.
        stage("lines", burgerLine, "READY", cashier, ROLE_CASHIER, 403);
        stage("lines", burgerLine, "LATER", cook, ROLE_KITCHEN, 400);
        JsonNode after = stage("lines", burgerLine, "READY", cook, ROLE_KITCHEN, 200);
        assertThat(after.get("status").asText()).isEqualTo("OPEN");
        // A double tap changes nothing.
        stage("lines", burgerLine, "READY", cook, ROLE_KITCHEN, 200);
        // A wrong tap goes back one step, not two.
        stage("lines", burgerLine, "NEW", cook, ROLE_KITCHEN, 409);
        assertThat(stage("lines", burgerLine, "PREPARING", cook, ROLE_KITCHEN, 200).get("lines").get(0).get("stage").asText())
                .isEqualTo("PREPARING");

        // "All ready" finishes what is left, skipping "preparing" for the chips.
        JsonNode done = stage("tickets", ticketId, "READY", cook, ROLE_KITCHEN, 200);
        assertThat(done.get("status").asText()).isEqualTo("READY");
        assertThat(done.get("lines").get(1).get("id").asText()).isEqualTo(chipsLine);
        assertThat(done.get("lines").get(1).get("stage").asText()).isEqualTo("READY");
        // A finished ticket leaves the kitchen board and shows as ready at the counter.
        assertThat(board(cook, ROLE_KITCHEN, null).get("tickets")).isEmpty();
        assertThat(orders().get("orders").get(0).get("ready").asBoolean()).isTrue();

        // Handing over needs the till permission, not the kitchen one.
        mockMvc.perform(as(post("/api/v1/restaurant/orders/" + orderId + "/handed-over"), TENANT, cook, ROLE_KITCHEN))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/v1/restaurant/orders/" + orderId + "/handed-over"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk());
        assertThat(orders().get("orders")).isEmpty();
        // Marking progress never touched the order row itself.
        assertThat(posDraftRepository.findById(orderId).orElseThrow().getVersion()).isZero();

        // The times are kept for the report.
        String today = LocalDate.now(ZoneId.of("Africa/Nairobi")).toString();
        JsonNode times = getJson(as(get("/api/v1/restaurant/reports/kitchen-times").param("branchId", branchId)
                .param("from", today).param("to", today), TENANT, owner, ROLE_OWNER), 200);
        assertThat(times.get("stations").get(0).get("tickets").asLong()).isEqualTo(1);
        assertThat(times.get("stations").get(0).get("stationName").asText()).isEqualTo("Kitchen");
    }

    @Test
    void voidingTheSaleTellsTheKitchenToStop() throws Exception {
        String chips = ownerPost("/api/v1/restaurant/menu/items", "{\"name\":\"Chips\",\"kind\":\"DISH\",\"price\":150}")
                .get("id").asText();
        JsonNode sale = postSale("v1-" + UUID.randomUUID(), dish(burger, 1, "400", single) + "," + dish(chips, 1, "150"),
                "550", 201);
        JsonNode ticket = board(cook, ROLE_KITCHEN, null).get("tickets").get(0);
        // The chips are already made when the sale is voided; the burger is not.
        stage("lines", ticket.get("lines").get(1).get("id").asText(), "READY", cook, ROLE_KITCHEN, 200);

        mockMvc.perform(as(post("/api/v1/sales/" + sale.get("id").asText() + "/void"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"notes\":\"customer left\"}"))
                .andExpect(status().isOk());

        JsonNode board = board(cook, ROLE_KITCHEN, null);
        assertThat(board.get("tickets")).hasSize(1);
        JsonNode voidTicket = board.get("tickets").get(0);
        assertThat(voidTicket.get("ticketType").asText()).isEqualTo("VOID");
        assertThat(voidTicket.get("lines")).hasSize(1);
        assertThat(voidTicket.get("lines").get(0).get("name").asText()).isEqualTo("Burger");
        assertThat(voidTicket.get("lines").get(0).get("stage").asText()).isEqualTo("VOIDED");
        // The cancelled burger cannot be marked ready, and a void ticket has nothing to prepare.
        stage("lines", ticket.get("lines").get(0).get("id").asText(), "READY", cook, ROLE_KITCHEN, 409);
        stage("tickets", voidTicket.get("id").asText(), "READY", cook, ROLE_KITCHEN, 409);

        // It stays on the board until someone in the kitchen says they saw it.
        mockMvc.perform(as(post("/api/v1/restaurant/kitchen/tickets/" + voidTicket.get("id").asText() + "/acknowledge"),
                        TENANT, cook, ROLE_KITCHEN))
                .andExpect(status().isNoContent());
        assertThat(board(cook, ROLE_KITCHEN, null).get("tickets")).isEmpty();

        // Voiding reversed the money like any other sale.
        Sale voided = saleRepository.findById(sale.get("id").asText()).orElseThrow();
        assertBalanced(journalLineRepository.findByJournalEntryId(voided.getVoidJournalEntryId()));
        assertThat(accountBalance(LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ tax, refunds, reports

    @Test
    void aDishCarriesItsOwnTaxRate() throws Exception {
        TaxRate vat = new TaxRate();
        vat.setBusinessId(TENANT);
        vat.setName("VAT 16%");
        vat.setRatePercent(new BigDecimal("16"));
        vat.setInclusive(true);
        vat.setActive(true);
        taxRateRepository.save(vat);
        String soup = ownerPost("/api/v1/restaurant/menu/items",
                "{\"name\":\"Soup\",\"kind\":\"DISH\",\"price\":116,\"taxRateId\":\"" + vat.getId() + "\"}").get("id").asText();
        // A tax rate from nowhere is refused.
        mockMvc.perform(as(post("/api/v1/restaurant/menu/items"), TENANT, owner, ROLE_OWNER)
                        .content("{\"name\":\"Stew\",\"kind\":\"DISH\",\"price\":100,\"taxRateId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());

        JsonNode sale = postSale("t1-" + UUID.randomUUID(), dish(soup, 1, "116"), "116", 201);
        assertThat(sale.get("taxTotal").decimalValue()).isEqualByComparingTo("16.00");
        assertThat(sale.get("items").get(0).get("taxAmount").decimalValue()).isEqualByComparingTo("16.00");
        assertThat(sale.get("items").get(0).get("taxCode").asText()).isEqualTo("B");
        Sale saved = saleRepository.findById(sale.get("id").asText()).orElseThrow();
        List<JournalLine> journal = journalLineRepository.findByJournalEntryId(saved.getJournalEntryId());
        assertBalanced(journal);
        assertThat(credit(journal, LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("100.00");
        assertThat(credit(journal, LedgerAccountCodes.VAT_PAYABLE)).isEqualByComparingTo("16.00");
    }

    @Test
    void aDishIsNotRefundedLineByLine() throws Exception {
        JsonNode sale = postSale("rf-" + UUID.randomUUID(), dish(burger, 1, "400", single), "400", 201);
        mockMvc.perform(as(post("/api/v1/sales/{saleId}/refund", sale.get("id").asText()), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "rf-r-" + UUID.randomUUID())
                        .content("{\"lines\":[{\"saleItemId\":\"" + sale.get("items").get(0).get("id").asText()
                                + "\",\"quantity\":1}],\"payments\":[{\"method\":\"cash\",\"amount\":400}],\"reason\":\"cold\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theMenuSalesReportCountsDishesAndWhatWasChosen() throws Exception {
        postSale("ms1-" + UUID.randomUUID(), dish(burger, 2, "550", dbl), "1100", 201);
        postSale("ms2-" + UUID.randomUUID(), dish(burger, 1, "450", single, extraCheese), "450", 201);
        JsonNode voided = postSale("ms3-" + UUID.randomUUID(), dish(burger, 1, "400", single), "400", 201);
        mockMvc.perform(as(post("/api/v1/sales/" + voided.get("id").asText() + "/void"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk());

        String today = LocalDate.now(ZoneId.of("Africa/Nairobi")).toString();
        JsonNode report = getJson(as(get("/api/v1/restaurant/reports/menu-sales").param("branchId", branchId)
                .param("from", today).param("to", today), TENANT, owner, ROLE_OWNER), 200);
        assertThat(report.get("totalRevenue").decimalValue()).isEqualByComparingTo("1550.00");
        assertThat(report.get("costTracked").asBoolean()).isFalse();
        assertThat(report.get("items")).hasSize(1);
        assertThat(report.get("items").get(0).get("quantity").decimalValue()).isEqualByComparingTo("3");
        JsonNode top = report.get("modifiers").get(0);
        assertThat(top.get("option").asText()).isEqualTo("Double");
        assertThat(top.get("timesChosen").asLong()).isEqualTo(2);
        assertThat(top.get("extraRevenue").decimalValue()).isEqualByComparingTo("300.00");

        // Someone who only sells does not read reports; a period that is too long is refused.
        mockMvc.perform(as(get("/api/v1/restaurant/reports/menu-sales").param("branchId", branchId)
                        .param("from", today).param("to", today), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/v1/restaurant/reports/menu-sales").param("branchId", branchId)
                        .param("from", "2026-01-01").param("to", "2026-12-31"), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ menu rules

    @Test
    void theMenuIsValidatedAndOnlyItsManagerChangesIt() throws Exception {
        mockMvc.perform(as(post("/api/v1/restaurant/menu/items"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"name\":\"Tea\",\"kind\":\"DISH\",\"price\":50}"))
                .andExpect(status().isForbidden());
        for (String bad : List.of(
                "{\"name\":\"Tea\",\"kind\":\"DISH\"}",
                "{\"name\":\"Tea\",\"kind\":\"DISH\",\"price\":0}",
                "{\"kind\":\"DISH\",\"price\":50}",
                "{\"name\":\"Tea\",\"kind\":\"SOUP\",\"price\":50}",
                "{\"name\":\"Tea\",\"kind\":\"DISH\",\"price\":50,\"itemId\":\"" + itemId + "\"}",
                "{\"kind\":\"ITEM\"}",
                "{\"kind\":\"ITEM\",\"itemId\":\"" + UUID.randomUUID() + "\"}",
                "{\"kind\":\"ITEM\",\"itemId\":\"" + itemId + "\",\"modifierGroupIds\":[\"" + cheeseGroup + "\"]}",
                "{\"name\":\"Tea\",\"kind\":\"DISH\",\"price\":50,\"stationId\":\"" + UUID.randomUUID() + "\"}")) {
            mockMvc.perform(as(post("/api/v1/restaurant/menu/items"), TENANT, owner, ROLE_OWNER).content(bad))
                    .andExpect(status().isBadRequest());
        }
        for (String bad : List.of(
                "{\"name\":\"Size\",\"minSelect\":2,\"maxSelect\":1,\"options\":[{\"name\":\"S\"},{\"name\":\"L\"}]}",
                "{\"name\":\"Size\",\"minSelect\":1,\"maxSelect\":1,\"options\":[]}",
                "{\"name\":\"Size\",\"minSelect\":0,\"maxSelect\":1,\"options\":[{\"name\":\"S\",\"priceDelta\":-10}]}",
                "{\"name\":\"Size\",\"minSelect\":0,\"maxSelect\":1,\"options\":[{\"name\":\"S\",\"preselected\":true},"
                        + "{\"name\":\"L\",\"preselected\":true}]}")) {
            mockMvc.perform(as(post("/api/v1/restaurant/menu/modifier-groups"), TENANT, owner, ROLE_OWNER).content(bad))
                    .andExpect(status().isBadRequest());
        }
        // A dish cannot become a product.
        mockMvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/restaurant/menu/items/" + burger), TENANT, owner, ROLE_OWNER)
                        .content("{\"kind\":\"ITEM\",\"itemId\":\"" + itemId + "\"}"))
                .andExpect(status().isBadRequest());

        JsonNode menu = getJson(as(get("/api/v1/restaurant/menu").param("branchId", branchId), TENANT, cashier, ROLE_CASHIER), 200);
        assertThat(menu.get("items")).hasSize(1);
        assertThat(menu.get("items").get(0).get("modifierGroupIds")).hasSize(2);
        // Groups come back by name: Cheese, then Patty, whose first option is the usual choice.
        assertThat(menu.get("modifierGroups").get(1).get("name").asText()).isEqualTo("Patty");
        assertThat(menu.get("modifierGroups").get(1).get("options").get(0).get("preselected").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ another business, another kind

    @Test
    void anotherBusinessSeesAndChangesNothing() throws Exception {
        postSale("ob-" + UUID.randomUUID(), dish(burger, 1, "400", single), "400", 201);
        JsonNode ticket = board(cook, ROLE_KITCHEN, null).get("tickets").get(0);
        String ticketId = ticket.get("id").asText();
        String lineId = ticket.get("lines").get(0).get("id").asText();
        String orderId = ticket.get("orderId").asText();

        assertThat(getJson(as(get("/api/v1/restaurant/menu"), OTHER, otherOwner, ROLE_OWNER), 200).get("items")).isEmpty();
        assertThat(getJson(as(get("/api/v1/restaurant/kitchen/tickets").param("branchId", branchId),
                OTHER, otherOwner, ROLE_OWNER), 200).get("tickets")).isEmpty();
        assertThat(getJson(as(get("/api/v1/restaurant/orders").param("branchId", branchId),
                OTHER, otherOwner, ROLE_OWNER), 200).get("orders")).isEmpty();
        mockMvc.perform(as(post("/api/v1/restaurant/kitchen/lines/" + lineId + "/stage"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"stage\":\"READY\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/v1/restaurant/kitchen/tickets/" + ticketId + "/stage"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"stage\":\"READY\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/v1/restaurant/orders/" + orderId + "/handed-over"), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/restaurant/menu/items/" + burger), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"price\":1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(delete("/api/v1/restaurant/menu/items/" + burger), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(put("/api/v1/restaurant/menu/modifier-groups/" + cheeseGroup), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"name\":\"X\",\"minSelect\":0,\"maxSelect\":1,\"options\":[{\"name\":\"Y\"}]}"))
                .andExpect(status().isNotFound());
        // Their own menu cannot borrow this business's modifier group or product.
        mockMvc.perform(as(post("/api/v1/restaurant/menu/items"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"name\":\"Theirs\",\"kind\":\"DISH\",\"price\":10,\"modifierGroupIds\":[\"" + cheeseGroup + "\"]}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/v1/restaurant/menu/items"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"kind\":\"ITEM\",\"itemId\":\"" + itemId + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(board(cook, ROLE_KITCHEN, null).get("tickets").get(0).get("lines").get(0).get("stage").asText())
                .isEqualTo("NEW");
    }

    @Test
    void anOpenOrderSurvivesAShiftCloseWhileAHeldCartDoesNot() {
        Shift shift = shiftRepository.findAll().get(0);
        PosDraft order = pendingDraft(PosDraftConstants.KIND_ORDER, shift, 9001L);
        PosDraft cart = pendingDraft(PosDraftConstants.KIND_CART, shift, 9002L);

        int cancelled = posDraftService.cancelPendingForClosedShift(TENANT, shift, cashier.getId());

        assertThat(cancelled).isEqualTo(1);
        assertThat(posDraftRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(PosDraftConstants.STATUS_PENDING);
        assertThat(posDraftRepository.findById(cart.getId()).orElseThrow().getStatus())
                .isEqualTo(PosDraftConstants.STATUS_CANCELLED);
        assertThat(PosDraftConstants.isCart(null)).isTrue();
        assertThat(PosDraftConstants.isCart(PosDraftConstants.KIND_SERVICE)).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private PosDraft pendingDraft(String kind, Shift shift, long number) {
        PosDraft draft = new PosDraft();
        draft.setBusinessId(TENANT);
        draft.setBranchId(branchId);
        draft.setTicketNumber(number);
        draft.setDraftKind(kind);
        draft.setCreatedBy(cashier.getId());
        draft.setShiftId(shift.getId());
        draft.setClientDraftId("t-" + UUID.randomUUID());
        return posDraftRepository.save(draft);
    }

    private static String dish(String menuItemId, int qty, String unitPrice, String... optionIds) {
        // A last argument that is not an id (contains a space) is the note.
        StringBuilder options = new StringBuilder();
        String note = null;
        for (String option : optionIds) {
            if (option.contains(" ")) {
                note = option;
                continue;
            }
            if (options.length() > 0) {
                options.append(',');
            }
            options.append('"').append(option).append('"');
        }
        return "{\"kind\":\"DISH\",\"menuItemId\":\"" + menuItemId + "\",\"quantity\":" + qty + ",\"unitPrice\":" + unitPrice
                + ",\"modifierOptionIds\":[" + options + "]" + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
    }

    private JsonNode ownerPost(String path, String body) throws Exception {
        MvcResult result = mockMvc.perform(as(post(path), TENANT, owner, ROLE_OWNER).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(MockHttpServletRequestBuilder request, int expected) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().is(expected)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode board(User user, String role, String stationId) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/restaurant/kitchen/tickets").param("branchId", branchId);
        if (stationId != null) {
            request = request.param("stationId", stationId);
        }
        return getJson(as(request, TENANT, user, role), 200);
    }

    private JsonNode orders() throws Exception {
        return getJson(as(get("/api/v1/restaurant/orders").param("branchId", branchId), TENANT, cashier, ROLE_CASHIER), 200);
    }

    private JsonNode stage(String what, String id, String stage, User user, String role, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/restaurant/kitchen/" + what + "/" + id + "/stage"),
                        TENANT, user, role).content("{\"stage\":\"" + stage + "\"}"))
                .andExpect(status().is(expected))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }
}
