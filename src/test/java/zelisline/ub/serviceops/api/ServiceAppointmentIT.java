package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.identity.domain.User;

/** Appointments (a service ticket that has not arrived yet), queue priority, wait estimate and floor status. */
class ServiceAppointmentIT extends ServiceSaleTestSupport {

    /** Tomorrow, on the hour: far enough ahead never to be "in the past". */
    private final Instant ten = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

    @BeforeEach
    void threeChairs() throws Exception {
        mockMvc.perform(as(put("/api/v1/service-stations"), TENANT, owner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"count\":3}"))
                .andExpect(status().isOk());
    }

    @Test
    void aBookingHoldsTheSlotArrivesIntoTheQueueAndIsPaidLikeAnyTicket() throws Exception {
        String clientId = UUID.randomUUID().toString();
        JsonNode booked = book(clientId, john, ten, "Wanjiru", 201);
        String id = booked.get("id").asText();
        assertThat(booked.get("stage").asText()).isEqualTo("BOOKED");
        assertThat(booked.get("bookingName").asText()).isEqualTo("Wanjiru");
        assertThat(Instant.parse(booked.get("scheduledStart").asText())).isEqualTo(ten);
        // The haircut in the fixtures decides how long the slot is.
        int minutes = serviceRepository.findById(haircut).orElseThrow().getDurationMinutes();
        assertThat(Instant.parse(booked.get("scheduledEnd").asText())).isEqualTo(ten.plus(minutes, ChronoUnit.MINUTES));

        // The same request again is the same booking, not a clash with itself.
        assertThat(book(clientId, john, ten, "Wanjiru", 201).get("id").asText()).isEqualTo(id);

        // John cannot be booked twice for overlapping times; back to back is fine; Peter is free.
        book(UUID.randomUUID().toString(), john, ten.plus(minutes - 1, ChronoUnit.MINUTES), "Otieno", 409);
        book(UUID.randomUUID().toString(), john, ten.minus(1, ChronoUnit.MINUTES), "Otieno", 409);
        book(UUID.randomUUID().toString(), john, ten.plus(minutes, ChronoUnit.MINUTES), "Otieno", 201);
        String peter = createStaff(TENANT, owner, "Peter");
        book(UUID.randomUUID().toString(), peter, ten, "Achieng", 201);

        // A booking is on the calendar, not on the floor.
        assertThat(calendar(ten.minus(1, ChronoUnit.HOURS), ten.plus(5, ChronoUnit.HOURS))).hasSize(3);
        assertThat(board().get("tickets")).isEmpty();
        // It cannot be paid before anyone has served the customer.
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "early-" + UUID.randomUUID())
                        .content("{\"payments\":[{\"method\":\"cash\",\"amount\":300}]}"))
                .andExpect(status().isConflict());

        assertThat(act(id, "arrive", "{}", 200).get("stage").asText()).isEqualTo("WAITING");
        act(id, "arrive", "{}", 200);
        assertThat(board().get("tickets")).hasSize(1);
        // Once arrived the slot no longer blocks the calendar.
        book(UUID.randomUUID().toString(), john, ten, "Kamau", 201);

        act(id, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);
        act(id, "done", "{}", 200);
        MvcResult paid = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "pay-" + UUID.randomUUID())
                        .content("{\"payments\":[{\"method\":\"cash\",\"amount\":300}]}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(paid.getResponse().getContentAsString()).get("ticket").get("status").asText())
                .isEqualTo("completed");
    }

    @Test
    void aBookedCustomerCanGoStraightToAChair() throws Exception {
        String id = book(UUID.randomUUID().toString(), john, ten, "Wanjiru", 201).get("id").asText();
        JsonNode started = act(id, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":2}", 200);
        assertThat(started.get("stage").asText()).isEqualTo("IN_SERVICE");
    }

    @Test
    void aBookingCanBeMovedAndANoShowFreesTheSlot() throws Exception {
        String first = book(UUID.randomUUID().toString(), john, ten, "Wanjiru", 201).get("id").asText();
        Instant eleven = ten.plus(1, ChronoUnit.HOURS);
        String second = book(UUID.randomUUID().toString(), john, eleven, "Otieno", 201).get("id").asText();

        // Onto someone else's slot: refused. Onto its own slot or a free one: fine.
        act(second, "reschedule", "{\"start\":\"" + ten + "\"}", 409);
        act(second, "reschedule", "{\"start\":\"" + eleven.plus(5, ChronoUnit.MINUTES) + "\"}", 200);
        Instant twelve = ten.plus(2, ChronoUnit.HOURS);
        JsonNode moved = act(second, "reschedule", "{\"start\":\"" + twelve + "\"}", 200);
        assertThat(Instant.parse(moved.get("scheduledStart").asText())).isEqualTo(twelve);
        act(second, "reschedule", "{\"start\":\"" + Instant.now().minus(1, ChronoUnit.HOURS) + "\"}", 400);

        // To another staff member, who must do the service and be free.
        String peter = createStaff(TENANT, owner, "Peter");
        JsonNode toPeter = act(second, "reschedule",
                "{\"start\":\"" + ten + "\",\"staffProfileId\":\"" + peter + "\"}", 200);
        assertThat(toPeter.get("staffName").asText()).isEqualTo("Peter");
        assertThat(posDraftLineRepository.findByDraftIdOrderByLineIndexAsc(second).getFirst()
                .getPerformerStaffProfileId()).isEqualTo(peter);
        act(second, "reschedule", "{\"start\":\"" + ten + "\",\"staffProfileId\":\"nobody\"}", 404);

        JsonNode missed = act(first, "no-show", "{}", 200);
        assertThat(missed.get("status").asText()).isEqualTo("cancelled");
        assertThat(missed.get("stage").asText()).isEqualTo("NO_SHOW");
        act(first, "no-show", "{}", 200);
        // The slot is free again, and a no-show stays on the calendar as history.
        book(UUID.randomUUID().toString(), john, ten, "Kamau", 201);
        assertThat(calendar(ten.minus(1, ChronoUnit.HOURS), ten.plus(5, ChronoUnit.HOURS))).hasSize(3);
        // A closed booking cannot be moved or arrive.
        act(first, "reschedule", "{\"start\":\"" + twelve + "\"}", 409);
        act(first, "arrive", "{}", 409);

        // Someone who has arrived is not a no-show; a seller may cancel a booking that has not.
        String third = book(UUID.randomUUID().toString(), john, twelve, "Njeri", 201).get("id").asText();
        act(third, "arrive", "{}", 200);
        act(third, "no-show", "{}", 409);
        String fourth = book(UUID.randomUUID().toString(), john, twelve, "Mwangi", 201).get("id").asText();
        act(fourth, "cancel", "{\"reason\":\"Customer called\"}", 200);
    }

    @Test
    void aBookingNeedsARealFutureTimeAStaffMemberWhoServesAndAService() throws Exception {
        book(UUID.randomUUID().toString(), john, Instant.now().minus(1, ChronoUnit.HOURS), "Late", 400);
        book(UUID.randomUUID().toString(), john, Instant.now().plus(800, ChronoUnit.DAYS), "Far", 400);
        book(UUID.randomUUID().toString(), "no-such-staff", ten, "Ghost", 404);
        // Someone who does not do this service cannot be booked for it.
        String peter = createStaff(TENANT, owner, "Peter");
        mockMvc.perform(as(put("/api/v1/services/" + haircut + "/staff"), TENANT, owner, ROLE_OWNER)
                        .content("{\"staffProfileIds\":[\"" + peter + "\"]}"))
                .andExpect(status().isOk());
        book(UUID.randomUUID().toString(), john, ten, "Wanjiru", 400);
        assertThat(posDraftRepository.count()).isZero();
        mockMvc.perform(as(post("/api/v1/service-tickets/appointments"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"x\",\"staffProfileId\":\"" + peter
                                + "\",\"start\":\"" + ten + "\",\"lines\":[],\"name\":\"Nobody\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(get("/api/v1/service-tickets/appointments").param("branchId", branchId)
                        .param("from", ten.toString()).param("to", ten.plus(90, ChronoUnit.DAYS).toString()),
                        TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherBusinessCannotSeeOrTouchBookingsOrStaff() throws Exception {
        String id = book(UUID.randomUUID().toString(), john, ten, "Wanjiru", 201).get("id").asText();
        mockMvc.perform(as(get("/api/v1/service-tickets/appointments").param("branchId", branchId)
                        .param("from", ten.minus(1, ChronoUnit.HOURS).toString())
                        .param("to", ten.plus(1, ChronoUnit.HOURS).toString()), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isNotFound());
        for (String action : new String[] {"arrive", "no-show", "reschedule", "priority"}) {
            String body = switch (action) {
                case "reschedule" -> "{\"start\":\"" + ten.plus(3, ChronoUnit.HOURS) + "\"}";
                case "priority" -> "{\"priority\":1}";
                default -> "{}";
            };
            mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/" + action), OTHER, otherOwner, ROLE_OWNER)
                            .content(body))
                    .andExpect(status().isNotFound());
        }
        // Booking this business's staff, or its branch, from another business.
        mockMvc.perform(as(post("/api/v1/service-tickets/appointments"), OTHER, otherOwner, ROLE_OWNER)
                        .content(bookingBody(UUID.randomUUID().toString(), john, ten, "Spy")))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(as(post("/api/v1/service-tickets/staff/" + john + "/floor-status"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"status\":\"OFF\"}"))
                .andExpect(status().isNotFound());
        assertThat(posDraftRepository.findById(id).orElseThrow().getServiceStage()).isEqualTo("BOOKED");
        assertThat(staffProfileRepository.findById(john).orElseThrow().getFloorStatus()).isNull();
    }

    @Test
    void theBoardShowsWhoCanServeAndRoughlyHowLongEachCustomerWillWait() throws Exception {
        String peter = createStaff(TENANT, owner, "Peter");
        int minutes = serviceRepository.findById(haircut).orElseThrow().getDurationMinutes();
        String first = walkIn();
        String second = walkIn();
        String third = walkIn();
        act(first, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);

        // Two people serving: the haircut in the chair is ahead of the second customer, and the
        // second customer's haircut is ahead of the third as well.
        JsonNode board = board();
        assertThat(board.get("staff")).hasSize(2);
        assertThat(wait(board, first)).isNull();
        assertThat(wait(board, second)).isEqualTo((int) Math.ceil(minutes / 2.0));
        assertThat(wait(board, third)).isEqualTo(minutes);

        // Peter goes on break: one person serving, so everything takes twice as long.
        MvcResult status = mockMvc.perform(as(post("/api/v1/service-tickets/staff/" + peter + "/floor-status"),
                        TENANT, cashier, ROLE_CASHIER).content("{\"status\":\"on_break\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(status.getResponse().getContentAsString()).get("floorStatus").asText())
                .isEqualTo("ON_BREAK");
        mockMvc.perform(as(post("/api/v1/service-tickets/staff/" + peter + "/floor-status"),
                        TENANT, cashier, ROLE_CASHIER).content("{\"status\":\"ASLEEP\"}"))
                .andExpect(status().isBadRequest());
        board = board();
        assertThat(wait(board, second)).isEqualTo(minutes);
        assertThat(wait(board, third)).isEqualTo(minutes * 2);

        // The third customer is moved to the front.
        assertThat(act(third, "priority", "{\"priority\":1}", 200).get("priority").asInt()).isEqualTo(1);
        act(third, "priority", "{\"priority\":10}", 400);
        board = board();
        assertThat(board.get("tickets").get(0).get("id").asText()).isEqualTo(third);
        assertThat(wait(board, third)).isEqualTo(minutes);
        assertThat(wait(board, second)).isEqualTo(minutes * 2);

        // Nobody available: no estimate rather than a wrong one.
        mockMvc.perform(as(post("/api/v1/service-tickets/staff/" + john + "/floor-status"),
                        TENANT, cashier, ROLE_CASHIER).content("{\"status\":\"OFF\"}"))
                .andExpect(status().isOk());
        assertThat(wait(board(), second)).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private String bookingBody(String clientId, String staff, Instant start, String name) {
        return "{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + clientId + "\",\"staffProfileId\":\""
                + staff + "\",\"start\":\"" + start + "\",\"name\":\"" + name
                + "\",\"phone\":\"0700000000\",\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}";
    }

    private JsonNode book(String clientId, String staff, Instant start, String name, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/appointments"), TENANT, cashier, ROLE_CASHIER)
                        .content(bookingBody(clientId, staff, start, name)))
                .andExpect(status().is(expected)).andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : objectMapper.readTree(content);
    }

    private JsonNode calendar(Instant from, Instant to) throws Exception {
        MvcResult result = mockMvc.perform(as(get("/api/v1/service-tickets/appointments").param("branchId", branchId)
                        .param("from", from.toString()).param("to", to.toString()), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String walkIn() throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode act(String id, String action, String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/" + action), TENANT, cashier, ROLE_CASHIER)
                        .content(body))
                .andExpect(status().is(expected)).andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isBlank() || expected >= 400 ? null : objectMapper.readTree(content);
    }

    private JsonNode board() throws Exception {
        MvcResult result = mockMvc.perform(as(get("/api/v1/service-tickets").param("branchId", branchId),
                        TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static Integer wait(JsonNode board, String ticketId) {
        for (JsonNode ticket : board.get("tickets")) {
            if (ticketId.equals(ticket.get("id").asText())) {
                JsonNode wait = ticket.get("estimatedWaitMinutes");
                return wait == null || wait.isNull() ? null : wait.asInt();
            }
        }
        throw new AssertionError("ticket not on the board");
    }
}
