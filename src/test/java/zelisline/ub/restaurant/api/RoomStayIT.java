package zelisline.ub.restaurant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.identity.domain.User;
import zelisline.ub.pricing.api.dto.PostSellingPriceRequest;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.restaurant.repository.MenuCategoryRepository;
import zelisline.ub.restaurant.repository.MenuItemRepository;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.serviceops.api.ServiceSaleTestSupport;
import zelisline.ub.serviceops.repository.ServiceResourceRepository;

/**
 * The front desk, Slice 2 (ADR 0028): a stay is a booking on a room, its folio an open order.
 * Checking in charges the room nights up front as a ROOM line that posts to ROOM_REVENUE; drinks
 * charged to the room ride the same folio and post to SALES_REVENUE; one sale settles the lot at
 * checkout; voiding it reverses room revenue exactly. Rooms do not double-book, a rate change after
 * check-in never moves a live folio, and no business touches another's stay.
 */
class RoomStayIT extends ServiceSaleTestSupport {

    private static final String ROLE_WAITER = "5a1e2222-2222-4222-8222-222222222204";

    @Autowired
    private ServiceResourceRepository resourceRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @Autowired
    private MenuCategoryRepository menuCategoryRepository;
    @Autowired
    private zelisline.ub.sales.repository.SaleRepository saleRepository;
    @Autowired
    private PricingService pricingService;

    private User waiter;
    private String room1;
    private String room2;
    private String soda;

    @BeforeEach
    void seed() throws Exception {
        menuItemRepository.deleteAll();
        menuCategoryRepository.deleteAll();
        resourceRepository.deleteAll();

        role(ROLE_WAITER, "waiter_rooms");
        String manageId = "5a1e1111-1111-4111-8111-111111111190";
        String ordersId = "5a1e1111-1111-4111-8111-111111111191";
        permissionRepository.save(perm(manageId, "restaurant.menu.manage"));
        permissionRepository.save(perm(ordersId, "restaurant.orders.write"));
        grant(ROLE_OWNER, manageId);
        grant(ROLE_OWNER, ordersId);
        grant(ROLE_WAITER, ordersId);
        waiter = user(TENANT, "waiter@rooms.test", "Mara", ROLE_WAITER);

        // A drink from the shelf, on the menu, to charge to a room.
        setShelfPrice("200");
        soda = ownerJson(post("/api/v1/restaurant/menu/items"),
                "{\"kind\":\"ITEM\",\"itemId\":\"" + itemId + "\",\"name\":\"Soda\"}", 201).get("id").asText();

        room1 = ownerJson(post("/api/v1/rooms"),
                "{\"branchId\":\"" + branchId + "\",\"name\":\"Room 1\",\"nightlyRate\":2500,\"area\":\"Upstairs\"}", 201)
                .get("id").asText();
        room2 = ownerJson(post("/api/v1/rooms"),
                "{\"branchId\":\"" + branchId + "\",\"name\":\"Room 2\",\"nightlyRate\":3000}", 201).get("id").asText();
    }

