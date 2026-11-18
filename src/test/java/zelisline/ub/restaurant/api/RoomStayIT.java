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
    void nightsAreCountedByCalendarDateNotByTwentyFourHourBlocks() throws Exception {
        // A real stay: check in tomorrow at 2pm Nairobi, check out the day after next at 11am — only
        // 45 hours, but two calendar nights slept, so it is charged two nights (2 × 2500 = 5000).
        java.time.ZoneId zone = java.time.ZoneId.of("Africa/Nairobi");
        java.time.LocalDate day = java.time.LocalDate.now(zone).plusDays(1);
        Instant checkIn = day.atTime(14, 0).atZone(zone).toInstant();
        Instant checkOut = day.plusDays(2).atTime(11, 0).atZone(zone).toInstant();
        assertThat(java.time.Duration.between(checkIn, checkOut).toHours()).isEqualTo(45);

        String stay = openStayNamed(room1, checkIn, checkOut, "Night counter", null, 201).get("id").asText();
        waiterPost("/open-orders/" + stay + "/seat", "", 200);
        JsonNode roomLine = getJson(as(get("/api/v1/restaurant/open-orders/" + stay), TENANT, waiter, ROLE_WAITER), 200)
                .get("lines").get(0);
        assertThat(roomLine.get("kind").asText()).isEqualTo("ROOM");
        assertThat(roomLine.get("quantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(roomLine.get("lineTotal").decimalValue()).isEqualByComparingTo("5000");
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

    @Test
    void aRoomOutForRepairTakesNoStayUntilItIsClearedAgain() throws Exception {
        // Put Room 1 under maintenance (ADR 0030): it is out of use for a stay however its dates look.
        send(as(patch("/api/v1/rooms/" + room1 + "/housekeeping"), TENANT, owner, ROLE_OWNER),
                "{\"status\":\"MAINTENANCE\"}", 200);

        Instant checkIn = Instant.now().plusSeconds(3600);
        Instant checkOut = checkIn.plusSeconds(86400);
        openStay(room1, checkIn, checkOut, 409);

        // Clear it, and the booking goes through.
        send(as(patch("/api/v1/rooms/" + room1 + "/housekeeping"), TENANT, owner, ROLE_OWNER),
                "{\"status\":\"CLEAN\"}", 200);
        openStay(room1, checkIn, checkOut, 201);
    }

    @Test
    void aRoomSentForRepairAfterBookingCannotTakeTheGuestInEither() throws Exception {
        // Book while the room is fine, then send it for repair before the guest arrives.
        Instant checkIn = Instant.now().plusSeconds(3600);
        String stay = openStay(room1, checkIn, checkIn.plusSeconds(86400), 201).get("id").asText();
        send(as(patch("/api/v1/rooms/" + room1 + "/housekeeping"), TENANT, owner, ROLE_OWNER),
                "{\"status\":\"MAINTENANCE\"}", 200);

        // Check-in is refused: the guest cannot be put into a room out for repair (ADR 0030).
        waiterPost("/open-orders/" + stay + "/seat", "", 409);

        // Clear it and the guest checks in.
        send(as(patch("/api/v1/rooms/" + room1 + "/housekeeping"), TENANT, owner, ROLE_OWNER),
                "{\"status\":\"CLEAN\"}", 200);
        waiterPost("/open-orders/" + stay + "/seat", "", 200);
    }

    @Test
    void aDrinkChargedToACheckedInRoomRidesTheFolioAndRingsNoSaleNow() throws Exception {
        Instant checkIn = Instant.now().plusSeconds(3600);
        String stay = openStayNamed(room1, checkIn, checkIn.plusSeconds(86400), "Ada", null, 201).get("id").asText();

        // A booking not yet arrived cannot be charged, and is not offered as in-house.
        chargeToRoom(stay, "c1", 409);
        assertThat(getJson(as(get("/api/v1/restaurant/in-house-stays").param("branchId", branchId),
                TENANT, cashier, ROLE_CASHIER), 200).get("stays")).isEmpty();

        // Check in. Now the guest is in-house and chargeable.
        waiterPost("/open-orders/" + stay + "/seat", "", 200);
        JsonNode stays = getJson(as(get("/api/v1/restaurant/in-house-stays").param("branchId", branchId),
                TENANT, cashier, ROLE_CASHIER), 200).get("stays");
        assertThat(stays).hasSize(1);
        assertThat(stays.get(0).get("orderId").asText()).isEqualTo(stay);
        assertThat(stays.get(0).get("roomName").asText()).isEqualTo("Room 1");

        // Charge a soda to the room. It lands on the stay's folio; no sale is rung for it now.
        long salesBefore = saleRepository.count();
        JsonNode after = chargeToRoom(stay, "c1", 200);
        assertThat(after.get("status").asText()).isEqualTo("pending");
        assertThat(saleRepository.count()).isEqualTo(salesBefore);
        boolean hasSoda = false;
        for (JsonNode line : after.get("lines")) {
            if ("Soda".equals(line.get("name").asText())) hasSoda = true;
        }
        assertThat(hasSoda).isTrue();

        // Charging the same cart again (same client line id) does not add the soda twice.
        JsonNode retry = chargeToRoom(stay, "c1", 200);
        int sodas = 0;
        for (JsonNode line : retry.get("lines")) {
            if ("Soda".equals(line.get("name").asText())) sodas++;
        }
        assertThat(sodas).isEqualTo(1);

        // Settle the folio: one night (2500) + one soda (200) = 2700, as one sale.
        waiterPost("/open-orders/" + stay + "/send", "", 200);
        String billId = waiterPost("/open-orders/" + stay + "/bills", "", 201).get("bills").get(0).get("id").asText();
        JsonNode paid = cashierPost("/open-orders/" + stay + "/bills/" + billId + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":2700}]}", 200);
        assertThat(paid.get("status").asText()).isEqualTo("completed");
        assertThat(accountBalance(LedgerAccountCodes.ROOM_REVENUE)).isEqualByComparingTo("2500.00");
        assertThat(accountBalance(LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("200.00");

        // A settled stay takes no more charges, and drops off the in-house list.
        chargeToRoom(stay, "c2", 409);
        assertThat(getJson(as(get("/api/v1/restaurant/in-house-stays").param("branchId", branchId),
                TENANT, cashier, ROLE_CASHIER), 200).get("stays")).isEmpty();
    }

    @Test
    void checkingOutSendsTheRoomToHousekeepingNotStraightBackToFree() throws Exception {
        Instant checkIn = Instant.now().plusSeconds(3600);
        String stay = openStay(room1, checkIn, checkIn.plusSeconds(86400), 201).get("id").asText();
        waiterPost("/open-orders/" + stay + "/seat", "", 200);

        // The room is in use but still clean while the guest is in.
        assertThat(roomHousekeeping(room1)).isEqualTo("CLEAN");

        // Check out: settle the one-night folio (2500).
        waiterPost("/open-orders/" + stay + "/send", "", 200);
        String billId = waiterPost("/open-orders/" + stay + "/bills", "", 201).get("bills").get(0).get("id").asText();
        cashierPost("/open-orders/" + stay + "/bills/" + billId + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":2500}]}", 200);

        // The room is now DIRTY — not free — and is not ready to let until it is turned round (ADR 0030).
        JsonNode room = getJson(as(get("/api/v1/rooms").param("branchId", branchId), TENANT, owner, ROLE_OWNER), 200)
                .get(0);
        assertThat(room.get("housekeepingStatus").asText()).isEqualTo("DIRTY");
        assertThat(room.get("readyToLet").asBoolean()).isFalse();

        // Turn it round and it can take the next guest again.
        send(as(patch("/api/v1/rooms/" + room1 + "/housekeeping"), TENANT, owner, ROLE_OWNER), "{\"status\":\"CLEAN\"}", 200);
        openStay(room1, Instant.now().plusSeconds(7200), Instant.now().plusSeconds(93600), 201);
    }

    @Test
    void anotherBusinessCannotChargeToOrSeeThisOnesStays() throws Exception {
        Instant checkIn = Instant.now().plusSeconds(3600);
        String stay = openStay(room1, checkIn, checkIn.plusSeconds(86400), 201).get("id").asText();
        waiterPost("/open-orders/" + stay + "/seat", "", 200);

        // The other business sees no in-house stays at this branch and cannot charge to ours.
        assertThat(getJson(as(get("/api/v1/restaurant/in-house-stays").param("branchId", branchId),
                OTHER, otherOwner, ROLE_OWNER), 200).get("stays")).isEmpty();
        mockMvc.perform(as(post("/api/v1/restaurant/open-orders/" + stay + "/charge-to-room"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"lines\":[{\"clientLineId\":\"x\",\"menuItemId\":\"" + soda + "\",\"quantity\":1}]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aBookingCanMoveRoomAndChangeDatesButNotOnceTheGuestIsIn() throws Exception {
        Instant checkIn = Instant.now().plusSeconds(3600);
        Instant checkOut = checkIn.plusSeconds(2 * 86400);
        String stay = openStay(room1, checkIn, checkOut, 201).get("id").asText();

        // Move the booking to Room 2.
        JsonNode moved = waiterPost("/open-orders/" + stay + "/move-room",
                "{\"roomId\":\"" + room2 + "\"}", 200);
        assertThat(moved.get("tableId").asText()).isEqualTo(room2);

        // A room already booked for overlapping nights cannot be moved onto.
        String other = openStay(room1, checkIn, checkOut, 201).get("id").asText();
        waiterPost("/open-orders/" + other + "/move-room", "{\"roomId\":\"" + room2 + "\"}", 409);

        // Extend the stay to three nights (no charge yet — nothing is charged until check-in).
        Instant longerOut = checkIn.plusSeconds(3 * 86400);
        JsonNode extended = waiterPost("/open-orders/" + stay + "/stay-dates",
                "{\"checkIn\":\"" + checkIn + "\",\"checkOut\":\"" + longerOut + "\"}", 200);
        assertThat(extended.get("scheduledEnd").asText()).startsWith(longerOut.toString().substring(0, 10));

        // Check in: the room-night line reflects the extended three nights at Room 2's 3000 rate.
        waiterPost("/open-orders/" + stay + "/seat", "", 200);
        JsonNode roomLine = getJson(as(get("/api/v1/restaurant/open-orders/" + stay), TENANT, waiter, ROLE_WAITER), 200)
                .get("lines").get(0);
        assertThat(roomLine.get("kind").asText()).isEqualTo("ROOM");
        assertThat(roomLine.get("quantity").decimalValue()).isEqualByComparingTo("3");
        assertThat(roomLine.get("unitPrice").decimalValue()).isEqualByComparingTo("3000");

        // Once the guest is in, neither the dates nor the room can be changed here.
        waiterPost("/open-orders/" + stay + "/stay-dates",
                "{\"checkIn\":\"" + checkIn + "\",\"checkOut\":\"" + checkOut + "\"}", 409);
    }

    @Test
    void aRoomTypesWeekendRateIsResolvedAndFrozenAtCheckInAsTwoLines() throws Exception {
        // A type: 3000 a weekday night, 5000 a weekend (Fri/Sat) night. A room of that type takes its
        // rate card (the type leads so the weekend rate applies).
        String type = ownerJson(post("/api/v1/rooms/types"),
                "{\"branchId\":\"" + branchId + "\",\"name\":\"Standard\",\"defaultNightlyRate\":3000,"
                        + "\"weekendNightlyRate\":5000}", 201).get("id").asText();
        String typedRoom = ownerJson(post("/api/v1/rooms"),
                "{\"branchId\":\"" + branchId + "\",\"name\":\"Typed\",\"nightlyRate\":9999}", 201).get("id").asText();
        send(as(patch("/api/v1/rooms/" + typedRoom + "/type"), TENANT, owner, ROLE_OWNER),
                "{\"roomTypeId\":\"" + type + "\"}", 200);

        // A stay over a Thursday night and a Friday night: one weekday, one weekend.
        java.time.ZoneId zone = java.time.ZoneId.of("Africa/Nairobi");
        java.time.LocalDate thursday = nextDayOfWeek(java.time.DayOfWeek.THURSDAY);
        Instant checkIn = thursday.atTime(14, 0).atZone(zone).toInstant();
        Instant checkOut = thursday.plusDays(2).atTime(11, 0).atZone(zone).toInstant();

        String stay = openStayNamed(typedRoom, checkIn, checkOut, "Rate test", null, 201).get("id").asText();
        waiterPost("/open-orders/" + stay + "/seat", "", 200);
        JsonNode lines = getJson(as(get("/api/v1/restaurant/open-orders/" + stay), TENANT, waiter, ROLE_WAITER), 200)
                .get("lines");

        // Two ROOM lines: one weekday night at 3000 and one weekend night at 5000 — the type's card,
        // not the room's own 9999 rate (a typed room ignores its own rate), frozen onto the folio.
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        java.util.List<java.math.BigDecimal> rates = new java.util.ArrayList<>();
        for (JsonNode line : lines) {
            if ("ROOM".equals(line.get("kind").asText())) {
                total = total.add(line.get("lineTotal").decimalValue());
                rates.add(line.get("unitPrice").decimalValue());
            }
        }
        assertThat(rates).hasSize(2);
        assertThat(total).isEqualByComparingTo("8000");
        assertThat(rates).anySatisfy(r -> assertThat(r).isEqualByComparingTo("3000"));
        assertThat(rates).anySatisfy(r -> assertThat(r).isEqualByComparingTo("5000"));

        // Changing the type's rate afterwards never moves this frozen folio (ADR 0028).
        send(as(patch("/api/v1/rooms/types/" + type), TENANT, owner, ROLE_OWNER),
                "{\"defaultNightlyRate\":9999}", 200);
        java.math.BigDecimal after = java.math.BigDecimal.ZERO;
        for (JsonNode line : getJson(as(get("/api/v1/restaurant/open-orders/" + stay), TENANT, waiter, ROLE_WAITER), 200)
                .get("lines")) {
            if ("ROOM".equals(line.get("kind").asText())) after = after.add(line.get("lineTotal").decimalValue());
        }
        assertThat(after).isEqualByComparingTo("8000");
    }

    /** The next date (from tomorrow) that falls on the given weekday, in Nairobi. */
    private static java.time.LocalDate nextDayOfWeek(java.time.DayOfWeek target) {
        java.time.LocalDate d = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Nairobi")).plusDays(1);
        while (d.getDayOfWeek() != target) {
            d = d.plusDays(1);
        }
        return d;
    }

    @Test
    void theAccommodationReportShowsTodaysRoomsAndThePeriodsOccupancyAndRevenue() throws Exception {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Nairobi"));

        // One stay checked in now (occupies a room); book, check in, settle one night at 2500 so there
        // is room revenue in the period.
        Instant checkIn = Instant.now().minusSeconds(3600);
        String stay = openStay(room1, checkIn, checkIn.plusSeconds(86400), 201).get("id").asText();
        waiterPost("/open-orders/" + stay + "/seat", "", 200);
        waiterPost("/open-orders/" + stay + "/send", "", 200);
        String billId = waiterPost("/open-orders/" + stay + "/bills", "", 201).get("bills").get(0).get("id").asText();
        cashierPost("/open-orders/" + stay + "/bills/" + billId + "/pay",
                "{\"payments\":[{\"method\":\"cash\",\"amount\":2500}]}", 200);
        // The settled stay sent Room 1 to housekeeping (DIRTY), so it is now one room to clean.

        JsonNode report = getJson(as(get("/api/v1/rooms/report")
                .param("branchId", branchId).param("from", today.toString()).param("to", today.toString()),
                TENANT, owner, ROLE_OWNER), 200);

        // Two rooms in all; after checkout the guest is gone, Room 1 is dirty, Room 2 is free.
        assertThat(report.get("totalRooms").asInt()).isEqualTo(2);
        assertThat(report.get("dirty").asInt()).isEqualTo(1);
        // One room-night sold at 2500 → that is the accommodation revenue and the average rate.
        assertThat(report.get("roomNightsSold").asLong()).isEqualTo(1);
        assertThat(report.get("accommodationRevenue").decimalValue()).isEqualByComparingTo("2500");
        assertThat(report.get("averageDailyRate").decimalValue()).isEqualByComparingTo("2500");
        // RevPAR = revenue ÷ (2 rooms × 1 day) = 1250; occupancy = 1 night ÷ 2 available = 0.5.
        assertThat(report.get("revPar").decimalValue()).isEqualByComparingTo("1250");
        assertThat(report.get("occupancyRate").decimalValue()).isEqualByComparingTo("0.5");

        // A bad range is refused, and another business sees nothing of ours.
        mockMvc.perform(as(get("/api/v1/rooms/report").param("branchId", branchId)
                        .param("from", today.toString()).param("to", today.minusDays(1).toString()),
                TENANT, owner, ROLE_OWNER)).andExpect(status().isBadRequest());
        JsonNode theirs = getJson(as(get("/api/v1/rooms/report").param("branchId", branchId)
                        .param("from", today.toString()).param("to", today.toString()),
                OTHER, otherOwner, ROLE_OWNER), 200);
        assertThat(theirs.get("totalRooms").asInt()).isZero();
        assertThat(theirs.get("accommodationRevenue").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void aReturningGuestsStayHistoryIsListedNewestFirstAndScopedToTheBusiness() throws Exception {
        // A deposit links a booking to a directory customer (found/made by phone). Book twice for the
        // same phone so both stays share one customer.
        Instant checkIn = Instant.now().plusSeconds(3600);
        String first = openStayNamed(room1, checkIn, checkIn.plusSeconds(86400), "Repeat Guest", "0712345678", 201)
                .get("id").asText();
        cashierPost("/open-orders/" + first + "/deposit", "{\"amount\":500,\"method\":\"mpesa_manual\"}", 200);
        String customerId = getJson(as(get("/api/v1/restaurant/open-orders/" + first), TENANT, waiter, ROLE_WAITER), 200)
                .get("customerId").asText();
        assertThat(customerId).isNotBlank();

        String second = openStayNamed(room2, checkIn.plusSeconds(10 * 86400), checkIn.plusSeconds(11 * 86400),
                "Repeat Guest", "0712345678", 201).get("id").asText();
        cashierPost("/open-orders/" + second + "/deposit", "{\"amount\":500,\"method\":\"mpesa_manual\"}", 200);

        // The history lists both stays, newest (the later check-in) first.
        JsonNode history = getJson(as(get("/api/v1/rooms/guest-stays").param("customerId", customerId),
                TENANT, waiter, ROLE_WAITER), 200);
        assertThat(history.get("stayCount").asInt()).isEqualTo(2);
        assertThat(history.get("customerName").asText()).isEqualTo("Repeat Guest");
        assertThat(history.get("stays").get(0).get("orderId").asText()).isEqualTo(second);
        assertThat(history.get("stays").get(0).get("state").asText()).isEqualTo("booked");
        assertThat(history.get("stays").get(1).get("orderId").asText()).isEqualTo(first);

        // Another business sees no stays for that customer id.
        assertThat(getJson(as(get("/api/v1/rooms/guest-stays").param("customerId", customerId),
                OTHER, otherOwner, ROLE_OWNER), 200).get("stayCount").asInt()).isZero();
    }

    @Test
    void aGuestCannotBeMovedOntoARoomAnotherGuestIsCheckedInto() throws Exception {
        Instant checkIn = Instant.now().plusSeconds(3600);
        Instant checkOut = checkIn.plusSeconds(2 * 86400);
        // Guest A checks into Room 1.
        String stayA = openStay(room1, checkIn, checkOut, 201).get("id").asText();
        waiterPost("/open-orders/" + stayA + "/seat", "", 200);

        // Guest B is in Room 2. Moving B onto Room 1 — where A is checked in — is refused, even though
        // A's stay is seated (service stage cleared) and so invisible to the booking-only overlap query.
        String stayB = openStay(room2, checkIn, checkOut, 201).get("id").asText();
        waiterPost("/open-orders/" + stayB + "/seat", "", 200);
        waiterPost("/open-orders/" + stayB + "/move-room", "{\"roomId\":\"" + room1 + "\"}", 409);

        // A new booking for later also cannot take a room a guest is still in over those nights.
        openStay(room1, checkIn.plusSeconds(86400), checkOut.plusSeconds(86400), 409);
    }

    @Test
    void aGuestIdCanBeRecordedOnAStayAndClearedScopedToTheBusiness() throws Exception {
        Instant checkIn = Instant.now().plusSeconds(3600);
        String stay = openStayNamed(room1, checkIn, checkIn.plusSeconds(86400), "Guest", null, 201).get("id").asText();

        // Record a National ID on the stay; it comes back on the folio.
        JsonNode saved = waiterPost("/open-orders/" + stay + "/guest-id",
                "{\"idType\":\"National ID\",\"idNumber\":\"12345678\"}", 200);
        assertThat(saved.get("guestIdType").asText()).isEqualTo("National ID");
        assertThat(saved.get("guestIdNumber").asText()).isEqualTo("12345678");

        // Clearing it (blank) removes it.
        JsonNode cleared = waiterPost("/open-orders/" + stay + "/guest-id", "{\"idType\":\"\",\"idNumber\":\"\"}", 200);
        assertThat(cleared.get("guestIdNumber").isNull()).isTrue();

        // Another business cannot touch this stay's ID.
        mockMvc.perform(as(post("/api/v1/restaurant/open-orders/" + stay + "/guest-id"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"idType\":\"Passport\",\"idNumber\":\"X\"}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private String roomHousekeeping(String roomId) throws Exception {
        for (JsonNode r : getJson(as(get("/api/v1/rooms").param("branchId", branchId), TENANT, owner, ROLE_OWNER), 200)) {
            if (roomId.equals(r.get("id").asText())) {
                return r.get("housekeepingStatus").asText();
            }
        }
        throw new AssertionError("room not found: " + roomId);
    }

    private JsonNode chargeToRoom(String stay, String clientLineId, int expected) throws Exception {
        return waiterPost("/open-orders/" + stay + "/charge-to-room",
                "{\"lines\":[{\"clientLineId\":\"" + clientLineId + "\",\"menuItemId\":\"" + soda + "\",\"quantity\":1}]}",
                expected);
    }

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
