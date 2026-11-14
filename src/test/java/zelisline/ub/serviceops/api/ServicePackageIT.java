package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.identity.domain.User;
import zelisline.ub.serviceops.domain.CustomerServicePackage;
import zelisline.ub.serviceops.repository.CustomerServicePackageRepository;

/** Prepaid packages: money held as deferred revenue and recognised one visit at a time (ADR 0015). */
class ServicePackageIT extends ServiceSaleTestSupport {

    private static final String DEFERRED = "2330";
    private static final String SERVICE_REVENUE = "4010";

    @Autowired
    private CustomerServicePackageRepository packageRepository;

    private String customer;

    @BeforeEach
    void aCustomerAndChairs() throws Exception {
        mockMvc.perform(as(put("/api/v1/service-stations"), TENANT, owner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"count\":3}"))
                .andExpect(status().isOk());
        customer = seedCustomer("Wanjiru", "0700990007");
    }

    @Test
    void anOfferIsSetByAManagerAndSoldOnceHoweverOftenTheTillRetries() throws Exception {
        // A seller cannot change what is offered.
        mockMvc.perform(as(put("/api/v1/service-packages/offers/" + haircut), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"sessions\":5,\"price\":1000}"))
                .andExpect(status().isForbidden());
        sell(TENANT, cashier, ROLE_CASHIER, customer, "k1", 400); // nothing offered yet
        offer("{\"sessions\":5,\"price\":1000,\"validDays\":90}");
        mockMvc.perform(as(put("/api/v1/service-packages/offers/" + haircut), TENANT, owner, ROLE_OWNER)
                        .content("{\"sessions\":1,\"price\":1000}"))
                .andExpect(status().isBadRequest());
        MvcResult offers = mockMvc.perform(as(get("/api/v1/service-packages/offers"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn();
        JsonNode offer = objectMapper.readTree(offers.getResponse().getContentAsString()).get(0);
        assertThat(offer.get("sessions").asInt()).isEqualTo(5);
        assertThat(offer.get("singlePrice").decimalValue()).isEqualByComparingTo("300.00");

        JsonNode sold = sell(TENANT, cashier, ROLE_CASHIER, customer, "k2", 201);
        assertThat(sold.get("sessionsRemaining").asInt()).isEqualTo(5);
        assertThat(sold.get("expiresAt").isNull()).isFalse();
        // The same request again is the same package: one payment, one liability.
        assertThat(sell(TENANT, cashier, ROLE_CASHIER, customer, "k2", 201).get("id").asText())
                .isEqualTo(sold.get("id").asText());
        assertThat(packageRepository.count()).isEqualTo(1);
        // Held, not earned; and the cash is in the drawer (100 float + 1000).
        assertThat(accountBalance(DEFERRED)).isEqualByComparingTo("1000.00");
        assertThat(accountBalance(SERVICE_REVENUE)).isEqualByComparingTo("0.00");
        assertThat(shiftRepository.findAll().getFirst().getExpectedClosingCash()).isEqualByComparingTo("1100.00");

        // No key, no sale.
        mockMvc.perform(as(post("/api/v1/service-packages/sell"), TENANT, cashier, ROLE_CASHIER)
                        .content(sellBody(customer)))
                .andExpect(status().isBadRequest());
        // Another business cannot sell to this customer, or see their packages.
        sell(OTHER, otherOwner, ROLE_OWNER, customer, "k3", 400);
        MvcResult theirs = mockMvc.perform(as(get("/api/v1/service-packages").param("customerId", customer),
                        OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(theirs.getResponse().getContentAsString())).isEmpty();
        assertThat(packageRepository.count()).isEqualTo(1);

        // Taking the offer away leaves the customer's package alone.
        offer("{}");
        MvcResult mine = mockMvc.perform(as(get("/api/v1/service-packages").param("customerId", customer),
                        TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(mine.getResponse().getContentAsString())).hasSize(1);
    }

    @Test
    void eachVisitUsesOneSessionAndTheLiabilityEndsAtExactlyZero() throws Exception {
        // Three haircuts for 1000: 333.33 a visit, with the odd cent on the last.
        offer("{\"sessions\":3,\"price\":1000}");
        String pkg = sell(TENANT, cashier, ROLE_CASHIER, customer, "k-" + UUID.randomUUID(), 201).get("id").asText();

        visit(customer, pkg, "[]", 200);
        assertThat(packageRepository.findById(pkg).orElseThrow().getSessionsRemaining()).isEqualTo(2);
        assertThat(accountBalance(DEFERRED)).isEqualByComparingTo("666.67");
        assertThat(accountBalance(SERVICE_REVENUE)).isEqualByComparingTo("300.00");
        // The session was worth more than today's haircut: the rest is the customer's credit.
        assertThat(wallet()).isEqualByComparingTo("33.33");

        visit(customer, pkg, "[]", 200);
        visit(customer, pkg, "[]", 200);
        CustomerServicePackage used = packageRepository.findById(pkg).orElseThrow();
        assertThat(used.getSessionsRemaining()).isZero();
        assertThat(used.getValueRemaining()).isEqualByComparingTo("0.00");
        assertThat(accountBalance(DEFERRED)).isEqualByComparingTo("0.00");
        assertThat(accountBalance(SERVICE_REVENUE)).isEqualByComparingTo("900.00");
        assertThat(wallet()).isEqualByComparingTo("100.00");
        assertBalanced(journalLineRepository.findAll());

        // Nothing left to use.
        visit(customer, pkg, "[{\"method\":\"cash\",\"amount\":300}]", 409);
        assertThat(saleRepository.count()).isEqualTo(3);
    }

    @Test
    void aSessionThatDoesNotCoverTheVisitIsToppedUpAndAFailedPaymentGivesItBack() throws Exception {
        offer("{\"sessions\":5,\"price\":1000}");
        String pkg = sell(TENANT, cashier, ROLE_CASHIER, customer, "k-" + UUID.randomUUID(), 201).get("id").asText();

        // 200 from the session is not enough on its own, and 50 more still falls short.
        visit(customer, pkg, "[]", 400);
        visit(customer, pkg, "[{\"method\":\"cash\",\"amount\":50}]", 400);
        assertThat(packageRepository.findById(pkg).orElseThrow().getSessionsRemaining()).isEqualTo(5);
        assertThat(accountBalance(DEFERRED)).isEqualByComparingTo("1000.00");
        assertThat(wallet()).isEqualByComparingTo("0.00");

        visit(customer, pkg, "[{\"method\":\"cash\",\"amount\":100}]", 200);
        assertThat(packageRepository.findById(pkg).orElseThrow().getSessionsRemaining()).isEqualTo(4);
        assertThat(accountBalance(DEFERRED)).isEqualByComparingTo("800.00");
        assertThat(accountBalance(SERVICE_REVENUE)).isEqualByComparingTo("300.00");
        assertThat(wallet()).isEqualByComparingTo("0.00");
    }

    @Test
    void aPackageIsOnlyForItsOwnerItsServiceAndItsTime() throws Exception {
        offer("{\"sessions\":5,\"price\":1000}");
        String pkg = sell(TENANT, cashier, ROLE_CASHIER, customer, "k-" + UUID.randomUUID(), 201).get("id").asText();

        // Someone else's ticket, and a ticket with no customer.
        String other = seedCustomer("Otieno", "0700990008");
        visit(other, pkg, "[{\"method\":\"cash\",\"amount\":300}]", 400);
        visit(null, pkg, "[{\"method\":\"cash\",\"amount\":300}]", 400);
        visit(customer, UUID.randomUUID().toString(), "[{\"method\":\"cash\",\"amount\":300}]", 404);

        // A visit for a different service.
        String shave = createService(TENANT, owner, "{\"name\":\"Shave\",\"durationMinutes\":15,\"price\":250}");
        String ticket = ticket(customer, shave);
        act(ticket, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);
        act(ticket, "done", "{}", 200);
        pay(ticket, pkg, "[{\"method\":\"cash\",\"amount\":250}]", 400);

        // Out of time.
        CustomerServicePackage row = packageRepository.findById(pkg).orElseThrow();
        row.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        packageRepository.save(row);
        visit(customer, pkg, "[{\"method\":\"cash\",\"amount\":100}]", 409);

        assertThat(packageRepository.findById(pkg).orElseThrow().getSessionsRemaining()).isEqualTo(5);
        assertThat(accountBalance(DEFERRED)).isEqualByComparingTo("1000.00");
        assertThat(saleRepository.count()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private BigDecimal wallet() {
        return creditAccountRepository.findByCustomerIdAndBusinessId(customer, TENANT).orElseThrow().getWalletBalance();
    }

    private void offer(String body) throws Exception {
        mockMvc.perform(as(put("/api/v1/service-packages/offers/" + haircut), TENANT, owner, ROLE_OWNER).content(body))
                .andExpect(status().isOk());
    }

    private String sellBody(String customerId) {
        return "{\"branchId\":\"" + branchId + "\",\"customerId\":\"" + customerId + "\",\"serviceId\":\"" + haircut
                + "\",\"method\":\"cash\"}";
    }

    private JsonNode sell(String tenant, User user, String role, String customerId, String key, int expected)
            throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-packages/sell"), tenant, user, role)
                        .header("Idempotency-Key", key)
                        .content(sellBody(customerId)))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** A whole visit for a haircut, paid with a session of the package plus whatever else. */
    private void visit(String customerId, String packageId, String payments, int expected) throws Exception {
        String id = ticket(customerId, haircut);
        act(id, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);
        act(id, "done", "{}", 200);
        pay(id, packageId, payments, expected);
        if (expected >= 400) {
            // Leave the chair free for the next attempt.
            mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/cancel"), TENANT, owner, ROLE_OWNER)
                            .content("{\"reason\":\"test\"}"))
                    .andExpect(status().isOk());
        }
    }

    private String ticket(String customerId, String serviceId) throws Exception {
        String who = customerId == null ? "" : "\"customerId\":\"" + customerId + "\",";
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID() + "\","
                                + who + "\"lines\":[{\"serviceId\":\"" + serviceId + "\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void act(String id, String action, String body, int expected) throws Exception {
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/" + action), TENANT, cashier, ROLE_CASHIER)
                        .content(body))
                .andExpect(status().is(expected));
    }

    private void pay(String id, String packageId, String payments, int expected) throws Exception {
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "pay-" + UUID.randomUUID())
                        .content("{\"packageId\":\"" + packageId + "\",\"payments\":" + payments + "}"))
                .andDo(r -> {
                    if (r.getResponse().getStatus() != expected) {
                        System.out.println("DBG pay -> " + r.getResponse().getContentAsString());
                    }
                })
                .andExpect(status().is(expected));
    }
}
