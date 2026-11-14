package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

/** The calendar: bookings hold a person and a place, time off blocks bookings, and one feed shows it all. */
class ServiceCalendarIT extends ServiceSaleTestSupport {

    private final Instant ten = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
    private String lenah;
    private String chair1;
    private String chair2;

    @BeforeEach
    void twoPeopleTwoChairs() throws Exception {
        lenah = createStaff(TENANT, owner, "Lenah");
        mockMvc.perform(as(put("/api/v1/service-stations"), TENANT, owner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"count\":2}"))
                .andExpect(status().isOk());
        JsonNode resources = objectMapper.readTree(mockMvc.perform(as(get("/api/v1/service-resources")
                        .param("branchId", branchId), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("resources");
        chair1 = resources.get(0).get("id").asText();
        chair2 = resources.get(1).get("id").asText();
    }

    @Test
    void aBookingHoldsItsChairAsWellAsItsPerson() throws Exception {
        String first = book(john, chair1, ten, 201).get("id").asText();
        // Lenah is free at ten, but Chair 1 is not; Chair 2 is.
        book(lenah, chair1, ten, 409);
        book(lenah, chair1, ten.plus(10, ChronoUnit.MINUTES), 409);
        book(lenah, chair2, ten, 201);
        // The chair is free again once the first booking's time is over.
        book(lenah, chair1, ten.plus(30, ChronoUnit.MINUTES), 201);
        // Another business's chair cannot be held; nor a chair that does not exist.
        book(john, UUID.randomUUID().toString(), ten.plus(3, ChronoUnit.HOURS), 400);

        // Moving a booking takes its chair along, and is refused where the chair is taken.
        mockMvc.perform(as(post("/api/v1/service-tickets/" + first + "/reschedule"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"start\":\"" + ten.plus(40, ChronoUnit.MINUTES) + "\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(as(post("/api/v1/service-tickets/" + first + "/reschedule"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"start\":\"" + ten.plus(2, ChronoUnit.HOURS) + "\"}"))
                .andExpect(status().isOk());

        // A chair taken out of use cannot be booked while it is out.
        mockMvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/service-resources/" + chair2), TENANT, owner, ROLE_OWNER)
                        .content("{\"blockedUntil\":\"" + ten.plus(2, ChronoUnit.DAYS) + "\"}"))
                .andExpect(status().isOk());
        book(john, chair2, ten.plus(5, ChronoUnit.HOURS), 409);
    }

    @Test
    void timeOffStopsBookingsAndOnlyAManagerCanSetIt() throws Exception {
        String off = "{\"from\":\"" + ten.minus(1, ChronoUnit.HOURS) + "\",\"to\":\"" + ten.plus(3, ChronoUnit.HOURS)
                + "\",\"reason\":\"Clinic\"}";
        mockMvc.perform(as(post("/api/v1/service-team/" + john + "/time-off"), TENANT, cashier, ROLE_CASHIER).content(off))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/v1/service-team/" + john + "/time-off"), OTHER, otherOwner, ROLE_OWNER).content(off))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/v1/service-team/" + john + "/time-off"), TENANT, owner, ROLE_OWNER)
                        .content("{\"from\":\"" + ten + "\",\"to\":\"" + ten.minus(1, ChronoUnit.HOURS) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(post("/api/v1/service-team/" + john + "/time-off"), TENANT, owner, ROLE_OWNER).content(off))
                .andExpect(status().isOk());

        book(john, null, ten, 409);
        book(john, null, ten.plus(150, ChronoUnit.MINUTES), 409);
        book(lenah, null, ten, 201);
        book(john, null, ten.plus(3, ChronoUnit.HOURS), 201);

        JsonNode calendar = calendar(TENANT, ten.minus(2, ChronoUnit.HOURS), ten.plus(6, ChronoUnit.HOURS), 200);
        assertThat(kinds(calendar)).contains("TIME_OFF", "BOOKED");
        // Removed: he can be booked again.
        mockMvc.perform(as(delete("/api/v1/service-team/" + john + "/time-off")
                        .param("from", ten.minus(1, ChronoUnit.HOURS).toString()), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isOk());
        book(john, null, ten, 201);
    }

    @Test
    void theCalendarShowsBookingsAndWorkByPersonAndByPlace() throws Exception {
        book(john, chair1, ten, 201);
        String missed = book(lenah, null, ten.plus(1, ChronoUnit.HOURS), 201).get("id").asText();
        mockMvc.perform(as(post("/api/v1/service-tickets/" + missed + "/no-show"), TENANT, cashier, ROLE_CASHIER).content("{}"))
                .andExpect(status().isOk());
        // A walk-in being served right now, on Chair 2.
        MvcResult opened = mockMvc.perform(as(post("/api/v1/service-tickets"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode walkIn = objectMapper.readTree(opened.getResponse().getContentAsString());
        mockMvc.perform(as(post("/api/v1/service-tickets/" + walkIn.get("id").asText() + "/lines/"
                        + walkIn.get("lines").get(0).get("id").asText() + "/start"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"performerStaffProfileId\":\"" + lenah + "\",\"resourceId\":\"" + chair2 + "\"}"))
                .andExpect(status().isOk());

        JsonNode calendar = calendar(TENANT, Instant.now().minus(1, ChronoUnit.HOURS), ten.plus(6, ChronoUnit.HOURS), 200);
        assertThat(calendar.get("staff")).hasSize(2);
        assertThat(calendar.get("resources")).hasSize(2);
        assertThat(kinds(calendar)).containsExactlyInAnyOrder("IN_SERVICE", "BOOKED", "NO_SHOW");
        for (JsonNode entry : calendar.get("entries")) {
            assertThat(Instant.parse(entry.get("end").asText())).isAfter(Instant.parse(entry.get("start").asText()));
            if ("BOOKED".equals(entry.get("kind").asText())) {
                assertThat(entry.get("staffProfileId").asText()).isEqualTo(john);
                assertThat(entry.get("resourceId").asText()).isEqualTo(chair1);
            }
            if ("IN_SERVICE".equals(entry.get("kind").asText())) {
                assertThat(entry.get("staffProfileId").asText()).isEqualTo(lenah);
                assertThat(entry.get("resourceId").asText()).isEqualTo(chair2);
            }
        }
        // Another business sees none of it, and the window is bounded.
        calendar(OTHER, ten.minus(2, ChronoUnit.HOURS), ten.plus(6, ChronoUnit.HOURS), 404);
        calendar(TENANT, ten, ten.plus(30, ChronoUnit.DAYS), 400);
    }

    private JsonNode book(String staff, String resourceId, Instant start, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/appointments"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"staffProfileId\":\"" + staff + "\",\"start\":\"" + start + "\",\"name\":\"Wanjiru\","
                                + (resourceId == null ? "" : "\"resourceId\":\"" + resourceId + "\",")
                                + "\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}"))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode calendar(String tenant, Instant from, Instant to, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(get("/api/v1/service-tickets/calendar").param("branchId", branchId)
                        .param("from", from.toString()).param("to", to.toString()),
                        tenant, tenant.equals(TENANT) ? cashier : otherOwner, tenant.equals(TENANT) ? ROLE_CASHIER : ROLE_OWNER))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> kinds(JsonNode calendar) {
        List<String> out = new ArrayList<>();
        for (JsonNode entry : calendar.get("entries")) {
            out.add(entry.get("kind").asText());
        }
        return out;
    }
}
