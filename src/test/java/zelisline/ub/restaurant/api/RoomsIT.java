package zelisline.ub.restaurant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.serviceops.api.ServiceSaleTestSupport;
import zelisline.ub.serviceops.repository.ServiceResourceRepository;

/**
 * The front desk, Slice 1 (ADR 0028): a lodging tenant's rooms. A room is a service resource of
 * type ROOM with a nightly rate. Managing rooms reuses the restaurant setup permission; a room is
 * kept off the service floor like a table; and no business sees another's rooms.
 */
class RoomsIT extends ServiceSaleTestSupport {

    @Autowired
    private ServiceResourceRepository resourceRepository;

    @BeforeEach
    void clean() {
        resourceRepository.deleteAll();
        // Managing rooms reuses the restaurant setup permission; the base fixture does not grant it.
        String manageId = "5a1e1111-1111-4111-8111-111111111190";
        permissionRepository.save(perm(manageId, "restaurant.menu.manage"));
        grant(ROLE_OWNER, manageId);
    }

    @Test
    void roomsAreAddedListedAndChangedWithANightlyRate() throws Exception {
        JsonNode room = ownerPost("{\"branchId\":\"" + branchId + "\",\"name\":\"Room 1\","
                + "\"nightlyRate\":2500,\"area\":\"Upstairs\"}", 201);
        assertThat(room.get("name").asText()).isEqualTo("Room 1");
        assertThat(room.get("nightlyRate").decimalValue()).isEqualByComparingTo("2500");
        assertThat(room.get("area").asText()).isEqualTo("Upstairs");
        assertThat(room.get("active").asBoolean()).isTrue();
        String roomId = room.get("id").asText();

        // A rate must be above zero and at most two decimal places.
        send(as(post("/api/v1/rooms"), TENANT, owner, ROLE_OWNER),
                "{\"branchId\":\"" + branchId + "\",\"name\":\"Bad\",\"nightlyRate\":0}", 400);
        send(as(post("/api/v1/rooms"), TENANT, owner, ROLE_OWNER),
                "{\"branchId\":\"" + branchId + "\",\"name\":\"Bad\",\"nightlyRate\":10.555}", 400);
        // A name and a rate are required.
        send(as(post("/api/v1/rooms"), TENANT, owner, ROLE_OWNER),
                "{\"branchId\":\"" + branchId + "\",\"name\":\"No rate\"}", 400);

        // Change the rate and take the room out of use.
        JsonNode updated = send(as(patch("/api/v1/rooms/" + roomId), TENANT, owner, ROLE_OWNER),
                "{\"nightlyRate\":3000,\"active\":false}", 200);
        assertThat(updated.get("nightlyRate").decimalValue()).isEqualByComparingTo("3000");
        assertThat(updated.get("active").asBoolean()).isFalse();

        // The list shows the room with its new rate.
        JsonNode rooms = getJson(as(get("/api/v1/rooms").param("branchId", branchId), TENANT, owner, ROLE_OWNER), 200);
        assertThat(rooms).hasSize(1);
        assertThat(rooms.get(0).get("nightlyRate").decimalValue()).isEqualByComparingTo("3000");
    }

