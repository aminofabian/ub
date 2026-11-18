package zelisline.ub.storefront.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.serviceops.api.ServiceSaleTestSupport;
import zelisline.ub.serviceops.repository.ServiceResourceRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * The public room-booking storefront (ADR 0031): a guest requests a stay from {@code {slug}.kiosk.ke}
 * with no account. Rooms are shown only when the house turns {@code rooms_online} on; the business is
 * resolved from the slug, never the body, so no tenant leaks another's rooms; a request opens a
 * BOOKED stay on the front desk, and a retry books nobody twice.
 */
class PublicRoomBookingIT extends ServiceSaleTestSupport {

    @Autowired
    private ServiceResourceRepository resourceRepository;
    @Autowired
    private BusinessRepository businessRepository;

    private String slug;
    private String base;
    private String roomId;
    private LocalDate checkIn;
    private LocalDate checkOut;

    @BeforeEach
    void seed() throws Exception {
        resourceRepository.deleteAll();
        slug = businessRepository.findById(TENANT).orElseThrow().getSlug();
        base = "/api/v1/public/businesses/" + slug + "/rooms";
        // Managing rooms reuses the restaurant setup permission; grant it to the owner.
        String manageId = "5a1e1111-1111-4111-8111-111111111190";
        permissionRepository.save(perm(manageId, "restaurant.menu.manage"));
        grant(ROLE_OWNER, manageId);
        // rooms_online persists on the shared tenant; reset it off so each test is order-independent.
        mockMvc.perform(as(put("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER)
                        .content("{\"roomsOnline\":false}"))
                .andExpect(status().isOk());
        checkIn = LocalDate.now(ZoneId.of("Africa/Nairobi")).plusDays(2);
        checkOut = checkIn.plusDays(2);
    }

    @Test
    void roomsAreHiddenUntilTheHouseTurnsThemOnline() throws Exception {
        addRoom("Room 1", 3000);
        // Off by default: the public page is a generic 404, indistinguishable from a wrong slug.
        mockMvc.perform(get(base)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/public/businesses/no-such-shop/rooms")).andExpect(status().isNotFound());

        turnRoomsOnline();
        JsonNode info = getJson(get(base), 200);
        assertThat(info.get("bookable").asBoolean()).isTrue();
        assertThat(info.get("branches")).hasSize(1);
    }

    @Test
    void aGuestSeesFreeRoomsAndRequestsAStayThatOpensABookingOnTheFrontDesk() throws Exception {
        String roomOne = addRoom("Room 1", 3000);
        String roomTwo = addRoom("Room 2", 3500);
        turnRoomsOnline();

        // Both rooms are free for the nights.
        JsonNode avail = getJson(get(base + "/availability")
                .param("branchId", branchId)
                .param("checkIn", checkIn.toString())
                .param("checkOut", checkOut.toString()), 200);
        assertThat(avail.get("nights").asInt()).isEqualTo(2);
        assertThat(avail.get("rooms")).hasSize(2);

        // Request Room 1. It opens a BOOKED stay; a replay of the same request opens no second stay.
        String requestId = "web-" + UUID.randomUUID().toString().replace("-", "");
        String body = bookingBody(roomOne, requestId);
        JsonNode confirm = send(post(base + "/bookings"), body, 201);
        assertThat(confirm.get("nights").asInt()).isEqualTo(2);
        assertThat(confirm.get("message").asText()).contains("confirm");
        send(post(base + "/bookings"), body, 201); // idempotent replay

        // Room 1 is now taken for those nights; Room 2 is still free.
        JsonNode after = getJson(get(base + "/availability")
                .param("branchId", branchId)
                .param("checkIn", checkIn.toString())
                .param("checkOut", checkOut.toString()), 200);
        assertThat(after.get("rooms")).hasSize(1);
        assertThat(after.get("rooms").get(0).get("id").asText()).isEqualTo(roomTwo);

        // The stay shows on the front desk as a booking.
        JsonNode desk = getJson(as(get("/api/v1/rooms/front-desk").param("branchId", branchId),
                TENANT, owner, ROLE_OWNER), 200);
        boolean booked = false;
        for (JsonNode room : desk.get("rooms")) {
            if (roomOne.equals(room.get("room").get("id").asText()) && room.get("stays").size() > 0) {
                booked = room.get("stays").get(0).get("booked").asBoolean();
            }
        }
        assertThat(booked).isTrue();
    }

    @Test
    void badDatesAndAnotherBusinessAreRefused() throws Exception {
        addRoom("Room 1", 3000);
        turnRoomsOnline();

        // Check-out before check-in is refused.
        send(post(base + "/bookings"),
                bookingBody(roomId, "web-" + UUID.randomUUID().toString().replace("-", ""))
                        .replace(checkOut.toString(), checkIn.minusDays(1).toString()),
                400);

        // Another business's public page does not show our rooms (its own are off / none).
        String otherSlug = businessRepository.findById(OTHER).orElseThrow().getSlug();
        mockMvc.perform(get("/api/v1/public/businesses/" + otherSlug + "/rooms")).andExpect(status().isNotFound());
    }

    @Test
    void anOnlineDepositIsScopedToTheBookingAndRefusesAStrangerId() throws Exception {
        String room = addRoom("Room 1", 3000);
        turnRoomsOnline();
        String requestId = "web-" + UUID.randomUUID().toString().replace("-", "");
        JsonNode confirm = send(post(base + "/bookings"), bookingBody(room, requestId), 201);
        String bookingId = confirm.get("bookingId").asText();
        assertThat(bookingId).isNotBlank();

        // A made-up booking id is a generic 404, never a gateway prompt.
        send(post(base + "/deposit"),
                "{\"bookingId\":\"5a1e0000-0000-4000-8000-000000000000\"}", 404);

        // A real stay from another slug cannot be paid through this house's page.
        String otherSlug = businessRepository.findById(OTHER).orElseThrow().getSlug();
        send(post("/api/v1/public/businesses/" + otherSlug + "/rooms/deposit"),
                "{\"bookingId\":\"" + bookingId + "\"}", 404);
    }

    // ------------------------------------------------------------------ helpers

    private String addRoom(String name, int rate) throws Exception {
        roomId = send(as(post("/api/v1/rooms"), TENANT, owner, ROLE_OWNER),
                "{\"branchId\":\"" + branchId + "\",\"name\":\"" + name + "\",\"nightlyRate\":" + rate + "}", 201)
                .get("id").asText();
        return roomId;
    }

    private void turnRoomsOnline() throws Exception {
        mockMvc.perform(as(put("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER)
                        .content("{\"roomsOnline\":true}"))
                .andExpect(status().isOk());
    }

    private String bookingBody(String room, String requestId) {
        return "{\"branchId\":\"" + branchId + "\",\"roomId\":\"" + room + "\","
                + "\"checkIn\":\"" + checkIn + "\",\"checkOut\":\"" + checkOut + "\","
                + "\"name\":\"Jane Doe\",\"phone\":\"0712345678\",\"guests\":1,"
                + "\"requestId\":\"" + requestId + "\"}";
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
        if (!body.isEmpty()) {
            request = request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body);
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
