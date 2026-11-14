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
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.identity.domain.User;
import zelisline.ub.sales.domain.SalePayment;

/** Booking deposits (ADR 0015), free redo tickets and the service contribution report. */
class ServiceDepositAndRedoIT extends ServiceSaleTestSupport {

    private static final String DEPOSITS = "2320";
    private static final String SERVICE_REVENUE = "4010";

    private final Instant ten = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
    private String customer;

    @BeforeEach
    void chairsAndACustomer() throws Exception {
        mockMvc.perform(as(put("/api/v1/service-stations"), TENANT, owner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"count\":3}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + john), TENANT, owner, ROLE_OWNER)
                        .content("{\"type\":\"PERCENT\",\"rate\":40}"))
                .andExpect(status().isOk());
        customer = seedCustomer("Wanjiru", "0700990002");
    }

    @Test
    void aCashDepositIsHeldThenSpentOnTheVisit() throws Exception {
        String id = book(customer);
        JsonNode held = act(id, "deposit", "{\"amount\":100,\"method\":\"cash\"}", 200);
        assertThat(held.get("depositState").asText()).isEqualTo("HELD");
        assertThat(held.get("depositAmount").decimalValue()).isEqualByComparingTo("100.00");
        // Held for the customer, not income, and not yet theirs to spend elsewhere.
        assertThat(accountBalance(DEPOSITS)).isEqualByComparingTo("100.00");
        assertThat(accountBalance(SERVICE_REVENUE)).isEqualByComparingTo("0.00");
        assertThat(wallet()).isEqualByComparingTo("0.00");
        // The cash is in the drawer: 100 float + 100 deposit.
        assertThat(shiftRepository.findAll().getFirst().getExpectedClosingCash()).isEqualByComparingTo("200.00");

        // A double tap changes nothing; a second, different deposit is refused.
        act(id, "deposit", "{\"amount\":100,\"method\":\"cash\"}", 200);
        act(id, "deposit", "{\"amount\":50,\"method\":\"cash\"}", 409);
        assertThat(accountBalance(DEPOSITS)).isEqualByComparingTo("100.00");
        assertThat(shiftRepository.findAll().getFirst().getExpectedClosingCash()).isEqualByComparingTo("200.00");

        act(id, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);
        act(id, "done", "{}", 200);
        // The customer hands over the other 200.
        JsonNode paid = pay(id, "[{\"method\":\"cash\",\"amount\":200}]", 200);
        assertThat(paid.get("ticket").get("depositState").asText()).isEqualTo("APPLIED");
        String saleId = paid.get("sale").get("id").asText();
        assertThat(paid.get("sale").get("grandTotal").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(salePaymentRepository.findBySaleIdOrderBySortOrderAsc(saleId))
                .extracting(SalePayment::getMethod)
                .containsExactlyInAnyOrder("customer_wallet", "cash");

        // The deposit has gone: nothing held, nothing left in the wallet, all of it now income.
        assertThat(accountBalance(DEPOSITS)).isEqualByComparingTo("0.00");
        assertThat(wallet()).isEqualByComparingTo("0.00");
        assertThat(accountBalance(SERVICE_REVENUE)).isEqualByComparingTo("300.00");
        assertThat(shiftRepository.findAll().getFirst().getExpectedClosingCash()).isEqualByComparingTo("400.00");
        assertBalanced(journalLineRepository.findAll());
    }

    @Test
    void aDepositLargerThanTheBillLeavesTheRestInTheWallet() throws Exception {
        String id = book(customer);
        // M-Pesa never touches the drawer.
        act(id, "deposit", "{\"amount\":500,\"method\":\"mpesa_manual\",\"reference\":\"QWE123\"}", 200);
        assertThat(shiftRepository.findAll().getFirst().getExpectedClosingCash()).isEqualByComparingTo("100.00");
        act(id, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);
        act(id, "done", "{}", 200);
        // Nothing more to hand over.
        JsonNode paid = pay(id, "[]", 200);
        assertThat(paid.get("ticket").get("status").asText()).isEqualTo("completed");
        assertThat(accountBalance(DEPOSITS)).isEqualByComparingTo("0.00");
        assertThat(wallet()).isEqualByComparingTo("200.00");
        assertThat(accountBalance(SERVICE_REVENUE)).isEqualByComparingTo("300.00");
        assertBalanced(journalLineRepository.findAll());
    }

    @Test
    void aNoShowForfeitsTheDepositUnlessTheShopKeepsItAsCreditAndACancelReturnsIt() throws Exception {
        String forfeited = book(customer);
        act(forfeited, "deposit", "{\"amount\":100,\"method\":\"cash\"}", 200);
        JsonNode missed = act(forfeited, "no-show", "{}", 200);
        assertThat(missed.get("depositState").asText()).isEqualTo("FORFEITED");
        assertThat(accountBalance(DEPOSITS)).isEqualByComparingTo("0.00");
        assertThat(accountBalance(SERVICE_REVENUE)).isEqualByComparingTo("100.00");
        assertThat(wallet()).isEqualByComparingTo("0.00");
        // Again: nothing more is posted.
        act(forfeited, "no-show", "{}", 200);
        assertThat(accountBalance(SERVICE_REVENUE)).isEqualByComparingTo("100.00");

        String kept = book(customer);
        act(kept, "deposit", "{\"amount\":150,\"method\":\"cash\"}", 200);
        assertThat(act(kept, "no-show", "{\"keepDepositAsCredit\":true}", 200).get("depositState").asText())
                .isEqualTo("CREDITED");
        assertThat(wallet()).isEqualByComparingTo("150.00");

        String cancelled = book(customer);
        act(cancelled, "deposit", "{\"amount\":50,\"method\":\"cash\"}", 200);
        assertThat(act(cancelled, "cancel", "{\"reason\":\"Customer called\"}", 200).get("depositState").asText())
                .isEqualTo("CREDITED");
        act(cancelled, "cancel", "{\"reason\":\"Customer called\"}", 200);
        assertThat(wallet()).isEqualByComparingTo("200.00");
        assertThat(accountBalance(DEPOSITS)).isEqualByComparingTo("0.00");
        assertThat(accountBalance(SERVICE_REVENUE)).isEqualByComparingTo("100.00");
        assertBalanced(journalLineRepository.findAll());
    }

    @Test
    void aDepositNeedsACustomerARealAmountAndTheRightBusiness() throws Exception {
        String anonymous = book(null);
        act(anonymous, "deposit", "{\"amount\":100,\"method\":\"cash\"}", 400);
        // A ticket cannot name another business's customer, so no money can be held for one.
        zelisline.ub.credits.domain.Customer theirs = new zelisline.ub.credits.domain.Customer();
        theirs.setBusinessId(OTHER);
        theirs.setName("Outsider");
        customerRepository.save(theirs);
        createTicket(TENANT, cashier, ROLE_CASHIER, "\"customerId\":\"" + theirs.getId() + "\",", 400);
        mockMvc.perform(as(post("/api/v1/service-tickets/appointments"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"staffProfileId\":\"" + john + "\",\"customerId\":\"" + theirs.getId()
                                + "\",\"start\":\"" + ten.plus(9, ChronoUnit.HOURS)
                                + "\",\"name\":\"X\",\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}"))
                .andExpect(status().isBadRequest());
        String id = book(customer);
        act(id, "deposit", "{\"amount\":0,\"method\":\"cash\"}", 400);
        act(id, "deposit", "{\"amount\":100,\"method\":\"card\"}", 400);
        act(id, "deposit", "{\"amount\":100,\"method\":\"customer_wallet\"}", 400);
        mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/deposit"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"amount\":100,\"method\":\"cash\"}"))
                .andExpect(status().isNotFound());
        assertThat(journalLineRepository.findAll()).isEmpty();
        assertThat(posDraftRepository.findById(id).orElseThrow().getDepositState()).isNull();
        assertThat(shiftRepository.findAll().getFirst().getExpectedClosingCash()).isEqualByComparingTo("100.00");
    }

    @Test
    void aPaymentKeyAlreadyUsedForAnotherSaleCannotCloseATicketOrReleaseItsDeposit() throws Exception {
        String first = walkIn(null);
        act(first, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);
        act(first, "done", "{}", 200);
        String key = "reused-" + UUID.randomUUID();
        mockMvc.perform(as(post("/api/v1/service-tickets/" + first + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", key)
                        .content("{\"payments\":[{\"method\":\"cash\",\"amount\":300}]}"))
                .andExpect(status().isOk());

        String second = book(customer);
        act(second, "deposit", "{\"amount\":100,\"method\":\"cash\"}", 200);
        act(second, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);
        act(second, "done", "{}", 200);
        mockMvc.perform(as(post("/api/v1/service-tickets/" + second + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", key)
                        .content("{\"payments\":[{\"method\":\"cash\",\"amount\":200}]}"))
                .andExpect(status().isConflict());
        // Still open, deposit still held, wallet untouched, one sale.
        assertThat(posDraftRepository.findById(second).orElseThrow().getStatus()).isEqualTo("pending");
        assertThat(posDraftRepository.findById(second).orElseThrow().getDepositState()).isEqualTo("HELD");
        assertThat(accountBalance(DEPOSITS)).isEqualByComparingTo("100.00");
        assertThat(wallet()).isEqualByComparingTo("0.00");
        assertThat(saleRepository.count()).isEqualTo(1);
    }

    @Test
    void aRedoIsDoneForFreeAndCountedAgainstWhoDidIt() throws Exception {
        // The original visit.
        String first = walkIn(null);
        act(first, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);
        act(first, "done", "{}", 200);
        String saleId = pay(first, "[{\"method\":\"cash\",\"amount\":300}]", 200).get("sale").get("id").asText();

        // A redo must point at a sale of this business.
        createTicket(TENANT, cashier, ROLE_CASHIER, "\"redoOfSaleId\":\"" + UUID.randomUUID() + "\",", 404);
        JsonNode redo = createTicket(TENANT, cashier, ROLE_CASHIER, "\"redoOfSaleId\":\"" + saleId + "\",", 201);
        String redoId = redo.get("id").asText();
        assertThat(redo.get("redoOfSaleId").asText()).isEqualTo(saleId);
        act(redoId, "deposit", "{\"amount\":100,\"method\":\"cash\"}", 409);
        act(redoId, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":2}", 200);
        JsonNode done = act(redoId, "done", "{}", 200);
        assertThat(done.get("status").asText()).isEqualTo("completed");
        assertThat(done.get("stage").asText()).isEqualTo("REDO_DONE");
        act(redoId, "done", "{}", 200);
        pay(redoId, "[{\"method\":\"cash\",\"amount\":300}]", 409);
        // One sale, one commission: the redo earned nothing and charged nothing.
        assertThat(saleRepository.count()).isEqualTo(1);
        assertThat(accountBalance(SERVICE_REVENUE)).isEqualByComparingTo("300.00");

        JsonNode report = report(TENANT, owner, 200);
        assertThat(report.get("revenue").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(report.get("commission").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(report.get("contribution").decimalValue()).isEqualByComparingTo("180.00");
        JsonNode service = report.get("services").get(0);
        assertThat(service.get("serviceId").asText()).isEqualTo(haircut);
        assertThat(service.get("timesDone").asLong()).isEqualTo(1);
        assertThat(service.get("minutes").asLong()).isEqualTo(30);
        assertThat(service.get("contribution").decimalValue()).isEqualByComparingTo("180.00");
        JsonNode staff = report.get("staff").get(0);
        assertThat(staff.get("staffProfileId").asText()).isEqualTo(john);
        assertThat(staff.get("servicesDone").asLong()).isEqualTo(1);
        assertThat(staff.get("redos").asLong()).isEqualTo(1);
        assertThat(staff.get("commission").decimalValue()).isEqualByComparingTo("120.00");

        // The haircut uses half a unit of the stocked product, last delivered at its batch cost.
        java.math.BigDecimal unitCost = inventoryBatchRepository.findAll().getFirst().getUnitCost();
        mockMvc.perform(as(put("/api/v1/services/" + haircut + "/consumables"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"items\":[{\"itemId\":\"" + itemId + "\",\"quantity\":0.5}]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(put("/api/v1/services/" + haircut + "/consumables"), TENANT, owner, ROLE_OWNER)
                        .content("{\"items\":[{\"itemId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(put("/api/v1/services/" + haircut + "/consumables"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"items\":[{\"itemId\":\"" + itemId + "\",\"quantity\":0.5}]}"))
                .andExpect(status().isNotFound());
        MvcResult set = mockMvc.perform(as(put("/api/v1/services/" + haircut + "/consumables"), TENANT, owner, ROLE_OWNER)
                        .content("{\"items\":[{\"itemId\":\"" + itemId + "\",\"quantity\":0.5}]}"))
                .andExpect(status().isOk()).andReturn();
        java.math.BigDecimal perVisit = unitCost.multiply(new java.math.BigDecimal("0.5"))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(objectMapper.readTree(set.getResponse().getContentAsString()).get("costPerVisit").decimalValue())
                .isEqualByComparingTo(perVisit);
        JsonNode costed = report(TENANT, owner, 200);
        assertThat(costed.get("productCost").decimalValue()).isEqualByComparingTo(perVisit);
        assertThat(costed.get("contribution").decimalValue())
                .isEqualByComparingTo(new java.math.BigDecimal("180.00").subtract(perVisit));
        // Listing what a service uses does not touch stock.
        assertThat(inventoryBatchRepository.findAll().getFirst().getQuantityRemaining())
                .isEqualByComparingTo(inventoryBatchRepository.findAll().getFirst().getInitialQuantity());

        // Another business sees nothing of it, and a seller without the permission sees no report.
        JsonNode other = report(OTHER, otherOwner, 200);
        assertThat(other.get("services")).isEmpty();
        assertThat(other.get("staff")).isEmpty();
        mockMvc.perform(as(get("/api/v1/commissions/reports/services"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isForbidden());
        // A redo cannot be opened against another business's sale.
        mockMvc.perform(as(post("/api/v1/service-tickets"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"redoOfSaleId\":\"" + saleId + "\",\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void aVoidedSaleDropsOutOfTheReport() throws Exception {
        String id = walkIn(null);
        act(id, "start", "{\"performerStaffProfileId\":\"" + john + "\",\"stationNo\":1}", 200);
        act(id, "done", "{}", 200);
        String saleId = pay(id, "[{\"method\":\"cash\",\"amount\":300}]", 200).get("sale").get("id").asText();
        mockMvc.perform(as(post("/api/v1/sales/" + saleId + "/void"), TENANT, owner, ROLE_OWNER)
                        .content("{\"notes\":\"wrong customer\"}"))
                .andExpect(status().is2xxSuccessful());
        JsonNode report = report(TENANT, owner, 200);
        assertThat(report.get("revenue").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(report.get("commission").decimalValue()).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ helpers

    private BigDecimal wallet() {
        return creditAccountRepository.findByCustomerIdAndBusinessId(customer, TENANT).orElseThrow().getWalletBalance();
    }

    private String book(String customerId) throws Exception {
        String who = customerId == null ? "" : "\"customerId\":\"" + customerId + "\",";
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/appointments"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID()
                                + "\",\"staffProfileId\":\"" + john + "\"," + who + "\"start\":\""
                                + ten.plus(posDraftRepository.count(), ChronoUnit.HOURS)
                                + "\",\"name\":\"Wanjiru\",\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String walkIn(String customerId) throws Exception {
        String who = customerId == null ? "" : "\"customerId\":\"" + customerId + "\",";
        return createTicket(TENANT, cashier, ROLE_CASHIER, who, 201).get("id").asText();
    }

    private JsonNode createTicket(String tenant, User user, String role, String extra, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets"), tenant, user, role)
                        .content("{\"branchId\":\"" + branchId + "\",\"clientTicketId\":\"" + UUID.randomUUID() + "\","
                                + extra + "\"lines\":[{\"serviceId\":\"" + haircut + "\"}]}"))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode act(String id, String action, String body, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/" + action), TENANT, cashier, ROLE_CASHIER)
                        .content(body))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode pay(String id, String payments, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/service-tickets/" + id + "/pay"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "pay-" + UUID.randomUUID())
                        .content("{\"payments\":" + payments + "}"))
                .andExpect(status().is(expected)).andReturn();
        return expected >= 400 ? null : objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode report(String tenant, User user, int expected) throws Exception {
        MvcResult result = mockMvc.perform(as(get("/api/v1/commissions/reports/services"), tenant, user, ROLE_OWNER))
                .andExpect(status().is(expected)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
