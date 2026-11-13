package zelisline.ub.serviceops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerBootstrapService;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.payroll.repository.StaffProfileRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.serviceops.repository.ServiceCategoryRepository;
import zelisline.ub.serviceops.repository.ServiceOfferingRepository;
import zelisline.ub.serviceops.repository.ServiceStaffLinkRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/** Selling a service: a SERVICE sale line attributed to the staff member who did the work (ADR 0014). */
class ServiceSaleIT extends ServiceSaleTestSupport {

    @Test
    void sellsAServiceToTheStaffMemberWhoPerformedIt() throws Exception {
        String key = "svc-" + UUID.randomUUID();
        JsonNode sale = postSale(key, serviceLine(haircut, john, 1, "300"), "300", 201);
        assertThat(sale.get("grandTotal").decimalValue()).isEqualByComparingTo("300.00");
        JsonNode line = sale.get("items").get(0);
        assertThat(line.get("lineKind").asText()).isEqualTo("SERVICE");
        assertThat(line.get("displayName").asText()).isEqualTo("Haircut");
        assertThat(line.get("serviceId").asText()).isEqualTo(haircut);
        assertThat(line.get("performerStaffProfileId").asText()).isEqualTo(john);
        assertThat(line.get("performerName").asText()).isEqualTo("John");
        assertThat(line.get("serviceMinutes").asInt()).isEqualTo(30);
        assertThat(line.get("itemId").isNull()).isTrue();

        SaleItem stored = saleItemRepository.findBySaleIdOrderByLineIndexAsc(sale.get("id").asText()).getFirst();
        assertThat(stored.getBatchId()).isNull();
        assertThat(stored.getCostTotal()).isEqualByComparingTo("0");
        assertThat(stockMovementRepository.count()).isZero();

        Sale row = saleRepository.findById(sale.get("id").asText()).orElseThrow();
        List<JournalLine> journal = journalLineRepository.findByJournalEntryId(row.getJournalEntryId());
        assertBalanced(journal);
        assertThat(credit(journal, LedgerAccountCodes.SERVICE_REVENUE)).isEqualByComparingTo("300.00");
        assertThat(credit(journal, LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("0");
        assertThat(debit(journal, LedgerAccountCodes.OPERATING_CASH)).isEqualByComparingTo("300.00");

        // The same request again (a till replaying its queue) creates nothing new.
        postSale(key, serviceLine(haircut, john, 1, "300"), "300", 200);
        assertThat(saleRepository.count()).isEqualTo(1);
        assertThat(saleItemRepository.count()).isEqualTo(1);
    }

    @Test
    void aSaleCanMixServicesAndProducts() throws Exception {
        String lines = serviceLine(haircut, john, 1, "300")
                + ",{\"itemId\":\"" + itemId + "\",\"quantity\":2,\"unitPrice\":5}";
        JsonNode sale = postSale("mix-" + UUID.randomUUID(), lines, "310", 201);
        assertThat(sale.get("grandTotal").decimalValue()).isEqualByComparingTo("310.00");
        assertThat(sale.get("items")).hasSize(2);

        Sale row = saleRepository.findById(sale.get("id").asText()).orElseThrow();
        List<JournalLine> journal = journalLineRepository.findByJournalEntryId(row.getJournalEntryId());
        assertBalanced(journal);
        assertThat(credit(journal, LedgerAccountCodes.SERVICE_REVENUE)).isEqualByComparingTo("300.00");
        assertThat(credit(journal, LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("10.00");
        assertThat(debit(journal, LedgerAccountCodes.COST_OF_GOODS_SOLD)).isEqualByComparingTo("4.00");
        assertThat(stockMovementRepository.count()).isEqualTo(1);
    }

    @Test
    void aProductOnlySaleStillPostsToSalesRevenueOnly() throws Exception {
        JsonNode sale = postSale("retail-" + UUID.randomUUID(),
                "{\"itemId\":\"" + itemId + "\",\"quantity\":2,\"unitPrice\":5}", "10", 201);
        Sale row = saleRepository.findById(sale.get("id").asText()).orElseThrow();
        List<JournalLine> journal = journalLineRepository.findByJournalEntryId(row.getJournalEntryId());
        assertBalanced(journal);
        assertThat(journal).hasSize(4);
        assertThat(credit(journal, LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("10.00");
        assertThat(credit(journal, LedgerAccountCodes.SERVICE_REVENUE)).isEqualByComparingTo("0");
        JsonNode line = sale.get("items").get(0);
        assertThat(line.get("lineKind").asText()).isEqualTo("ITEM");
        assertThat(line.get("serviceId").isNull()).isTrue();
        assertThat(line.get("performerName").isNull()).isTrue();
    }

    @Test
    void refusesServiceLinesThatDoNotAddUp() throws Exception {
        String otherShopService = createService(OTHER, otherOwner,
                "{\"name\":\"Haircut\",\"durationMinutes\":30,\"price\":300}");
        String otherShopStaff = createStaff(OTHER, otherOwner, "Wanjiru");
        String peter = createStaff(TENANT, owner, "Peter");
        String colour = createService(TENANT, owner, "{\"name\":\"Colour\",\"durationMinutes\":90,\"price\":1500}");
        mockMvc.perform(as(put("/api/v1/services/" + colour + "/staff"), TENANT, owner, ROLE_OWNER)
                        .content("{\"staffProfileIds\":[\"" + peter + "\"]}"))
                .andExpect(status().isOk());

        List<String> bad = List.of(
                // another business's service, another business's staff
                serviceLine(otherShopService, john, 1, "300"),
                serviceLine(haircut, otherShopStaff, 1, "300"),
                // missing pieces
                "{\"kind\":\"SERVICE\",\"serviceId\":\"" + haircut + "\",\"quantity\":1,\"unitPrice\":300}",
                "{\"kind\":\"SERVICE\",\"performerStaffProfileId\":\"" + john + "\",\"quantity\":1,\"unitPrice\":300}",
                // a service line is not a product line, and the other way round
                "{\"kind\":\"SERVICE\",\"itemId\":\"" + itemId + "\",\"serviceId\":\"" + haircut
                        + "\",\"performerStaffProfileId\":\"" + john + "\",\"quantity\":1,\"unitPrice\":300}",
                "{\"itemId\":\"" + itemId + "\",\"serviceId\":\"" + haircut + "\",\"quantity\":1,\"unitPrice\":5}",
                // not a whole haircut, wrong price, unknown kind, staff not allowed to do this service
                serviceLine(haircut, john, 1, "250"),
                "{\"kind\":\"SERVICE\",\"serviceId\":\"" + haircut + "\",\"performerStaffProfileId\":\"" + john
                        + "\",\"quantity\":1.5,\"unitPrice\":300}",
                "{\"kind\":\"MYSTERY\",\"itemId\":\"" + itemId + "\",\"quantity\":1,\"unitPrice\":5}",
                serviceLine(colour, john, 1, "1500"));
        for (String line : bad) {
            mockMvc.perform(as(post("/api/v1/sales"), TENANT, cashier, ROLE_CASHIER)
                            .header("Idempotency-Key", "bad-" + UUID.randomUUID())
                            .content(saleBody(line, "300")))
                    .andExpect(status().isBadRequest());
        }
        assertThat(saleRepository.count()).isZero();
        assertThat(saleItemRepository.count()).isZero();
        assertThat(stockMovementRepository.count()).isZero();

        // Peter may do the colour.
        postSale("ok-" + UUID.randomUUID(), serviceLine(colour, peter, 1, "1500"), "1500", 201);
    }

    @Test
    void removedSwitchedOffOrFormerStaffCannotBeSold() throws Exception {
        mockMvc.perform(as(patch("/api/v1/staff/profiles/" + john), TENANT, owner, ROLE_OWNER)
                        .content("{\"employmentStatus\":\"terminated\"}"))
                .andExpect(status().isOk());
        postSale("gone-" + UUID.randomUUID(), serviceLine(haircut, john, 1, "300"), "300", 400);

        String peter = createStaff(TENANT, owner, "Peter");
        mockMvc.perform(as(patch("/api/v1/services/" + haircut), TENANT, owner, ROLE_OWNER)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());
        postSale("off-" + UUID.randomUUID(), serviceLine(haircut, peter, 1, "300"), "300", 400);

        mockMvc.perform(as(delete("/api/v1/services/" + haircut), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isNoContent());
        postSale("del-" + UUID.randomUUID(), serviceLine(haircut, peter, 1, "300"), "300", 400);
        assertThat(saleRepository.count()).isZero();
    }

    @Test
    void priceFollowsTheServiceUnlessItIsVariableOrTheSellerMaySetPrices() throws Exception {
        // The owner holds pricing.sell_price.set and may charge a different price.
        mockMvc.perform(as(post("/api/v1/sales"), TENANT, owner, ROLE_OWNER)
                        .header("Idempotency-Key", "disc-" + UUID.randomUUID())
                        .content(saleBody(serviceLine(haircut, john, 1, "250"), "250")))
                .andExpect(status().isCreated());

        String braids = createService(TENANT, owner,
                "{\"name\":\"Braids\",\"durationMinutes\":180,\"price\":0,\"priceIsVariable\":true}");
        JsonNode sale = postSale("var-" + UUID.randomUUID(), serviceLine(braids, john, 1, "2400"), "2400", 201);
        assertThat(sale.get("grandTotal").decimalValue()).isEqualByComparingTo("2400.00");
        // Even a variable price must be positive.
        postSale("zero-" + UUID.randomUUID(), serviceLine(braids, john, 1, "0"), "0", 400);
    }

    @Test
    void aSoldServiceKeepsItsNameAndPerformerAfterTheCatalogueChanges() throws Exception {
        JsonNode sale = postSale("snap-" + UUID.randomUUID(), serviceLine(haircut, john, 2, "300"), "600", 201);
        String saleId = sale.get("id").asText();
        mockMvc.perform(as(patch("/api/v1/services/" + haircut), TENANT, owner, ROLE_OWNER)
                        .content("{\"name\":\"Fade\",\"durationMinutes\":45,\"price\":400}"))
                .andExpect(status().isOk());
        SaleItem stored = saleItemRepository.findBySaleIdOrderByLineIndexAsc(saleId).getFirst();
        assertThat(stored.getDisplayName()).isEqualTo("Haircut");
        assertThat(stored.getServiceMinutes()).isEqualTo(30);
        assertThat(stored.getLineTotal()).isEqualByComparingTo("600.00");
        assertThat(stored.getPerformerStaffProfileId()).isEqualTo(john);
    }

    @Test
    void voidingReversesServiceRevenue() throws Exception {
        String lines = serviceLine(haircut, john, 1, "300")
                + ",{\"itemId\":\"" + itemId + "\",\"quantity\":2,\"unitPrice\":5}";
        String saleId = postSale("void-" + UUID.randomUUID(), lines, "310", 201).get("id").asText();

        mockMvc.perform(as(post("/api/v1/sales/{saleId}/void", saleId), TENANT, cashier, ROLE_CASHIER)
                        .content("{\"notes\":\"wrong barber\"}"))
                .andExpect(status().isOk());

        Sale voided = saleRepository.findById(saleId).orElseThrow();
        assertThat(voided.getStatus()).isEqualTo("voided");
        List<JournalLine> journal = journalLineRepository.findByJournalEntryId(voided.getVoidJournalEntryId());
        assertBalanced(journal);
        assertThat(debit(journal, LedgerAccountCodes.SERVICE_REVENUE)).isEqualByComparingTo("300.00");
        assertThat(debit(journal, LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("10.00");
        // The product went back on the shelf; the service had no stock to return.
        assertThat(itemRepository.findById(itemId).orElseThrow().getCurrentStock()).isEqualByComparingTo("10");
    }

    @Test
    void aServiceLineIsReversedByVoidNotByLineRefund() throws Exception {
        String lines = serviceLine(haircut, john, 1, "300")
                + ",{\"itemId\":\"" + itemId + "\",\"quantity\":2,\"unitPrice\":5}";
        JsonNode sale = postSale("refund-" + UUID.randomUUID(), lines, "310", 201);
        String saleId = sale.get("id").asText();
        String serviceLineId = null;
        String productLineId = null;
        for (JsonNode line : sale.get("items")) {
            if ("SERVICE".equals(line.get("lineKind").asText())) {
                serviceLineId = line.get("id").asText();
            } else {
                productLineId = line.get("id").asText();
            }
        }

        mockMvc.perform(as(post("/api/v1/sales/{saleId}/refund", saleId), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "r1-" + UUID.randomUUID())
                        .content("{\"lines\":[{\"saleItemId\":\"" + serviceLineId
                                + "\",\"quantity\":1}],\"payments\":[{\"method\":\"cash\",\"amount\":300}],\"reason\":\"unhappy\"}"))
                .andExpect(status().isBadRequest());

        // The product on the same sale still refunds normally, out of goods revenue.
        MvcResult refund = mockMvc.perform(as(post("/api/v1/sales/{saleId}/refund", saleId), TENANT, cashier, ROLE_CASHIER)
                        .header("Idempotency-Key", "r2-" + UUID.randomUUID())
                        .content("{\"lines\":[{\"saleItemId\":\"" + productLineId
                                + "\",\"quantity\":2}],\"payments\":[{\"method\":\"cash\",\"amount\":10}],\"reason\":\"returned\"}"))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        String refundJournal = objectMapper.readTree(refund.getResponse().getContentAsString())
                .get("journalEntryId").asText();
        List<JournalLine> journal = journalLineRepository.findByJournalEntryId(refundJournal);
        assertBalanced(journal);
        assertThat(debit(journal, LedgerAccountCodes.SALES_REVENUE)).isEqualByComparingTo("10.00");
        assertThat(debit(journal, LedgerAccountCodes.SERVICE_REVENUE)).isEqualByComparingTo("0");
    }
}
