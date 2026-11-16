package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.identity.domain.User;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.serviceops.application.StaffEarningsService;

/**
 * What a customer chooses on a service (ADR 0025). Braids cost 1,000 and take 120 minutes; long
 * hair adds 500 and an hour, extra packs add 300. John braids at 50%.
 */
class ServiceOptionsIT extends ServiceSaleTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    private StaffEarningsService staffEarningsService;

    private String braids;
    private String shortHair;
    private String longHair;
    private String packs;
    private String wash;

    @BeforeEach
    void braidsWithALengthAndExtras() throws Exception {
        braids = createService(TENANT, owner, "{\"name\":\"Braids\",\"durationMinutes\":120,\"price\":1000}");
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + john), TENANT, owner, ROLE_OWNER)
                        .content("{\"type\":\"PERCENT\",\"rate\":50}"))
                .andExpect(status().isOk());
        JsonNode saved = setOptions(braids, owner, ROLE_OWNER, TENANT, lengthAndExtras(), 200);
        JsonNode length = saved.get("options").get(0);
        assertThat(length.get("required").asBoolean()).isTrue();
        assertThat(length.get("multiple").asBoolean()).isFalse();
        shortHair = length.get("options").get(0).get("id").asText();
        longHair = length.get("options").get(1).get("id").asText();
        JsonNode extras = saved.get("options").get(1);
        packs = extras.get("options").get(0).get("id").asText();
        wash = extras.get("options").get(1).get("id").asText();
        assertThat(java.util.Set.of(shortHair, longHair, packs, wash)).hasSize(4);
    }

    @Test
    void theChosenLengthAndExtrasSetThePriceTheTimeTheReceiptAndTheCommission() throws Exception {
        JsonNode visit = open("{\"serviceId\":\"" + braids + "\"}", 201);
        String id = visit.get("id").asText();
        String lineId = visit.get("lines").get(0).get("id").asText();
        assertThat(visit.get("total").decimalValue()).isEqualByComparingTo("1000.00");
        assign(id, lineId);

        // The length must be chosen before money is taken.
        pay(id, 1000, 409);

        JsonNode chosen = options(id, lineId, cashier, ROLE_CASHIER, TENANT, "[\"" + longHair + "\",\"" + packs + "\"]", 200);
        JsonNode line = chosen.get("lines").get(0);
        assertThat(line.get("unitPrice").decimalValue()).isEqualByComparingTo("1800.00");
        assertThat(line.get("plannedMinutes").asInt()).isEqualTo(180);
        assertThat(line.get("options")).hasSize(2);
        assertThat(line.get("options").get(0).get("group").asText()).isEqualTo("Hair length");
        assertThat(line.get("options").get(0).get("name").asText()).isEqualTo("Long");
        assertThat(chosen.get("total").decimalValue()).isEqualByComparingTo("1800.00");

        // Changing the choice replaces it; it does not stack.
        JsonNode again = options(id, lineId, cashier, ROLE_CASHIER, TENANT, "[\"" + longHair + "\"]", 200);
        assertThat(again.get("total").decimalValue()).isEqualByComparingTo("1500.00");
        options(id, lineId, cashier, ROLE_CASHIER, TENANT, "[\"" + longHair + "\",\"" + packs + "\"]", 200);

        JsonNode paid = pay(id, 1800, 200);
        String saleId = paid.get("sale").get("id").asText();
        assertThat(paid.get("sale").get("grandTotal").decimalValue()).isEqualByComparingTo("1800.00");
        SaleItem sold = saleItemRepository.findBySaleIdOrderByLineIndexAsc(saleId).get(0);
        // The receipt names what was chosen; the line's own name stays the service, for the reports.
        assertThat(sold.getLineLabel()).isEqualTo("Braids (Long, Extra packs)");
        assertThat(sold.getDisplayName()).isEqualTo("Braids");
        assertThat(sold.getServiceMinutes()).isEqualTo(180);
        assertThat(sold.getModifiers()).contains("Extra packs");
        // Commission is on what the customer paid, options included.
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("900.00");
        assertThat(accountBalance("4010")).isEqualByComparingTo("1800.00");
        assertBalanced(journalLineRepository.findAll());
    }

    @Test
    void optionsCanBeChosenWhenTheVisitIsOpenedAndBadChoicesAreRefused() throws Exception {
        JsonNode visit = open("{\"serviceId\":\"" + braids + "\",\"optionIds\":[\"" + shortHair + "\",\"" + packs + "\",\"" + wash + "\"]}", 201);
        assertThat(visit.get("total").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(visit.get("lines").get(0).get("plannedMinutes").asInt()).isEqualTo(135);
        // Two lengths at once, and an option that belongs to no service here.
        open("{\"serviceId\":\"" + braids + "\",\"optionIds\":[\"" + shortHair + "\",\"" + longHair + "\"]}", 400);
        open("{\"serviceId\":\"" + braids + "\",\"optionIds\":[\"nosuch\"]}", 400);
        open("{\"serviceId\":\"" + haircut + "\",\"optionIds\":[\"" + longHair + "\"]}", 400);
        String id = visit.get("id").asText();
        String lineId = visit.get("lines").get(0).get("id").asText();
        options(id, lineId, cashier, ROLE_CASHIER, TENANT, "[\"" + shortHair + "\",\"" + longHair + "\"]", 400);
        assertThat(visit(id).get("total").decimalValue()).isEqualByComparingTo("1500.00");
        // Clearing the choice takes the service back to its own price.
        assertThat(options(id, lineId, cashier, ROLE_CASHIER, TENANT, "[]", 200).get("total").decimalValue())
                .isEqualByComparingTo("1000.00");
    }

    @Test
    void aChangedOrRemovedOptionIsRepricedBeforeMoneyIsTaken() throws Exception {
        JsonNode visit = open("{\"serviceId\":\"" + braids + "\",\"optionIds\":[\"" + longHair + "\",\"" + packs + "\"]}", 201);
        String id = visit.get("id").asText();
        assign(id, visit.get("lines").get(0).get("id").asText());
        // The owner puts long hair up to 700 and stops offering extra packs. Ids sent back are kept.
        JsonNode saved = setOptions(braids, owner, ROLE_OWNER, TENANT, "{\"groups\":[{\"id\":\"" + groupId(0)
                + "\",\"name\":\"Hair length\",\"required\":true,\"options\":["
                + "{\"id\":\"" + shortHair + "\",\"name\":\"Short\"},"
                + "{\"id\":\"" + longHair + "\",\"name\":\"Long\",\"priceDelta\":700,\"extraMinutes\":60}]}]}", 200);
        assertThat(saved.get("options").get(0).get("options").get(1).get("id").asText()).isEqualTo(longHair);

        // The first try is refused with the new total, and nothing is rung.
        pay(id, 1800, 409);
        assertThat(saleRepository.count()).isZero();
        JsonNode now = visit(id);
        assertThat(now.get("total").decimalValue()).isEqualByComparingTo("1700.00");
        assertThat(now.get("lines").get(0).get("options")).hasSize(1);
        pay(id, 1700, 200);
    }

    @Test
    void aSaleCannotClaimOptionsThePriceDoesNotMatch() throws Exception {
        // Rung straight at the till: the listed price with long hair is 1,500, not 1,000.
        postSale("opt-" + UUID.randomUUID(), serviceLineWithOptions("1000", longHair), "1000", 400);
        postSale("opt-" + UUID.randomUUID(), serviceLineWithOptions("1500", "nosuch"), "1500", 400);
        JsonNode sale = postSale("opt-" + UUID.randomUUID(), serviceLineWithOptions("1500", longHair), "1500", 201);
        assertThat(saleItemRepository.findBySaleIdOrderByLineIndexAsc(sale.get("id").asText()).get(0).getLineLabel())
                .isEqualTo("Braids (Long)");
    }

    @Test
    void aBookingIsAsLongAsWhatWasChosenAndCannotGrowIntoTheNextOne() throws Exception {
        Instant ten = Instant.now().atZone(ZoneId.of("Africa/Nairobi")).plusDays(3).withHour(10).withMinute(0)
                .withSecond(0).withNano(0).toInstant();
        JsonNode first = book(ten, "{\"serviceId\":\"" + braids + "\",\"optionIds\":[\"" + shortHair + "\"]}", "Wanjiru", 201);
        assertThat(Instant.parse(first.get("scheduledEnd").asText())).isEqualTo(ten.plus(120, ChronoUnit.MINUTES));
        // Someone else is booked with John straight after.
        book(ten.plus(120, ChronoUnit.MINUTES), "{\"serviceId\":\"" + haircut + "\"}", "Achieng", 201);
        // Long hair would run an hour into that booking.
        String id = first.get("id").asText();
        String lineId = first.get("lines").get(0).get("id").asText();
        options(id, lineId, cashier, ROLE_CASHIER, TENANT, "[\"" + longHair + "\"]", 409);
        assertThat(Instant.parse(visit(id).get("scheduledEnd").asText())).isEqualTo(ten.plus(120, ChronoUnit.MINUTES));
        // Extra packs add no time, so that is fine.
        JsonNode withPacks = options(id, lineId, cashier, ROLE_CASHIER, TENANT, "[\"" + shortHair + "\",\"" + packs + "\"]", 200);
        assertThat(withPacks.get("total").decimalValue()).isEqualByComparingTo("1300.00");
        // The owner types what John gets for it: 1,200 of the 1,300. Dropping the packs would leave
        // the service costing less than that, so it is refused until the amount is changed.
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/lines/" + lineId + "/team"), TENANT, owner, ROLE_OWNER)
                        .content("{\"members\":[{\"staffProfileId\":\"" + john + "\",\"amount\":1200}]}"))
                .andExpect(status().isOk());
        options(id, lineId, cashier, ROLE_CASHIER, TENANT, "[\"" + shortHair + "\"]", 409);
        assertThat(visit(id).get("total").decimalValue()).isEqualByComparingTo("1300.00");
    }

    @Test
    void onlyAManagerSetsOptionsAndAnotherBusinessCanDoNothing() throws Exception {
        setOptions(braids, cashier, ROLE_CASHIER, TENANT, lengthAndExtras(), 403);
        setOptions(braids, otherOwner, ROLE_OWNER, OTHER, "{\"groups\":[]}", 404);
        // The same name twice, a group with nothing in it, a negative extra.
        setOptions(braids, owner, ROLE_OWNER, TENANT,
                "{\"groups\":[{\"name\":\"Length\",\"options\":[{\"name\":\"Long\"},{\"name\":\"long\"}]}]}", 400);
        setOptions(braids, owner, ROLE_OWNER, TENANT, "{\"groups\":[{\"name\":\"Length\",\"options\":[]}]}", 400);
        setOptions(braids, owner, ROLE_OWNER, TENANT,
                "{\"groups\":[{\"name\":\"Length\",\"options\":[{\"name\":\"Long\",\"priceDelta\":-5}]}]}", 400);

        JsonNode visit = open("{\"serviceId\":\"" + braids + "\"}", 201);
        String id = visit.get("id").asText();
        String lineId = visit.get("lines").get(0).get("id").asText();
        options(id, lineId, otherOwner, ROLE_OWNER, OTHER, "[\"" + longHair + "\"]", 404);
        assertThat(visit(id).get("total").decimalValue()).isEqualByComparingTo("1000.00");
        // The other business has a service of its own; this business's options are not its options.
        String theirs = createService(OTHER, otherOwner, "{\"name\":\"Braids\",\"durationMinutes\":120,\"price\":1000}");
        assertThat(serviceRepository.findById(theirs).orElseThrow().getOptionsJson()).isNull();
        // Emptying the list makes it a plain service again.
        assertThat(setOptions(braids, owner, ROLE_OWNER, TENANT, "{\"groups\":[]}", 200).get("options")).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private String lengthAndExtras() {
        return "{\"groups\":["
                + "{\"name\":\"Hair length\",\"required\":true,\"options\":["
                + "{\"name\":\"Short\"},{\"name\":\"Long\",\"priceDelta\":500,\"extraMinutes\":60}]},"
                + "{\"name\":\"Extras\",\"multiple\":true,\"options\":["
                + "{\"name\":\"Extra packs\",\"priceDelta\":300},{\"name\":\"Wash first\",\"priceDelta\":200,\"extraMinutes\":15}]}]}";
    }

    private String groupId(int index) throws Exception {
        MvcResult result = mockMvc.perform(as(get("/api/v1/services/" + braids), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("options").get(index).get("id").asText();
    }

    private JsonNode setOptions(String serviceId, User user, String role, String tenant, String body, int expected)
            throws Exception {
        MvcResult result = mockMvc.perform(as(put("/api/v1/services/" + serviceId + "/options"), tenant, user, role).content(body))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode open(String line, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"lines\":[" + line + "]}"))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode visit(String id) throws Exception {
        return objectMapper.readTree(mockMvc.perform(as(get("/api/v1/service-tickets/" + id), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void assign(String id, String lineId) throws Exception {
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/lines/" + lineId + "/assign"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"performerStaffProfileId\":\"" + john + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode options(String id, String lineId, User user, String role, String tenant, String ids, int expected)
            throws Exception {
        MvcResult result = mockMvc.perform(as(put("/api/v1/service-tickets/" + id + "/lines/" + lineId + "/options"),
                        tenant, user, role).content("{\"optionIds\":" + ids + "}"))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode pay(String id, int cash, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "pay-" + UUID.randomUUID())
                        .content("{\"payments\":[{\"method\":\"cash\",\"amount\":" + cash + "}]}"))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode book(Instant start, String line, String name, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/appointments"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"staffProfileId\":\"" + john + "\",\"start\":\"" + start + "\",\"lines\":[" + line
                                + "],\"name\":\"" + name + "\"}"))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String serviceLineWithOptions(String price, String optionId) {
        return "{\"kind\":\"SERVICE\",\"serviceId\":\"" + braids + "\",\"performerStaffProfileId\":\"" + john
                + "\",\"quantity\":1,\"unitPrice\":" + price + ",\"modifierOptionIds\":[\"" + optionId + "\"]}";
    }
}
