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

    // ------------------------------------------------------------------ helpers

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