    @Test
    void aStayBooksChecksInChargesADrinkAndSettlesAsOneSaleWithRoomRevenue() throws Exception {
        // Book Room 1 for two nights. The same room over overlapping nights is refused; Room 2 is free.
        Instant checkIn = Instant.now().plusSeconds(3600);
        Instant checkOut = checkIn.plusSeconds(2 * 86400);
        String stay = openStay(room1, checkIn, checkOut, 201).get("id").asText();
        openStay(room1, checkIn.plusSeconds(86400), checkOut.plusSeconds(86400), 409);
        openStay(room2, checkIn, checkOut, 201);

        // Nothing is charged until check-in. Check in: a room-night line for 2 × 2500 appears.
        waiterPost("/open-orders/" + stay + "/seat", "", 200);
        JsonNode seated = getJson(as(get("/api/v1/restaurant/open-orders/" + stay), TENANT, waiter, ROLE_WAITER), 200);
        JsonNode roomLine = seated.get("lines").get(0);
        assertThat(roomLine.get("kind").asText()).isEqualTo("ROOM");
        assertThat(roomLine.get("quantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(roomLine.get("unitPrice").decimalValue()).isEqualByComparingTo("2500");

        // Changing the room's rate afterwards does not move this stay's folio.
        send(as(patch("/api/v1/rooms/" + room1), TENANT, owner, ROLE_OWNER), "{\"nightlyRate\":9999}", 200);

        // Charge a soda (200) to the room. Send everything, then settle the whole folio as one bill.
        waiterPost("/open-orders/" + stay + "/lines",
                "{\"lines\":[{\"clientLineId\":\"d1\",\"menuItemId\":\"" + soda + "\",\"quantity\":1}]}", 200);
        waiterPost("/open-orders/" + stay + "/send", "", 200);
        String billId = waiterPost("/open-orders/" + stay + "/bills", "", 201).get("bills").get(0).get("id").asText();
        // Total is 2 nights × 2500 + one soda × 200 = 5200.
        JsonNode paid = cashierPost("/open-orders/" + stay + "/bills/" + billId + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":5200}]}", 200);
        assertThat(paid.get("status").asText()).isEqualTo("completed");

        // The one sale splits revenue: 5000 to room revenue, 200 to sales revenue.
        assertThat(accountBalance(LedgerAccountCodes.ROOM_REVENUE)).isEqualByComparingTo("5000.00");
        assertThat(accountBalance(LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("200.00");

        // Voiding the sale reverses room revenue to zero.
        String saleId = saleRepository.findAll().stream().map(Sale::getId).findFirst().orElseThrow();
        mockMvc.perform(as(post("/api/v1/sales/" + saleId + "/void"), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isOk());
        assertThat(accountBalance(LedgerAccountCodes.ROOM_REVENUE)).isEqualByComparingTo("0.00");
        assertThat(accountBalance(LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("0.00");
    }

    @Test
    void aDepositCoversPartOfTheStayAndTheFrontDeskListsRoomsAndStays() throws Exception {
        Instant checkIn = Instant.now().plusSeconds(3600);
        String stay = openStayNamed(room1, checkIn, checkIn.plusSeconds(86400), "Kevin", "0711000111", 201)
                .get("id").asText();
        // Hold a 1000 deposit against the booking.
        cashierPost("/open-orders/" + stay + "/deposit", "{\"amount\":1000,\"method\":\"mpesa_manual\"}", 200);
        assertThat(accountBalance(LedgerAccountCodes.CUSTOMER_DEPOSITS)).isEqualByComparingTo("1000.00");

        // The front desk lists the room with its booked stay.
        JsonNode desk = getJson(as(get("/api/v1/rooms/front-desk").param("branchId", branchId), TENANT, waiter, ROLE_WAITER), 200);
        JsonNode roomOne = null;
        for (JsonNode r : desk.get("rooms")) {
            if (room1.equals(r.get("room").get("id").asText())) roomOne = r;
        }
        assertThat(roomOne).isNotNull();
        assertThat(roomOne.get("stays")).hasSize(1);
        assertThat(roomOne.get("stays").get(0).get("booked").asBoolean()).isTrue();

        // Check in (one night × 2500), settle: deposit funds 1000, guest pays 1500 cash. Full room revenue 2500.
        waiterPost("/open-orders/" + stay + "/seat", "", 200);
        waiterPost("/open-orders/" + stay + "/send", "", 200);
        String billId = waiterPost("/open-orders/" + stay + "/bills", "", 201).get("bills").get(0).get("id").asText();
        JsonNode paid = cashierPost("/open-orders/" + stay + "/bills/" + billId + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":1500}]}", 200);
        assertThat(paid.get("depositState").asText()).isEqualTo("APPLIED");
        assertThat(accountBalance(LedgerAccountCodes.ROOM_REVENUE)).isEqualByComparingTo("2500.00");
        assertThat(accountBalance(LedgerAccountCodes.CUSTOMER_DEPOSITS)).isEqualByComparingTo("0.00");
    }

    @Test
    void anotherBusinessCannotSeeOrBookThisOnesRooms() throws Exception {
        Instant checkIn = Instant.now().plusSeconds(3600);
        String stay = openStay(room1, checkIn, checkIn.plusSeconds(86400), 201).get("id").asText();

        // The other business sees no rooms and no stays at this branch, and cannot touch the stay.
        assertThat(getJson(as(get("/api/v1/rooms").param("branchId", branchId), OTHER, otherOwner, ROLE_OWNER), 200))
                .isEmpty();
        assertThat(getJson(as(get("/api/v1/rooms/front-desk").param("branchId", branchId), OTHER, otherOwner, ROLE_OWNER), 200)
                .get("rooms")).isEmpty();
        mockMvc.perform(as(get("/api/v1/restaurant/open-orders/" + stay), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode openStay(String roomId, Instant checkIn, Instant checkOut, int expected) throws Exception {
        return openStayNamed(roomId, checkIn, checkOut, "Guest", null, expected);
    }

    private JsonNode openStayNamed(String roomId, Instant checkIn, Instant checkOut, String name, String phone, int expected)
            throws Exception {
        String body = "{\"branchId\":\"" + branchId + "\",\"channel\":\"LODGING\",\"tableId\":\"" + roomId
                + "\",\"guestCount\":1,\"customerName\":\"" + name + "\""
                + (phone == null ? "" : ",\"customerPhone\":\"" + phone + "\"")
                + ",\"scheduledStart\":\"" + checkIn + "\",\"scheduledEnd\":\"" + checkOut
                + "\",\"clientOrderId\":\"" + UUID.randomUUID() + "\"}";
        return send(as(post("/api/v1/restaurant/open-orders"), TENANT, waiter, ROLE_WAITER), body, expected);
    }

    private JsonNode waiterPost(String path, String body, int expected) throws Exception {
        return send(as(post("/api/v1/restaurant" + path), TENANT, waiter, ROLE_WAITER), body, expected);
    }

    private JsonNode cashierPost(String path, String body, int expected) throws Exception {
        return send(as(post("/api/v1/restaurant" + path), TENANT, cashier, ROLE_CASHIER), body, expected);
    }

    private JsonNode ownerJson(MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
        return send(as(request, TENANT, owner, ROLE_OWNER), body, expected);
    }

    private void setShelfPrice(String amount) {
        pricingService.setSellingPrice(TENANT, new PostSellingPriceRequest(itemId, null, new BigDecimal(amount),
                java.time.LocalDate.now(java.time.ZoneOffset.UTC), null), owner.getId());
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
