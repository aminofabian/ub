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
import zelisline.ub.serviceops.repository.RoomMaintenanceIssueRepository;
import zelisline.ub.serviceops.repository.ServiceResourceRepository;

/**
 * Maintenance issues on a lodging room (ADR 0030 Phase 2). The point of the feature: an issue is a
 * persistent ticket that OUTLIVES the housekeeping flag — turning a room CLEAN wipes its housekeeping
 * note, but the maintenance history stays. Also covers the open→in-progress→done lifecycle, the
 * open-issue count on the room and front desk, and that no business sees another's issues.
 */
class RoomMaintenanceIT extends ServiceSaleTestSupport {

    @Autowired
    private ServiceResourceRepository resourceRepository;
    @Autowired
    private RoomMaintenanceIssueRepository issueRepository;

    private String roomId;

    @BeforeEach
    void seed() throws Exception {
        issueRepository.deleteAll();
        resourceRepository.deleteAll();
        String manageId = "5a1e1111-1111-4111-8111-111111111190";
        permissionRepository.save(perm(manageId, "restaurant.menu.manage"));
        grant(ROLE_OWNER, manageId);
        roomId = send(as(post("/api/v1/rooms"), TENANT, owner, ROLE_OWNER),
                "{\"branchId\":\"" + branchId + "\",\"name\":\"Room 4\",\"nightlyRate\":2500}", 201)
                .get("id").asText();
    }

    @Test
    void anIssueOutlivesTheHousekeepingFlagThatIsWipedWhenTheRoomIsCleaned() throws Exception {
        // Raise an issue: the room goes to MAINTENANCE and shows one open issue.
        JsonNode issue = send(as(post("/api/v1/rooms/" + roomId + "/issues"), TENANT, owner, ROLE_OWNER),
                "{\"description\":\"Cracked basin\",\"photoUrl\":\"https://media.example/basin.jpg\"}", 201);
        assertThat(issue.get("status").asText()).isEqualTo("OPEN");
        assertThat(issue.get("description").asText()).isEqualTo("Cracked basin");
        assertThat(issue.get("photoUrl").asText()).isEqualTo("https://media.example/basin.jpg");
        String issueId = issue.get("id").asText();

        JsonNode room = roomById(roomId);
        assertThat(room.get("housekeepingStatus").asText()).isEqualTo("MAINTENANCE");
        assertThat(room.get("openIssueCount").asInt()).isEqualTo(1);
        assertThat(room.get("readyToLet").asBoolean()).isFalse();

        // Turn the room CLEAN — this wipes the housekeeping note/cleaner (existing behaviour)...
        send(as(patch("/api/v1/rooms/" + roomId + "/housekeeping"), TENANT, owner, ROLE_OWNER),
                "{\"status\":\"CLEAN\",\"note\":\"ignored once clean\"}", 200);
        JsonNode cleaned = roomById(roomId);
        assertThat(cleaned.get("housekeepingStatus").asText()).isEqualTo("CLEAN");
        assertThat(cleaned.get("housekeepingNote").isNull()).isTrue();

        // ...but the maintenance issue is still there, in its history, because it is a separate record.
        JsonNode history = getJson(
                as(get("/api/v1/rooms/" + roomId + "/issues"), TENANT, owner, ROLE_OWNER), 200).get("issues");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("id").asText()).isEqualTo(issueId);
        assertThat(history.get(0).get("description").asText()).isEqualTo("Cracked basin");
        // It is still open, so the room still carries the badge even though it reads CLEAN.
        assertThat(roomById(roomId).get("openIssueCount").asInt()).isEqualTo(1);
    }

    @Test
    void anIssueMovesThroughItsLifecycleAndDropsOffTheOpenListWhenDone() throws Exception {
        String issueId = send(as(post("/api/v1/rooms/" + roomId + "/issues"), TENANT, owner, ROLE_OWNER),
                "{\"description\":\"Broken lock\"}", 201).get("id").asText();

        // Open issues at the branch: one.
        assertThat(branchIssues(true)).hasSize(1);

        // Move to IN_PROGRESS — still open.
        JsonNode inProgress = send(as(patch("/api/v1/rooms/issues/" + issueId), TENANT, owner, ROLE_OWNER),
                "{\"status\":\"IN_PROGRESS\"}", 200);
        assertThat(inProgress.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(inProgress.get("closedAt").isNull()).isTrue();
        assertThat(branchIssues(true)).hasSize(1);

        // Mark DONE — it stamps a close time and leaves the open list.
        JsonNode done = send(as(patch("/api/v1/rooms/issues/" + issueId), TENANT, owner, ROLE_OWNER),
                "{\"status\":\"DONE\"}", 200);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("closedAt").isNull()).isFalse();
        assertThat(branchIssues(true)).isEmpty();
        // But it is still in the full history, and the room's badge clears.
        assertThat(branchIssues(false)).hasSize(1);
        assertThat(roomById(roomId).get("openIssueCount").asInt()).isZero();
    }

    @Test
    void aDescriptionIsRequiredAndAStatusMustBeKnown() throws Exception {
        send(as(post("/api/v1/rooms/" + roomId + "/issues"), TENANT, owner, ROLE_OWNER),
                "{\"description\":\"   \"}", 400);
        String issueId = send(as(post("/api/v1/rooms/" + roomId + "/issues"), TENANT, owner, ROLE_OWNER),
                "{\"description\":\"Leaky tap\"}", 201).get("id").asText();
        send(as(patch("/api/v1/rooms/issues/" + issueId), TENANT, owner, ROLE_OWNER),
                "{\"status\":\"CANCELLED\"}", 400);
    }

    @Test
    void noBusinessSeesOrTouchesAnotherTenantsIssues() throws Exception {
        String issueId = send(as(post("/api/v1/rooms/" + roomId + "/issues"), TENANT, owner, ROLE_OWNER),
                "{\"description\":\"Cracked basin\"}", 201).get("id").asText();

        // Another business's branch has no issues of ours.
        assertThat(getJson(as(get("/api/v1/rooms/issues").param("branchId", branchId),
                OTHER, otherOwner, ROLE_OWNER), 200).get("issues")).isEmpty();
        // And it cannot update our issue.
        mockMvc.perform(as(patch("/api/v1/rooms/issues/" + issueId), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isNotFound());
        // An issue on a room that isn't ours is a generic 404.
        mockMvc.perform(as(post("/api/v1/rooms/" + roomId + "/issues"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"description\":\"sneaky\"}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode roomById(String id) throws Exception {
        JsonNode rooms = getJson(as(get("/api/v1/rooms").param("branchId", branchId), TENANT, owner, ROLE_OWNER), 200);
        for (JsonNode room : rooms) {
            if (id.equals(room.get("id").asText())) {
                return room;
            }
        }
        throw new AssertionError("room not found: " + id);
    }

    private JsonNode branchIssues(boolean openOnly) throws Exception {
        return getJson(as(get("/api/v1/rooms/issues")
                .param("branchId", branchId).param("openOnly", String.valueOf(openOnly)),
                TENANT, owner, ROLE_OWNER), 200).get("issues");
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
