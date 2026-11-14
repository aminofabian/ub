package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.posdraft.domain.PosDraft;

/** The public booking page: no sign-in, so every guard is tested from the outside. */
class OnlineBookingIT extends ServiceSaleTestSupport {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final String ALL_WEEK = "{\"mon\":{\"open\":\"08:00\",\"close\":\"18:00\"},"
            + "\"tue\":{\"open\":\"08:00\",\"close\":\"18:00\"},\"wed\":{\"open\":\"08:00\",\"close\":\"18:00\"},"
            + "\"thu\":{\"open\":\"08:00\",\"close\":\"18:00\"},\"fri\":{\"open\":\"08:00\",\"close\":\"18:00\"},"
            + "\"sat\":{\"open\":\"08:00\",\"close\":\"18:00\"},\"sun\":{\"open\":\"08:00\",\"close\":\"18:00\"}}";

    private final LocalDate tomorrow = LocalDate.now(NAIROBI).plusDays(1);
    private String slug;
    private String base;
    /** Set to make every call come from one address; otherwise each call comes from its own. */
    private String fixedAddress;

    @BeforeEach
    void openForBooking() throws Exception {
        slug = businessRepository.findById(TENANT).orElseThrow().getSlug();
        base = "/api/v1/public/businesses/" + slug + "/booking";
    }