    @Test
    void roomsAreNotPlacesOnTheServiceFloorAndTheCashierCannotManageThem() throws Exception {
        String roomId = ownerPost("{\"branchId\":\"" + branchId + "\",\"name\":\"Room 7\",\"nightlyRate\":1800}", 201)
                .get("id").asText();

        // The service floor lists chairs, never rooms, and cannot make or change one.
        JsonNode places = getJson(
                as(get("/api/v1/service-resources").param("branchId", branchId), TENANT, owner, ROLE_OWNER), 200);
        for (JsonNode place : places.get("resources")) {
            assertThat(place.get("resourceType").asText()).isNotEqualTo("ROOM");
            assertThat(place.get("id").asText()).isNotEqualTo(roomId);
        }
        send(as(post("/api/v1/service-resources"), TENANT, owner, ROLE_OWNER),
                "{\"branchId\":\"" + branchId + "\",\"name\":\"Room 8\",\"resourceType\":\"ROOM\"}", 400);
        send(as(patch("/api/v1/service-resources/" + roomId), TENANT, owner, ROLE_OWNER),
                "{\"name\":\"Chair X\"}", 404);

        // Setting up rooms is a manager's job; a cashier who only sells cannot add one.
        mockMvc.perform(as(post("/api/v1/rooms"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"name\":\"Room 9\",\"nightlyRate\":1000}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void noBusinessSeesAnotherBusinessRooms() throws Exception {
        String roomId = ownerPost("{\"branchId\":\"" + branchId + "\",\"name\":\"Room 1\",\"nightlyRate\":2500}", 201)
                .get("id").asText();

        // Another business querying this branch sees no rooms, and cannot change one of ours.
        JsonNode theirView = getJson(
                as(get("/api/v1/rooms").param("branchId", branchId), OTHER, otherOwner, ROLE_OWNER), 200);
        assertThat(theirView).isEmpty();
        mockMvc.perform(as(patch("/api/v1/rooms/" + roomId), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"name\":\"Theirs\"}"))
                .andExpect(status().isNotFound());

        // Ours is untouched.
        assertThat(getJson(as(get("/api/v1/rooms").param("branchId", branchId), TENANT, owner, ROLE_OWNER), 200)
                .get(0).get("name").asText()).isEqualTo("Room 1");
    }

    @Test
    void aRoomMovesThroughItsHousekeepingCycleSeparatelyFromBeingInUse() throws Exception {
        JsonNode room = ownerPost("{\"branchId\":\"" + branchId + "\",\"name\":\"Room 4\",\"nightlyRate\":3500}", 201);
        String roomId = room.get("id").asText();
        // A new room is clean and ready to let.
        assertThat(room.get("housekeepingStatus").asText()).isEqualTo("CLEAN");
        assertThat(room.get("readyToLet").asBoolean()).isTrue();

        // Mark it dirty: still in use, but no longer ready for the next guest.
        JsonNode dirty = hk(roomId, "DIRTY", 200);
        assertThat(dirty.get("housekeepingStatus").asText()).isEqualTo("DIRTY");
        assertThat(dirty.get("readyToLet").asBoolean()).isFalse();
        assertThat(dirty.get("active").asBoolean()).isTrue();

        // Clean it again: ready to let once more.
        assertThat(hk(roomId, "clean", 200).get("readyToLet").asBoolean()).isTrue();

        // Inspected also counts as ready to let; maintenance does not.
        assertThat(hk(roomId, "INSPECTED", 200).get("readyToLet").asBoolean()).isTrue();
        assertThat(hk(roomId, "MAINTENANCE", 200).get("readyToLet").asBoolean()).isFalse();

        // An unknown state is refused; the room is unchanged.
        hk(roomId, "SPARKLING", 400);
        hk(roomId, "", 400);
        assertThat(getJson(as(get("/api/v1/rooms").param("branchId", branchId), TENANT, owner, ROLE_OWNER), 200)
                .get(0).get("housekeepingStatus").asText()).isEqualTo("MAINTENANCE");
    }

    @Test
    void aRoomCarriesAHousekeepingNoteAndItClearsWhenTheRoomIsTurnedClean() throws Exception {
        String roomId = ownerPost("{\"branchId\":\"" + branchId + "\",\"name\":\"Room 6\",\"nightlyRate\":2500}", 201)
                .get("id").asText();

        // Mark it dirty with a note. The note is kept on the room.
        JsonNode dirty = send(as(patch("/api/v1/rooms/" + roomId + "/housekeeping"), TENANT, owner, ROLE_OWNER),
                "{\"status\":\"DIRTY\",\"note\":\"Broken kettle, no towels\"}", 200);
        assertThat(dirty.get("housekeepingNote").asText()).isEqualTo("Broken kettle, no towels");

        // Turning it CLEAN clears the note (a cleaned room starts fresh).
        JsonNode clean = send(as(patch("/api/v1/rooms/" + roomId + "/housekeeping"), TENANT, owner, ROLE_OWNER),
                "{\"status\":\"CLEAN\"}", 200);
        assertThat(clean.get("housekeepingNote").isNull()).isTrue();
        assertThat(clean.get("cleanerStaffProfileId").isNull()).isTrue();

        // A named cleaner must be a real staff member of the business.
        send(as(patch("/api/v1/rooms/" + roomId + "/housekeeping"), TENANT, owner, ROLE_OWNER),
                "{\"status\":\"DIRTY\",\"cleanerStaffProfileId\":\"not-a-real-staff-id\"}", 400);
    }

    @Test
    void housekeepingIsScopedToTheBusinessAndNeedsFrontDeskAccess() throws Exception {
        String roomId = ownerPost("{\"branchId\":\"" + branchId + "\",\"name\":\"Room 5\",\"nightlyRate\":2000}", 201)
                .get("id").asText();

        // Another business cannot touch our room's housekeeping.
        mockMvc.perform(as(patch("/api/v1/rooms/" + roomId + "/housekeeping"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"status\":\"DIRTY\"}"))
                .andExpect(status().isNotFound());

        // A cashier who can sell sees the front desk, so may turn a room round (a cleaner's job).
        hk(roomId, "DIRTY", 200, cashier, ROLE_CASHIER);
        assertThat(getJson(as(get("/api/v1/rooms").param("branchId", branchId), TENANT, owner, ROLE_OWNER), 200)
                .get(0).get("housekeepingStatus").asText()).isEqualTo("DIRTY");
    }

    @Test
    void roomTypesAreCreatedListedUpdatedAndAssignedToRooms() throws Exception {
        JsonNode type = send(as(post("/api/v1/rooms/types"), TENANT, owner, ROLE_OWNER),
                "{\"branchId\":\"" + branchId + "\",\"name\":\"Executive\",\"defaultNightlyRate\":5000,"
                        + "\"weekendNightlyRate\":7000,\"capacity\":2,\"bedConfig\":\"1 king\"}", 201);
        assertThat(type.get("name").asText()).isEqualTo("Executive");
        assertThat(type.get("defaultNightlyRate").decimalValue()).isEqualByComparingTo("5000");
        assertThat(type.get("weekendNightlyRate").decimalValue()).isEqualByComparingTo("7000");
        String typeId = type.get("id").asText();

        // A rate is required and must be above zero; a duplicate name at the branch is refused.
        send(as(post("/api/v1/rooms/types"), TENANT, owner, ROLE_OWNER),
                "{\"branchId\":\"" + branchId + "\",\"name\":\"Bad\",\"defaultNightlyRate\":0}", 400);
        send(as(post("/api/v1/rooms/types"), TENANT, owner, ROLE_OWNER),
                "{\"branchId\":\"" + branchId + "\",\"name\":\"Executive\",\"defaultNightlyRate\":4000}", 409);

        // Update clears the weekend rate with a 0, and changes the weekday rate.
        JsonNode updated = send(as(patch("/api/v1/rooms/types/" + typeId), TENANT, owner, ROLE_OWNER),
                "{\"defaultNightlyRate\":5500,\"weekendNightlyRate\":0}", 200);
        assertThat(updated.get("defaultNightlyRate").decimalValue()).isEqualByComparingTo("5500");
        assertThat(updated.get("weekendNightlyRate").isNull()).isTrue();

        // Assign a room to the type; the room reports its type.
        String roomId = ownerPost("{\"branchId\":\"" + branchId + "\",\"name\":\"Room E\",\"nightlyRate\":1000}", 201)
                .get("id").asText();
        JsonNode typedRoom = send(as(patch("/api/v1/rooms/" + roomId + "/type"), TENANT, owner, ROLE_OWNER),
                "{\"roomTypeId\":\"" + typeId + "\"}", 200);
        assertThat(typedRoom.get("roomTypeId").asText()).isEqualTo(typeId);
        // The room reports the rate a guest is actually charged — the type's 5500, not its own 1000 —
        // so the front desk quotes the right price (the booking-drawer price snag).
        assertThat(typedRoom.get("effectiveNightlyRate").decimalValue()).isEqualByComparingTo("5500");

        // Clear the type with a blank id; the room falls back to its own rate.
        JsonNode cleared = send(as(patch("/api/v1/rooms/" + roomId + "/type"), TENANT, owner, ROLE_OWNER),
                "{\"roomTypeId\":\"\"}", 200);
        assertThat(cleared.get("roomTypeId").isNull()).isTrue();
        assertThat(cleared.get("effectiveNightlyRate").decimalValue()).isEqualByComparingTo("1000");

        // A cashier cannot create or change a type; another business sees none and cannot touch ours.
        mockMvc.perform(as(post("/api/v1/rooms/types"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"name\":\"X\",\"defaultNightlyRate\":100}"))
                .andExpect(status().isForbidden());
        assertThat(getJson(as(get("/api/v1/rooms/types").param("branchId", branchId), OTHER, otherOwner, ROLE_OWNER), 200)
                .get("types")).isEmpty();
        mockMvc.perform(as(patch("/api/v1/rooms/types/" + typeId), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"name\":\"Theirs\"}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode hk(String roomId, String status, int expected) throws Exception {
        return hk(roomId, status, expected, owner, ROLE_OWNER);
    }

    private JsonNode hk(String roomId, String status, int expected, zelisline.ub.identity.domain.User user, String role)
            throws Exception {
        return send(as(patch("/api/v1/rooms/" + roomId + "/housekeeping"), TENANT, user, role),
                "{\"status\":\"" + status + "\"}", expected);
    }

    private JsonNode ownerPost(String body, int expected) throws Exception {
        return send(as(post("/api/v1/rooms"), TENANT, owner, ROLE_OWNER), body, expected);
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
