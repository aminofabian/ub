package zelisline.ub.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.domain.RestockRun;
import zelisline.ub.inventory.domain.RestockSuggestion;
import zelisline.ub.inventory.repository.RestockRunRepository;
import zelisline.ub.inventory.repository.RestockSuggestionRepository;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * End-to-end nightly restock digest: seed a branch with one item + batch + a sale in
 * the window + a primary supplier link, generate the run, then verify the suggestion
 * line, counts, idempotency, and the read endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class RestockDigestIT {

    private static final String TENANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1";
    private static final String P_PATH_A_WRITE = "11111111-0000-0000-0000-000000000050";
    private static final String P_PATH_A_READ = "11111111-0000-0000-0000-000000000049";
    private static final String P_ORDER_PAD_WRITE = "11111111-0000-0000-0000-000000000060";
    private static final String ROLE_OWNER = "22222222-0000-0000-0000-0000000000a1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BusinessRepository businessRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private ItemCatalogService itemCatalogService;
    @Autowired private CatalogBootstrapService catalogBootstrapService;
    @Autowired private ItemTypeRepository itemTypeRepository;
    @Autowired private InventoryBatchRepository inventoryBatchRepository;
    @Autowired private SaleRepository saleRepository;
    @Autowired private SaleItemRepository saleItemRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private SupplierProductRepository supplierProductRepository;
    @Autowired private RestockRunRepository restockRunRepository;
    @Autowired private RestockSuggestionRepository restockSuggestionRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private zelisline.ub.purchasing.repository.PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private zelisline.ub.purchasing.repository.PurchaseOrderLineRepository purchaseOrderLineRepository;
    @Autowired private zelisline.ub.inventory.repository.OrderPadItemRepository orderPadItemRepository;

    private User owner;
    private String branchId;
    private String itemId;
    private String supplierId;
    private LocalDate runDate;

    @BeforeEach
    void seed() throws Exception {
        restockSuggestionRepository.deleteAll();
        restockRunRepository.deleteAll();
        saleItemRepository.deleteAll();
        saleRepository.deleteAll();
        supplierProductRepository.deleteAll();
        supplierRepository.deleteAll();
        inventoryBatchRepository.deleteAll();
        itemRepository.deleteAll();
        branchRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Digest Shop");
        b.setSlug("digest-shop");
        b.setSettings("{}");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        br.setRestockCoverDays(3);
        branchRepository.save(br);
        branchId = br.getId();

        Permission writePerm = new Permission();
        writePerm.setId(P_PATH_A_WRITE);
        writePerm.setPermissionKey("purchasing.path_a.write");
        writePerm.setDescription("Path A write");
        permissionRepository.save(writePerm);

        Permission readPerm = new Permission();
        readPerm.setId(P_PATH_A_READ);
        readPerm.setPermissionKey("purchasing.path_a.read");
        readPerm.setDescription("Path A read");
        permissionRepository.save(readPerm);
        Role ownerRole = new Role();
        ownerRole.setId(ROLE_OWNER);
        ownerRole.setBusinessId(null);
        ownerRole.setRoleKey("owner");
        ownerRole.setName("Owner");
        ownerRole.setSystem(true);
        roleRepository.save(ownerRole);
        grant(ROLE_OWNER, P_PATH_A_WRITE);
        grant(ROLE_OWNER, P_PATH_A_READ);
        Permission padWritePerm = new Permission();
        padWritePerm.setId(P_ORDER_PAD_WRITE);
        padWritePerm.setPermissionKey("order_pad.write");
        padWritePerm.setDescription("Order pad write");
        permissionRepository.save(padWritePerm);
        grant(ROLE_OWNER, P_ORDER_PAD_WRITE);
        owner = user("owner-digest@test", ROLE_OWNER);

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
        String goodsTypeId =
                itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();
        itemId = itemCatalogService
                .createItem(
                        TENANT,
                        new CreateItemRequest(
                                "SKU-DIGEST", null, "Digest Item", null, goodsTypeId, null, null,
                                null, false, true, true, null, null, null, null, null,
                                null, null, null, null, null, false, null, null, null, null),
                        null)
                .body()
                .id();

        var item = itemRepository.findById(itemId).orElseThrow();
        item.setMinStockLevel(new BigDecimal("5"));
        item.setReorderLevel(new BigDecimal("5"));
        item.setBuyingPrice(new BigDecimal("52.0000"));
        itemRepository.save(item);

        InventoryBatch batch = new InventoryBatch();
        batch.setId(UUID.randomUUID().toString());
        batch.setBusinessId(TENANT);
        batch.setBranchId(branchId);
        batch.setItemId(itemId);
        batch.setBatchNumber("DIGEST-B1");
        batch.setSourceType("test");
        batch.setSourceId("source-digest-1");
        BigDecimal qty = new BigDecimal("4");
        batch.setInitialQuantity(qty);
        batch.setQuantityRemaining(qty);
        batch.setUnitCost(new BigDecimal("52.0000"));
        batch.setReceivedAt(Instant.parse("2026-04-01T12:00:00Z"));
        batch.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
        inventoryBatchRepository.save(batch);

        Supplier supplier = new Supplier();
        supplier.setBusinessId(TENANT);
        supplier.setName("Digest Supplier");
        supplier.setStatus("active");
        supplierRepository.save(supplier);
        supplierId = supplier.getId();

        SupplierProduct link = new SupplierProduct();
        link.setSupplierId(supplierId);
        link.setItemId(itemId);
        link.setPrimaryLink(true);
        link.setDefaultCostPrice(new BigDecimal("52.0000"));
        link.setLeadTimeDays(1);
        link.setActive(true);
        supplierProductRepository.save(link);

        runDate = LocalDate.now(ZoneOffset.UTC);
        seedSaleYesterday(itemId);
    }

    @Test
    void generate_createsRunWithSuggestion() throws Exception {
        MvcResult generated = mockMvc.perform(
                        post("/api/v1/inventory/restock/runs/generate")
                                .param("branchId", branchId)
                                .header("X-Tenant-Id", TENANT)
                                .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode run = objectMapper.readTree(generated.getResponse().getContentAsString());
        assertThat(run.get("branchId").asText()).isEqualTo(branchId);
        assertThat(run.get("status").asText()).isEqualTo("generated");
        assertThat(run.get("trigger").asText()).isEqualTo("manual");
        assertThat(run.get("lineCount").asInt()).isEqualTo(1);
        assertThat(run.get("poLineCount").asInt()).isEqualTo(1);
        assertThat(run.get("padLineCount").asInt()).isZero();
        assertThat(run.get("suggestions")).hasSize(1);

        JsonNode line = run.get("suggestions").get(0);
        assertThat(line.get("itemId").asText()).isEqualTo(itemId);
        assertThat(line.get("target").asText()).isEqualTo("po");
        assertThat(line.get("supplierId").asText()).isEqualTo(supplierId);
        assertThat(line.get("reasonCode").asText()).contains("BELOW_MIN");
        assertThat(line.get("confidence").asText()).isEqualTo("low");
        assertThat(line.get("status").asText()).isEqualTo("pending");
        // on-hand 4 <= reorder 5 → BELOW_MIN; thin history fallback par = reorder*2 = 10 → suggested 6
        assertThat(line.get("suggestedQty").decimalValue()).isEqualByComparingTo("6");
        assertThat(line.get("onHand").decimalValue()).isEqualByComparingTo("4");
        assertThat(line.get("unitCost").decimalValue()).isEqualByComparingTo("52.0000");

        String runId = run.get("id").asText();
        mockMvc.perform(get("/api/v1/inventory/restock/runs/" + runId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineCount").value(1));
    }

    @Test
    void generate_isIdempotentPerDay() throws Exception {
        String firstId = generate().get("id").asText();
        String secondId = generate().get("id").asText();
        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void latest_returnsRun() throws Exception {
        String runId = generate().get("id").asText();
        mockMvc.perform(get("/api/v1/inventory/restock/runs/latest")
                        .param("branchId", branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId));
    }

    @Test
    void list_returnsRunRow() throws Exception {
        generate();
        mockMvc.perform(get("/api/v1/inventory/restock/runs")
                        .param("branchId", branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].branchId").value(branchId))
                .andExpect(jsonPath("$[0].lineCount").value(1));
    }

    @Test
    void accept_createsDraftPoAndAcceptsSuggestion() throws Exception {
        JsonNode run = generate();
        String runId = run.get("id").asText();

        JsonNode body = acceptAll(runId);
        assertThat(body.get("run").get("status").asText()).isEqualTo("accepted");
        assertThat(body.get("padLinesCreated").asInt()).isZero();
        assertThat(body.get("skippedLines")).isEmpty();
        JsonNode poRef = body.get("purchaseOrders").get(0);
        assertThat(poRef.get("lineCount").asInt()).isEqualTo(1);
        assertThat(poRef.get("supplierName").asText()).isEqualTo("Digest Supplier");
        assertThat(poRef.get("poNumber").asText()).isNotEmpty();
        assertThat(body.get("run").get("suggestions").get(0).get("status").asText())
                .isEqualTo("accepted");
        assertThat(body.get("run").get("suggestions").get(0).get("acceptedQty").decimalValue())
                .isEqualByComparingTo("6");

        var po = purchaseOrderRepository
                .findByIdAndBusinessId(poRef.get("purchaseOrderId").asText(), TENANT)
                .orElseThrow();
        assertThat(po.getStatus()).isEqualTo("draft");
        assertThat(po.getSource()).isEqualTo("restock");
        assertThat(po.getSupplierId()).isEqualTo(supplierId);
        var lines = purchaseOrderLineRepository.findByPurchaseOrderIdOrderBySortOrderAscIdAsc(po.getId());
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().getItemId()).isEqualTo(itemId);
        assertThat(lines.getFirst().getQtyOrdered()).isEqualByComparingTo("6.0000");
    }

    @Test
    void accept_isIdempotent_reusesPo() throws Exception {
        JsonNode run = generate();
        String runId = run.get("id").asText();

        JsonNode first = acceptAll(runId);
        String poId = first.get("purchaseOrders").get(0).get("purchaseOrderId").asText();

        JsonNode second = acceptAll(runId);
        assertThat(second.get("purchaseOrders")).isEmpty(); // nothing left to accept
        assertThat(second.get("run").get("suggestions").get(0).get("status").asText())
                .isEqualTo("accepted");
        var lines = purchaseOrderLineRepository.findByPurchaseOrderIdOrderBySortOrderAscIdAsc(poId);
        assertThat(lines).hasSize(1); // no duplicate lines
    }

    @Test
    void acceptPad_createsOrderPadItem() throws Exception {
        String padItemId = seedSecondItemNoSupplier("SKU-PAD", "Digest Pad Item");
        JsonNode run = generate();
        JsonNode pad = null;
        for (JsonNode s : run.get("suggestions")) {
            if ("pad".equals(s.get("target").asText())) {
                pad = s;
                break;
            }
        }
        assertThat(pad).isNotNull();
        String runId = run.get("id").asText();

        MvcResult result = mockMvc.perform(
                        post("/api/v1/inventory/restock/runs/" + runId + "/accept")
                                .contentType(APPLICATION_JSON)
                                .content("{\"mode\":\"pad\",\"lineIds\":[\""
                                        + pad.get("id").asText() + "\"]}")
                                .header("X-Tenant-Id", TENANT)
                                .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("padLinesCreated").asInt()).isEqualTo(1);
        assertThat(body.get("purchaseOrders")).isEmpty();
        // The PO-target line stays pending → partially_accepted.
        assertThat(body.get("run").get("status").asText()).isEqualTo("partially_accepted");
        String padRecordId = null;
        for (JsonNode s : body.get("run").get("suggestions")) {
            if ("pad".equals(s.get("target").asText())) {
                assertThat(s.get("status").asText()).isEqualTo("accepted");
                padRecordId = s.get("orderPadItemId").asText();
            } else {
                assertThat(s.get("status").asText()).isEqualTo("pending");
            }
        }
        assertThat(padRecordId).isNotNull();
        var padRow = orderPadItemRepository.findById(padRecordId).orElseThrow();
        assertThat(padRow.getItemId()).isEqualTo(padItemId);
        assertThat(padRow.getQuantity()).isEqualByComparingTo(pad.get("suggestedQty").decimalValue());
        assertThat(padRow.isOrdered()).isFalse();
    }

    @Test
    void snooze_excludesItemFromNextRun() throws Exception {
        JsonNode run = generate();
        String suggestionId = run.get("suggestions").get(0).get("id").asText();

        mockMvc.perform(post("/api/v1/inventory/restock/suggestions/" + suggestionId + "/snooze")
                        .contentType(APPLICATION_JSON)
                        .content("{\"days\":2}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions[0].status").value("snoozed"))
                .andExpect(jsonPath("$.suggestions[0].snoozeUntil").value(runDate.plusDays(2).toString()));

        LocalDate next = runDate.plusDays(1);
        MvcResult nextRun = mockMvc.perform(post("/api/v1/inventory/restock/runs/generate")
                        .param("branchId", branchId)
                        .param("runDate", next.toString())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode nextBody = objectMapper.readTree(nextRun.getResponse().getContentAsString());
        assertThat(nextBody.get("lineCount").asInt()).isZero();
        assertThat(nextBody.get("suggestions")).isEmpty();
    }

    @Test
    void dismiss_marksSuggestionDismissed() throws Exception {
        JsonNode run = generate();
        String suggestionId = run.get("suggestions").get(0).get("id").asText();
        mockMvc.perform(post("/api/v1/inventory/restock/suggestions/" + suggestionId + "/dismiss")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions[0].status").value("dismissed"));
    }

    @Test
    void activeRun_returnsSummaryOnlyWhenActionable() throws Exception {
        MvcResult empty = mockMvc.perform(get("/api/v1/inventory/restock/runs/active")
                        .param("branchId", branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode emptyBody = objectMapper.readTree(empty.getResponse().getContentAsString());
        assertThat(emptyBody.get("runId").isNull()).isTrue();
        assertThat(emptyBody.get("lineCount").asInt()).isZero();

        JsonNode run = generate();
        MvcResult active = mockMvc.perform(get("/api/v1/inventory/restock/runs/active")
                        .param("branchId", branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode activeBody = objectMapper.readTree(active.getResponse().getContentAsString());
        assertThat(activeBody.get("runId").asText()).isEqualTo(run.get("id").asText());
        assertThat(activeBody.get("status").asText()).isEqualTo("generated");
        assertThat(activeBody.get("lineCount").asInt()).isEqualTo(1);

        // Accepting the run makes it non-actionable → empty summary again.
        acceptAll(run.get("id").asText());
        MvcResult done = mockMvc.perform(get("/api/v1/inventory/restock/runs/active")
                        .param("branchId", branchId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode doneBody = objectMapper.readTree(done.getResponse().getContentAsString());
        assertThat(doneBody.get("runId").isNull()).isTrue();
    }

    @Test
    void acceptHistory_biasesParTowardAcceptedQty() throws Exception {
        // Three historical accepted runs where the reviewer accepted half of the suggestion.
        for (int i = 1; i <= 3; i++) {
            RestockRun hist = new RestockRun();
            hist.setId("hist-run-" + i);
            hist.setBusinessId(TENANT);
            hist.setBranchId(branchId);
            hist.setRunDate(runDate.minusDays(i));
            hist.setGeneratedAt(Instant.now());
            hist.setStatus(InventoryConstants.DIGEST_RUN_ACCEPTED);
            hist.setLineCount(1);
            hist.setPoLineCount(1);
            hist.setCurrency("KES");
            hist.setTrigger(InventoryConstants.DIGEST_TRIGGER_SCHEDULED);
            hist.setEstTotal(new BigDecimal("312"));
            restockRunRepository.save(hist);

            RestockSuggestion s = new RestockSuggestion();
            s.setRunId(hist.getId());
            s.setBusinessId(TENANT);
            s.setBranchId(branchId);
            s.setItemId(itemId);
            s.setTarget(InventoryConstants.DIGEST_TARGET_PO);
            s.setOnHand(new BigDecimal("4"));
            s.setInbound(BigDecimal.ZERO);
            s.setReorderLevel(new BigDecimal("5"));
            s.setPar(new BigDecimal("10"));
            s.setSuggestedQty(new BigDecimal("6"));
            s.setAcceptedQty(new BigDecimal("3")); // 0.5 ratio → clamped to 0.6
            s.setUnitCost(new BigDecimal("52"));
            s.setReasonCode(InventoryConstants.DIGEST_REASON_BELOW_MIN);
            s.setEvidence("hist");
            s.setConfidence(InventoryConstants.DIGEST_CONFIDENCE_LOW);
            s.setStatus(InventoryConstants.DIGEST_SUGGESTION_ACCEPTED);
            restockSuggestionRepository.save(s);
        }

        JsonNode run = generate();
        JsonNode line = run.get("suggestions").get(0);
        // fallback par 10 × 0.6 = 6 → suggested = 6 − 4 = 2
        assertThat(line.get("par").decimalValue()).isEqualByComparingTo("6");
        assertThat(line.get("suggestedQty").decimalValue()).isEqualByComparingTo("2");
    }

    @Test
    void newRun_expiresPriorPendingRun() throws Exception {
        JsonNode first = generate();
        String firstRunId = first.get("id").asText();
        String firstSuggestionId = first.get("suggestions").get(0).get("id").asText();

        LocalDate next = runDate.plusDays(1);
        mockMvc.perform(post("/api/v1/inventory/restock/runs/generate")
                        .param("branchId", branchId)
                        .param("runDate", next.toString())
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/inventory/restock/runs/" + firstRunId)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("expired"));

        // Dismiss / accept on the expired run are rejected.
        mockMvc.perform(post("/api/v1/inventory/restock/suggestions/" + firstSuggestionId + "/dismiss")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/inventory/restock/runs/" + firstRunId + "/accept")
                        .contentType(APPLICATION_JSON)
                        .content("{\"mode\":\"all\"}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isConflict());
    }

    @Test
    void accept_zeroQtyOverride_rejected() throws Exception {
        JsonNode run = generate();
        String runId = run.get("id").asText();
        String suggestionId = run.get("suggestions").get(0).get("id").asText();
        mockMvc.perform(post("/api/v1/inventory/restock/runs/" + runId + "/accept")
                        .contentType(APPLICATION_JSON)
                        .content("{\"mode\":\"all\",\"qtyOverrides\":{\"" + suggestionId
                                + "\":0}}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isBadRequest());
    }

    private JsonNode acceptAll(String runId) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/inventory/restock/runs/" + runId + "/accept")
                                .contentType(APPLICATION_JSON)
                                .content("{\"mode\":\"all\"}")
                                .header("X-Tenant-Id", TENANT)
                                .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Second item WITHOUT a supplier link → the run gets a {@code target=pad} line. */
    private String seedSecondItemNoSupplier(String sku, String name) {
        String goodsTypeId =
                itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(TENANT).getFirst().getId();
        String id = itemCatalogService
                .createItem(
                        TENANT,
                        new CreateItemRequest(
                                sku, null, name, null, goodsTypeId, null, null,
                                null, false, true, true, null, null, null, null, null,
                                null, null, null, null, null, false, null, null, null, null),
                        null)
                .body()
                .id();
        var item = itemRepository.findById(id).orElseThrow();
        item.setMinStockLevel(new BigDecimal("5"));
        item.setReorderLevel(new BigDecimal("5"));
        item.setBuyingPrice(new BigDecimal("30.0000"));
        itemRepository.save(item);

        InventoryBatch batch = new InventoryBatch();
        batch.setId(UUID.randomUUID().toString());
        batch.setBusinessId(TENANT);
        batch.setBranchId(branchId);
        batch.setItemId(id);
        batch.setBatchNumber("DIGEST-PAD-B1");
        batch.setSourceType("test");
        batch.setSourceId("source-pad-1");
        BigDecimal qty = new BigDecimal("3");
        batch.setInitialQuantity(qty);
        batch.setQuantityRemaining(qty);
        batch.setUnitCost(new BigDecimal("30.0000"));
        batch.setReceivedAt(Instant.parse("2026-04-01T12:00:00Z"));
        batch.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
        inventoryBatchRepository.save(batch);

        seedSale(id, "idem-digest-pad");
        return id;
    }

    private JsonNode generate() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/inventory/restock/runs/generate")
                                .param("branchId", branchId)
                                .header("X-Tenant-Id", TENANT)
                                .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void seedSaleYesterday(String soldItemId) {
        seedSale(soldItemId, "idem-digest-yesterday");
    }

    private void seedSale(String soldItemId, String idemKey) {
        LocalDate soldOn = runDate.minusDays(1);
        Instant soldAt = soldOn.atTime(14, 30).toInstant(ZoneOffset.UTC);

        Sale sale = new Sale();
        sale.setBusinessId(TENANT);
        sale.setBranchId(branchId);
        sale.setShiftId("shift-digest");
        sale.setStatus(SalesConstants.SALE_STATUS_COMPLETED);
        sale.setIdempotencyKey(idemKey);
        sale.setGrandTotal(new BigDecimal("100.00"));
        sale.setSoldBy(owner.getId());
        sale.setSoldAt(soldAt);
        saleRepository.save(sale);

        SaleItem line = new SaleItem();
        line.setSaleId(sale.getId());
        line.setLineIndex(0);
        line.setItemId(soldItemId);
        line.setQuantity(new BigDecimal("2"));
        line.setUnitPrice(new BigDecimal("50"));
        line.setLineTotal(new BigDecimal("100"));
        line.setUnitCost(new BigDecimal("30"));
        line.setCostTotal(new BigDecimal("60"));
        line.setProfit(new BigDecimal("40"));
        saleItemRepository.save(line);
    }

    private void grant(String roleId, String permissionId) {
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(roleId, permissionId));
        rolePermissionRepository.save(rp);
    }

    private User user(String email, String roleId) {
        User u = new User();
        u.setBusinessId(TENANT);
        u.setEmail(email);
        u.setName("U");
        u.setRoleId(roleId);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(u);
        return u;
    }
}
