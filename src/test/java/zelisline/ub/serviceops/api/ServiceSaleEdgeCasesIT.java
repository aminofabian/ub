package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.receipt.SaleReceiptService;
import zelisline.ub.serviceops.application.StaffEarningsService;

/**
 * Paths an independent review found broken or untested: a service with no product on the sale
 * paid on a tab or overpaid by a named customer, its receipt, repeated partial refunds, and who
 * may be the performer.
 */
class ServiceSaleEdgeCasesIT extends ServiceSaleTestSupport {

    @Autowired
    private SaleReceiptService saleReceiptService;
    @Autowired
    private StaffEarningsService staffEarningsService;
    @Autowired
    private StaffProfileService staffProfileService;

    @Test
    void aServiceOnlySaleCanGoOnACustomersTab() throws Exception {
        String customerId = seedCustomer(new BigDecimal("5000.00"));
        MvcResult result = mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "tab-" + UUID.randomUUID())
                        .content("{\"branchId\":\"" + branchId + "\",\"customerId\":\"" + customerId + "\",\"lines\":["
                                + serviceLine(haircut, john, 1, "300")
                                + "],\"payments\":[{\"method\":\"customer_credit\",\"amount\":300}]}"))
                .andExpect(status().isCreated())
                .andReturn();
        String saleId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        assertThat(creditAccountRepository.findByCustomerIdAndBusinessId(customerId, TENANT).orElseThrow()
                .getBalanceOwed()).isEqualByComparingTo("300.00");
        Sale sale = saleRepository.findById(saleId).orElseThrow();
        List<JournalLine> journal = journalLineRepository.findByJournalEntryId(sale.getJournalEntryId());
        assertBalanced(journal);
        assertThat(debit(journal, LedgerAccountCodes.ACCOUNTS_RECEIVABLE_CUSTOMERS)).isEqualByComparingTo("300.00");
        assertThat(credit(journal, LedgerAccountCodes.SERVICE_REVENUE)).isEqualByComparingTo("300.00");
    }

    @Test
    void aNamedCustomerOverpayingForAServiceGetsTheExtraInTheirWallet() throws Exception {
        String customerId = seedCustomer(new BigDecimal("0.00"));
        mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "over-" + UUID.randomUUID())
                        .content("{\"branchId\":\"" + branchId + "\",\"customerId\":\"" + customerId + "\",\"lines\":["
                                + serviceLine(haircut, john, 1, "300")
                                + "],\"payments\":[{\"method\":\"cash\",\"amount\":500}]}"))
                .andExpect(status().isCreated());
        assertThat(walletTransactionRepository.count()).isPositive();
    }

    @Test
    void theReceiptForAServiceSaleNamesThePerformerAndShowsTheTip() throws Exception {
        MvcResult result = mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "rcpt-" + UUID.randomUUID())
                        .content("{\"branchId\":\"" + branchId + "\",\"lines\":[" + serviceLine(haircut, john, 1, "300")
                                + "],\"payments\":[{\"method\":\"cash\",\"amount\":350}],\"tips\":[{\"staffProfileId\":\""
                                + john + "\",\"amount\":50}]}"))
                .andExpect(status().isCreated())
                .andReturn();
        String saleId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        String receipt = new String(saleReceiptService.buildEscPos(TENANT, saleId, 80), StandardCharsets.ISO_8859_1);
        assertThat(receipt).contains("Haircut - John");
        assertThat(receipt).contains("TOTAL 300.00");
        assertThat(receipt).contains("Tip 50.00");
        assertThat(receipt).contains("TO PAY 350.00");
        assertThat(saleReceiptService.buildPdf(TENANT, saleId)).isNotEmpty();

        // A sale with no tip prints no tip lines.
        String plain = postSale("rcpt2-" + UUID.randomUUID(), serviceLine(haircut, john, 1, "300"), "300", 201)
                .get("id").asText();
        String plainReceipt = new String(saleReceiptService.buildEscPos(TENANT, plain, 80), StandardCharsets.ISO_8859_1);
        assertThat(plainReceipt).contains("Haircut - John").doesNotContain("Tip ").doesNotContain("TO PAY");
    }

    @Test
    void refundingAProductLineBitByBitReversesTheWholeCommissionInTheEnd() throws Exception {
        String cashierProfile = staffProfileService.ensureProfile(TENANT, cashier.getId()).getId();
        mockMvc.perform(as(put("/api/v1/commissions/plans/" + cashierProfile), TENANT, owner, ROLE_OWNER)
                        .content("{\"productRate\":20}"))
                .andExpect(status().isOk());
        // 3 units at 5 = 15, 20 % = 3 shillings.
        JsonNode sale = postSale("bit-" + UUID.randomUUID(),
                "{\"itemId\":\"" + itemId + "\",\"quantity\":3,\"unitPrice\":5}", "15", 201);
        assertThat(staffEarningsService.balance(TENANT, cashierProfile)).isEqualByComparingTo("3.00");
        String lineId = sale.get("items").get(0).get("id").asText();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(as(post("/api/v1/sales/{saleId}/refund", sale.get("id").asText()), TENANT, cashier, ROLE_CASHIER)
                            .header("Idempotency-Key", "bit-r" + i + "-" + UUID.randomUUID())
                            .content("{\"lines\":[{\"saleItemId\":\"" + lineId
                                    + "\",\"quantity\":1}],\"payments\":[{\"method\":\"cash\",\"amount\":5}],\"reason\":\"returned\"}"))
                    .andExpect(status().is2xxSuccessful());
        }
        assertThat(staffEarningsService.balance(TENANT, cashierProfile)).isEqualByComparingTo("0.00");
    }

    @Test
    void onlyStaffMarkedAsServingCustomersCanBeThePerformer() throws Exception {
        // The cashier has a login and a staff profile, but is not marked as serving customers.
        String cashierProfile = staffProfileService.ensureProfile(TENANT, cashier.getId()).getId();
        postSale("nb-" + UUID.randomUUID(), serviceLine(haircut, cashierProfile, 1, "300"), "300", 400);
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + cashierProfile), TENANT, owner, ROLE_OWNER)
                        .content("{\"bookable\":true}"))
                .andExpect(status().isOk());
        postSale("nb2-" + UUID.randomUUID(), serviceLine(haircut, cashierProfile, 1, "300"), "300", 201);
    }

    @Test
    void lineKindIsCaseInsensitiveAndBlankMeansProduct() throws Exception {
        postSale("lk1-" + UUID.randomUUID(), "{\"kind\":\"service\",\"serviceId\":\"" + haircut
                + "\",\"performerStaffProfileId\":\"" + john + "\",\"quantity\":1,\"unitPrice\":300}", "300", 201);
        postSale("lk2-" + UUID.randomUUID(), "{\"kind\":\"\",\"serviceId\":\"\",\"itemId\":\"" + itemId
                + "\",\"quantity\":1,\"unitPrice\":5}", "5", 201);
        postSale("lk3-" + UUID.randomUUID(), "{\"kind\":\"item\",\"itemId\":\"" + itemId
                + "\",\"quantity\":1,\"unitPrice\":5}", "5", 201);
    }

    @Test
    void anotherBusinessCannotAdjustOrReverseAPayout() throws Exception {
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/adjustments"), TENANT, owner, ROLE_OWNER)
                        .header("Idempotency-Key", "x-" + UUID.randomUUID()).content("{\"amount\":200,\"reason\":\"bonus\"}"))
                .andExpect(status().isOk());
        MvcResult paid = mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/payouts"), TENANT, owner, ROLE_OWNER)
                        .header("Idempotency-Key", "x-" + UUID.randomUUID()).content("{\"amount\":200,\"method\":\"OTHER\"}"))
                .andExpect(status().isOk()).andReturn();
        String entryId = objectMapper.readTree(paid.getResponse().getContentAsString()).get(0).get("id").asText();

        mockMvc.perform(as(post("/api/v1/commissions/payouts/" + entryId + "/reverse"), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/v1/commissions/staff/" + john + "/adjustments"), OTHER, otherOwner, ROLE_OWNER)
                        .header("Idempotency-Key", "x-" + UUID.randomUUID()).content("{\"amount\":999,\"reason\":\"theft\"}"))
                .andExpect(status().isNotFound());
        assertThat(staffEarningsService.balance(TENANT, john)).isEqualByComparingTo("0.00");
    }

    @Test
    void chairsAreACountPerBranchAndStaffHaveAUsualChair() throws Exception {
        mockMvc.perform(as(put("/api/v1/service-stations"), TENANT, owner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"count\":3}"))
                .andExpect(status().isOk());
        MvcResult read = mockMvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/service-stations").param("branchId", branchId), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(read.getResponse().getContentAsString()).get("count").asInt()).isEqualTo(3);

        for (String bad : List.of("{\"branchId\":\"" + branchId + "\",\"count\":51}",
                "{\"branchId\":\"" + branchId + "\",\"count\":-1}", "{\"count\":2}")) {
            mockMvc.perform(as(put("/api/v1/service-stations"), TENANT, owner, ROLE_OWNER).content(bad))
                    .andExpect(status().isBadRequest());
        }
        // A seller cannot change it; another business cannot see or change this branch.
        mockMvc.perform(as(put("/api/v1/service-stations"), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"branchId\":\"" + branchId + "\",\"count\":9}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(put("/api/v1/service-stations"), OTHER, otherOwner, ROLE_OWNER)
                        .content("{\"branchId\":\"" + branchId + "\",\"count\":9}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/service-stations").param("branchId", branchId), OTHER, otherOwner, ROLE_OWNER))
                .andExpect(status().isNotFound());
        assertThat(branchRepository.findById(branchId).orElseThrow().getServiceStationCount()).isEqualTo(3);

        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + john), TENANT, owner, ROLE_OWNER)
                        .content("{\"usualStationNo\":2}"))
                .andExpect(status().isOk());
        assertThat(staffProfileRepository.findById(john).orElseThrow().getUsualStationNo()).isEqualTo(2);
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + john), TENANT, owner, ROLE_OWNER)
                        .content("{\"usualStationNo\":0}"))
                .andExpect(status().isOk());
        assertThat(staffProfileRepository.findById(john).orElseThrow().getUsualStationNo()).isNull();
    }

    private String seedCustomer(BigDecimal creditLimit) {
        Customer c = new Customer();
        c.setBusinessId(TENANT);
        c.setName("Kevin");
        customerRepository.save(c);
        CustomerPhone ph = new CustomerPhone();
        ph.setBusinessId(TENANT);
        ph.setCustomerId(c.getId());
        ph.setPhone("0700990001");
        ph.setPrimary(true);
        customerPhoneRepository.save(ph);
        CreditAccount a = new CreditAccount();
        a.setBusinessId(TENANT);
        a.setCustomerId(c.getId());
        a.setCreditLimit(creditLimit);
        creditAccountRepository.save(a);
        return c.getId();
    }
}