    @Test
    void bookingIsOffUntilAManagerSetsTheHours() throws Exception {
        mockMvc.perform(get(base)).andExpect(status().isNotFound());
        book(at("10:00"), null, "0700990010", UUID.randomUUID().toString(), 404);
        // A seller cannot switch it on.
        mockMvc.perform(as(put("/api/v1/service-booking-hours"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"days\":" + ALL_WEEK + "}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(put("/api/v1/service-booking-hours"), TENANT, owner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"days\":{\"mon\":{\"open\":\"18:00\",\"close\":\"08:00\"}}}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(put("/api/v1/service-booking-hours"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"days\":" + ALL_WEEK + "}"))
                .andExpect(status().isNotFound());
        setHours(ALL_WEEK);

        MvcResult result = mockMvc.perform(get(base)).andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();
        JsonNode info = objectMapper.readTree(body);
        assertThat(info.get("branches")).hasSize(1);
        assertThat(info.get("services").get(0).get("id").asText()).isEqualTo(haircut);
        assertThat(info.get("staff").get(0).get("name").asText()).isEqualTo("John");
        // Nothing a passer-by should not see.
        assertThat(body).doesNotContain("commission").doesNotContain("phone");
        // The other business has not switched it on.
        mockMvc.perform(get("/api/v1/public/businesses/"
                        + businessRepository.findById(OTHER).orElseThrow().getSlug() + "/booking"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/public/businesses/no-such-shop/booking")).andExpect(status().isNotFound());

        // Switched off again.
        setHours("{}");
        mockMvc.perform(get(base)).andExpect(status().isNotFound());
    }

    @Test
    void aCustomerBooksAFreeTimeAndTheShopSeesIt() throws Exception {
        setHours(ALL_WEEK);
        // 08:00 to 18:00 in half hours, each long enough for a 30-minute haircut.
        assertThat(slots(null)).hasSize(20);

        String requestId = UUID.randomUUID().toString();
        JsonNode booked = book(at("10:00"), null, "0700 990 011", requestId, 201);
        assertThat(booked.get("staffName").asText()).isEqualTo("John");
        assertThat(booked.get("serviceName").asText()).isEqualTo("Haircut");
        assertThat(Instant.parse(booked.get("start").asText())).isEqualTo(at("10:00"));

        // Sent again (a double tap, a retry on a bad connection): the same booking.
        assertThat(book(at("10:00"), null, "0700990011", requestId, 201).get("reference").asText())
                .isEqualTo(booked.get("reference").asText());
        assertThat(posDraftRepository.count()).isEqualTo(1);
        PosDraft row = posDraftRepository.findAll().getFirst();
        assertThat(row.getServiceStage()).isEqualTo("BOOKED");
        assertThat(row.getBookingSource()).isEqualTo("ONLINE");
        assertThat(row.getBookingPhone()).isEqualTo("+254700990011");
        // An unverified number is never texted at the shop's cost.
        assertThat(row.isSendReminder()).isFalse();
        assertThat(row.getBookingName()).isEqualTo("Wanjiru Kamau");
        assertThat(row.getCreatedBy()).isEqualTo(owner.getId());

        // The shop sees it on its calendar like any booking, and can mark the arrival.
        MvcResult calendar = mockMvc.perform(as(get("/api/v1/service-tickets/appointments").param("branchId", branchId)
                        .param("from", at("00:00").toString()).param("to", at("23:30").toString()),
                        TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(calendar.getResponse().getContentAsString())).hasSize(1);

        // John is the only one, so that time is gone; someone else cannot take it.
        assertThat(slots(null)).hasSize(19).doesNotContain(at("10:00"));
        book(at("10:00"), null, "0700990012", UUID.randomUUID().toString(), 409);

        // A second staff member frees the time again, and "anyone" goes to whoever is free.
        createStaff(TENANT, owner, "Peter Otieno");
        assertThat(slots(null)).hasSize(20);
        assertThat(slots(john)).hasSize(19);
        assertThat(book(at("10:00"), null, "0700990012", UUID.randomUUID().toString(), 201).get("staffName").asText())
                .isEqualTo("Peter");
        book(at("10:00"), john, "0700990013", UUID.randomUUID().toString(), 409);
    }

    @Test
    void onlyRealFreeTimesInsideOpeningHoursCanBeBooked() throws Exception {
        setHours(ALL_WEEK);
        book(at("10:10"), null, "0700990014", UUID.randomUUID().toString(), 409);
        book(at("07:30"), null, "0700990014", UUID.randomUUID().toString(), 409);
        // Would finish after closing.
        book(at("18:00"), null, "0700990014", UUID.randomUUID().toString(), 409);
        book(Instant.now().minusSeconds(3600), null, "0700990014", UUID.randomUUID().toString(), 409);
        book(at("10:00").plusSeconds(90L * 86400), null, "0700990014", UUID.randomUUID().toString(), 409);
        book(at("10:00"), null, "12345", UUID.randomUUID().toString(), 400);
        // A "name" that is really a link is reduced to its letters, or refused when none are left.
        send(bookingBody(branchId, haircut, null, at("10:00"), "0700990014", UUID.randomUUID().toString())
                .replace("Wanjiru Kamau", "12 34 //"), 400);
        book(at("10:00"), "not-a-staff-member", "0700990014", UUID.randomUUID().toString(), 409);
        // A service or branch of another business is simply not there.
        String theirs = createService(OTHER, otherOwner, "{\"name\":\"Wash\",\"durationMinutes\":30,\"price\":200}");
        send(bookingBody(branchId, theirs, null, at("10:00"), "0700990014", UUID.randomUUID().toString()), 404);
        send(bookingBody(UUID.randomUUID().toString(), haircut, null, at("10:00"), "0700990014",
                UUID.randomUUID().toString()), 404);
        assertThat(posDraftRepository.count()).isZero();
        assertThat(slots(null, tomorrow.minusDays(5))).isEmpty();
        assertThat(slots(null, tomorrow.plusDays(200))).isEmpty();
    }

    @Test
    void oneNumberCannotFillTheBookAndOneAddressCannotFloodIt() throws Exception {
        setHours(ALL_WEEK);
        fixedAddress = "10.200.1.7";
        book(at("09:00"), null, "0700990015", UUID.randomUUID().toString(), 201);
        book(at("11:00"), null, "0700990015", UUID.randomUUID().toString(), 201);
        book(at("13:00"), null, "0700990015", UUID.randomUUID().toString(), 201);
        book(at("15:00"), null, "0700990015", UUID.randomUUID().toString(), 409);
        assertThat(posDraftRepository.count()).isEqualTo(3);
        // Six attempts a minute from one address; the next is turned away before anything is read.
        book(at("16:00"), null, "0700990016", UUID.randomUUID().toString(), 201);
        book(at("16:30"), null, "0700990017", UUID.randomUUID().toString(), 201);
        book(at("17:00"), null, "0700990018", UUID.randomUUID().toString(), 429);
        assertThat(posDraftRepository.count()).isEqualTo(5);
    }

    @Test
    void theCalendarCountsFreeTimesDayByDay() throws Exception {
        setHours(ALL_WEEK);
        LocalDate today = LocalDate.now(NAIROBI);
        // A week starting yesterday: the past is closed, tomorrow has the whole day free.
        JsonNode week = calendar(today.minusDays(1), 7, null, 200).get("days");
        assertThat(week).hasSize(7);
        assertThat(week.get(0).get("open").asBoolean()).isFalse();
        assertThat(week.get(2).get("date").asText()).isEqualTo(tomorrow.toString());
        assertThat(week.get(2).get("open").asBoolean()).isTrue();
        assertThat(week.get(2).get("free").asInt()).isEqualTo(20);
        // The count is exactly what the day's own list of times says.
        assertThat(week.get(3).get("free").asInt()).isEqualTo(slots(null, tomorrow.plusDays(1)).size());

        // A booking takes one time off that day and no other.
        book(at("10:00"), null, "0700990031", UUID.randomUUID().toString(), 201);
        week = calendar(today.minusDays(1), 7, null, 200).get("days");
        assertThat(week.get(2).get("free").asInt()).isEqualTo(19);
        assertThat(week.get(3).get("free").asInt()).isEqualTo(20);
        assertThat(calendar(tomorrow, 1, john, 200).get("days").get(0).get("free").asInt()).isEqualTo(19);

        // A weekday the shop does not take bookings on is closed; beyond the horizon too.
        String key = tomorrow.getDayOfWeek().name().substring(0, 3).toLowerCase(java.util.Locale.ROOT);
        String other = key.equals("mon") ? "tue" : "mon";
        setHours("{\"" + other + "\":{\"open\":\"08:00\",\"close\":\"18:00\"}}");
        assertThat(calendar(tomorrow, 1, null, 200).get("days").get(0).get("open").asBoolean()).isFalse();
        setHours(ALL_WEEK);
        assertThat(calendar(today.plusDays(200), 3, null, 200).get("days").get(0).get("open").asBoolean()).isFalse();

        // At most six weeks at a time; an unknown staff member has no times; other shops stay shut.
        calendar(today, 43, null, 400);
        assertThat(calendar(tomorrow, 1, "no-such-person", 200).get("days").get(0).get("free").asInt()).isZero();
        mockMvc.perform(get("/api/v1/public/businesses/"
                        + businessRepository.findById(OTHER).orElseThrow().getSlug() + "/booking/days")
                        .param("branchId", branchId).param("serviceId", haircut).param("from", tomorrow.toString()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode calendar(LocalDate from, int days, String staffId, int expected) throws Exception {
        var request = get(base + "/days").param("branchId", branchId).param("serviceId", haircut)
                .param("from", from.toString()).param("days", String.valueOf(days));
        if (staffId != null) {
            request = request.param("staffId", staffId);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().is(expected)).andReturn();
        return expected == 200 ? objectMapper.readTree(result.getResponse().getContentAsString()) : null;
    }

    private Instant at(String time) {
        return tomorrow.atTime(LocalTime.parse(time)).atZone(NAIROBI).toInstant();
    }

    private void setHours(String days) throws Exception {
        mockMvc.perform(as(put("/api/v1/service-booking-hours"), TENANT, owner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"days\":" + days + "}"))
                .andExpect(status().isOk());
    }

    private java.util.List<Instant> slots(String staffId) throws Exception {
        return slots(staffId, tomorrow);
    }

    private java.util.List<Instant> slots(String staffId, LocalDate date) throws Exception {
        var request = get(base + "/slots").param("branchId", branchId).param("serviceId", haircut)
                .param("date", date.toString());
        if (staffId != null) {
            request = request.param("staffId", staffId);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        java.util.List<Instant> out = new java.util.ArrayList<>();
        for (JsonNode start : objectMapper.readTree(result.getResponse().getContentAsString()).get("starts")) {
            out.add(Instant.parse(start.asText()));
        }
        return out;
    }

    private String bookingBody(String branch, String service, String staffId, Instant start, String phone, String requestId) {
        return "{\"branchId\":\"" + branch + "\",\"serviceId\":\"" + service + "\","
                + (staffId == null ? "" : "\"staffId\":\"" + staffId + "\",")
                + "\"start\":\"" + start + "\",\"name\":\"Wanjiru Kamau\",\"phone\":\"" + phone
                + "\",\"requestId\":\"" + requestId + "\"}";
    }

    private JsonNode book(Instant start, String staffId, String phone, String requestId, int expected) throws Exception {
        return send(bookingBody(branchId, haircut, staffId, start, phone, requestId), expected);
    }

    private JsonNode send(String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(post(base + "/appointments").contentType(APPLICATION_JSON)
                        .header("X-Forwarded-For", fixedAddress != null ? fixedAddress : "10.9." + (int) (Math.random() * 250) + "."
                                + (int) (Math.random() * 250) + ", 172.16.0.1")
                        .content(body))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
