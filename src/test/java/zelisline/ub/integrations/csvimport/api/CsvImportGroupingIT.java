package zelisline.ub.integrations.csvimport.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.media.MediaStore;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Integration tests for the {@code groupByName} variant-grouping option on the items CSV import
 * ({@code POST /api/v1/integrations/imports/items}) and for the now-optional blank SKU.
 *
 * <p>Harness copied from {@link CsvImportIT}: @SpringBootTest + MockMvc, schema generated from
 * entities ({@code create-drop}), a Business/Branch/User/Role/Permissions seeded in @BeforeEach,
 * and the endpoint called via multipart with the tenant + test-auth headers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class CsvImportGroupingIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbd3";
    private static final String OTHER_TENANT = "cccccccc-cccc-cccc-cccc-ccccccccccd4";
    private static final String ROLE = "22222222-0000-0000-0000-0000000000e1";
    private static final String P_IMPORT = "11111111-0000-0000-0000-000000000107";
    private static final String P_ITEMS_READ = "11111111-0000-0000-0000-000000000108";
    private static final String P_ITEMS_WRITE = "11111111-0000-0000-0000-000000000109";
    private static final String P_LINK_SUPPLIERS = "11111111-0000-0000-0000-000000000110";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private BranchRepository branchRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private RolePermissionRepository rolePermissionRepository;
    @Autowired
    private CatalogBootstrapService catalogBootstrapService;
    @Autowired
    private ItemRepository itemRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private MediaStore mediaStore;

    private User user;

    @BeforeEach
    void seed() {
        itemRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Group Co");
        b.setSlug("group-co");
        businessRepository.save(b);

        Branch br = new Branch();
        br.setBusinessId(TENANT);
        br.setName("Main");
        branchRepository.save(br);

        permissionRepository.save(perm(P_IMPORT, "integrations.imports.manage", "csv"));
        permissionRepository.save(perm(P_ITEMS_READ, "catalog.items.read", "items"));
        permissionRepository.save(perm(P_ITEMS_WRITE, "catalog.items.write", "items"));
        permissionRepository.save(perm(P_LINK_SUPPLIERS, "catalog.items.link_suppliers", "items"));

        Role role = new Role();
        role.setId(ROLE);
        role.setBusinessId(null);
        role.setRoleKey("group_tester");
        role.setName("Group Tester");
        role.setSystem(true);
        roleRepository.save(role);

        for (String pid : List.of(P_IMPORT, P_ITEMS_READ, P_ITEMS_WRITE, P_LINK_SUPPLIERS)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE, pid));
            rolePermissionRepository.save(rp);
        }

        user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("csv-group-it@test");
        user.setName("CSV Group IT");
        user.setRoleId(ROLE);
        user.setBranchId(br.getId());
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(user);

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
    }

    // a. Blank SKU row imports successfully and the catalog auto-allocates a non-blank SKU.
    @Test
    void blankSku_importsAndAutoAllocatesSku() throws Exception {
        String csv = """
                sku,name,item_type_key
                ,No Sku Item,goods
                """;
        uploadItems(csv, false, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andExpect(jsonPath("$.rowsCommitted").value(1));

        Item created = itemByName(TENANT, "No Sku Item");
        assertThat(created.getSku()).isNotBlank();
        assertThat(created.getVariantOfItemId()).isNull();
    }

    // b. Three distinct variant names + groupByName=true → one non-sellable parent with 3 children,
    //    each child re-parented with the right variantName and keeping its own stock.
    @Test
    void threeDistinctVariants_groupByName_createsOneParentWithThreeChildren() throws Exception {
        String csv = """
                sku,name,item_type_key,category_name,variant_name,is_stocked,stock
                BAG-S,Maya HC,goods,Bag,Small,true,5
                BAG-M,Maya HC,goods,Bag,Medium,true,7
                BAG-L,Maya HC,goods,Bag,Large,true,9
                """;
        uploadItems(csv, false, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsCommitted").value(3));

        Item small = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "BAG-S").orElseThrow();
        Item medium = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "BAG-M").orElseThrow();
        Item large = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "BAG-L").orElseThrow();

        // All three children share one parent id.
        String parentId = small.getVariantOfItemId();
        assertThat(parentId).isNotNull();
        assertThat(medium.getVariantOfItemId()).isEqualTo(parentId);
        assertThat(large.getVariantOfItemId()).isEqualTo(parentId);

        // The parent: non-sellable, unstocked, no parent of its own, named after the family.
        Item parent = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(parentId, TENANT).orElseThrow();
        assertThat(parent.getVariantOfItemId()).isNull();
        assertThat(parent.isSellable()).isFalse();
        assertThat(parent.isStocked()).isFalse();
        assertThat(parent.getName()).isEqualTo("Maya HC");

        // Exactly three children attached to the parent.
        List<Item> children = itemRepository
                .findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(TENANT, parentId);
        assertThat(children).extracting(Item::getSku)
                .containsExactlyInAnyOrder("BAG-S", "BAG-M", "BAG-L");

        // Each child keeps its own variantName and its own stock.
        assertThat(small.getVariantName()).isEqualTo("Small");
        assertThat(medium.getVariantName()).isEqualTo("Medium");
        assertThat(large.getVariantName()).isEqualTo("Large");
        assertThat(small.getCurrentStock().stripTrailingZeros().toPlainString()).isEqualTo("5");
        assertThat(medium.getCurrentStock().stripTrailingZeros().toPlainString()).isEqualTo("7");
        assertThat(large.getCurrentStock().stripTrailingZeros().toPlainString()).isEqualTo("9");
    }

    // c. Name case/space drift still groups under a single parent.
    @Test
    void nameCaseAndSpaceDrift_groupsUnderSingleParent() throws Exception {
        String csv = """
                sku,name,item_type_key,category_name,variant_name
                DRIFT-1,Maya HC,goods,Bag,Small
                DRIFT-2,Maya Hc ,goods,Bag,Large
                """;
        uploadItems(csv, false, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsCommitted").value(2));

        Item one = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "DRIFT-1").orElseThrow();
        Item two = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "DRIFT-2").orElseThrow();

        assertThat(one.getVariantOfItemId()).isNotNull();
        assertThat(two.getVariantOfItemId()).isEqualTo(one.getVariantOfItemId());

        List<Item> children = itemRepository
                .findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(
                        TENANT, one.getVariantOfItemId());
        assertThat(children).hasSize(2);
    }

    // d. Price-only duplicates (same category+name+variant_name) stay standalone — one distinct label.
    @Test
    void priceOnlyDuplicates_sameVariantName_stayStandalone() throws Exception {
        String csv = """
                sku,name,item_type_key,category_name,variant_name,price
                CRD-1,Airtel 50,goods,Credit,Airtel,50.00
                CRD-2,Airtel 50,goods,Credit,Airtel,55.00
                CRD-3,Airtel 50,goods,Credit,Airtel,60.00
                """;
        uploadItems(csv, false, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsCommitted").value(3));

        for (String sku : List.of("CRD-1", "CRD-2", "CRD-3")) {
            Item item = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, sku).orElseThrow();
            assertThat(item.getVariantOfItemId())
                    .as("row %s must remain standalone (no parent)", sku)
                    .isNull();
        }
        // No parent item exists for this family.
        assertThat(itemByNameOrNull(TENANT, "Airtel 50").getVariantOfItemId()).isNull();
    }

    // d2. Mixed bucket: labelled rows form a family, a blank-variant row stays standalone.
    //     Guards the code-review fix — blank-label members are never pulled in under a fallback label.
    @Test
    void mixedBucket_blankVariantRowStaysStandalone_labelledRowsGroup() throws Exception {
        String csv = """
                sku,name,item_type_key,category_name,variant_name,is_stocked,stock
                MIX-A,Coke,goods,Soda,500ml,true,5
                MIX-B,Coke,goods,Soda,1L,true,7
                MIX-C,Coke,goods,Soda,,true,9
                """;
        uploadItems(csv, false, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsCommitted").value(3));

        Item a = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "MIX-A").orElseThrow();
        Item b = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "MIX-B").orElseThrow();
        Item c = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "MIX-C").orElseThrow();

        // The two labelled rows are grouped under one parent.
        String parentId = a.getVariantOfItemId();
        assertThat(parentId).isNotNull();
        assertThat(b.getVariantOfItemId()).isEqualTo(parentId);

        // The blank-label row stays standalone — not attached, and not labelled "Option N".
        assertThat(c.getVariantOfItemId()).as("blank-variant row must stay standalone").isNull();
        assertThat(c.getVariantName()).isNull();

        // Exactly the two labelled children hang off the parent.
        List<Item> children = itemRepository
                .findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(TENANT, parentId);
        assertThat(children).extracting(Item::getSku).containsExactlyInAnyOrder("MIX-A", "MIX-B");
    }

    // e. Single-row product with groupByName=true stays standalone.
    @Test
    void singleRow_groupByName_staysStandalone() throws Exception {
        String csv = """
                sku,name,item_type_key,category_name,variant_name
                SOLO-1,Lonely HC,goods,Bag,Small
                """;
        uploadItems(csv, false, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsCommitted").value(1));

        Item item = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "SOLO-1").orElseThrow();
        assertThat(item.getVariantOfItemId()).isNull();
    }

    // f. groupByName defaults off: multi-variant CSV without the param stays flat (opt-in proof).
    @Test
    void groupByNameOff_multiVariantStaysFlat() throws Exception {
        String csv = """
                sku,name,item_type_key,category_name,variant_name
                OFF-S,Flat HC,goods,Bag,Small
                OFF-M,Flat HC,goods,Bag,Medium
                OFF-L,Flat HC,goods,Bag,Large
                """;
        // No groupByName param at all — proving the behaviour is opt-in.
        uploadItemsNoGroupParam(csv)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsCommitted").value(3));

        for (String sku : List.of("OFF-S", "OFF-M", "OFF-L")) {
            Item item = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, sku).orElseThrow();
            assertThat(item.getVariantOfItemId())
                    .as("row %s must stay flat when groupByName is off", sku)
                    .isNull();
        }
    }

    // g. Dry-run with groupByName=true: 0 errors and a warning starting with "grouping on:".
    @Test
    void dryRun_groupByName_returnsGroupingSummaryWarning() throws Exception {
        String csv = """
                sku,name,item_type_key,category_name,variant_name
                DRY-S,Dry HC,goods,Bag,Small
                DRY-M,Dry HC,goods,Bag,Medium
                DRY-L,Dry HC,goods,Bag,Large
                """;
        uploadItems(csv, true, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("grouping on:"));
    }

    // h. Tenant isolation: items imported into TENANT are invisible to a second business id.
    @Test
    void tenantIsolation_otherBusinessSeesNoImportedItems() throws Exception {
        String csv = """
                sku,name,item_type_key,category_name,variant_name
                ISO-S,Iso HC,goods,Bag,Small
                ISO-M,Iso HC,goods,Bag,Medium
                """;
        uploadItems(csv, false, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsCommitted").value(2));

        // Present for the importing tenant.
        assertThat(itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "ISO-S")).isPresent();
        assertThat(itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "ISO-M")).isPresent();

        // Invisible to the other business, by SKU and in bulk.
        assertThat(itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(OTHER_TENANT, "ISO-S")).isEmpty();
        assertThat(itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(OTHER_TENANT, "ISO-M")).isEmpty();
        assertThat(itemRepository.findByBusinessIdAndDeletedAtIsNull(OTHER_TENANT)).isEmpty();
    }

    // i. An actual .xlsx upload (ZIP/PK magic) is parsed by POI the same as CSV: headers map,
    //    typed numeric cells render as text, and groupByName still forms the family.
    @Test
    void xlsxUpload_parsesAndGroupsLikeCsv() throws Exception {
        String[] headers = {"sku", "name", "item_type_key", "category_name", "variant_name", "is_stocked", "stock"};
        Object[][] rows = {
                {"XL-S", "Xlsx HC", "goods", "Bag", "Small", "true", 5d},
                {"XL-M", "Xlsx HC", "goods", "Bag", "Medium", "true", 7d},
        };
        byte[] xlsx = buildXlsx(headers, rows);

        MockMultipartFile file = new MockMultipartFile(
                "file", "inventory.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);
        mockMvc.perform(multipart("/api/v1/integrations/imports/items")
                        .file(file)
                        .param("dryRun", "false")
                        .param("groupByName", "true")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsCommitted").value(2));

        Item s = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "XL-S").orElseThrow();
        Item m = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "XL-M").orElseThrow();
        // Numeric stock cell (5.0 / 7.0) rendered as text and parsed as a quantity.
        assertThat(s.getCurrentStock().stripTrailingZeros().toPlainString()).isEqualTo("5");
        // Both rows grouped under one parent.
        assertThat(s.getVariantOfItemId()).isNotNull();
        assertThat(m.getVariantOfItemId()).isEqualTo(s.getVariantOfItemId());
        assertThat(s.getVariantName()).isEqualTo("Small");
    }

    private static byte[] buildXlsx(String[] headers, Object[][] rows) throws Exception {
        try (org.apache.poi.ss.usermodel.Workbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("inventory");
            org.apache.poi.ss.usermodel.Row head = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) {
                head.createCell(c).setCellValue(headers[c]);
            }
            for (int r = 0; r < rows.length; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    org.apache.poi.ss.usermodel.Cell cell = row.createCell(c);
                    if (v instanceof Double d) {
                        cell.setCellValue(d);
                    } else {
                        cell.setCellValue(String.valueOf(v));
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    // j. The raw inventory sheet's own headers (ITEM_NAME / CATEGORY / ITEM_TYPE / VARIANT_NAME /
    //    PRICE / COST_PRICE / STOCK, no SKU) import with no reshaping: header case is normalized,
    //    item_name→name and category→category_name aliases apply, ITEM_TYPE "Unit" is ignored
    //    (defaults to goods), SKUs auto-allocate, and the two variants group.
    @Test
    void rawSheetHeaders_importWithAliasesAndGrouping() throws Exception {
        // Mirrors inventory1.xlsx exactly: uppercase headers, ITEM_TYPE=Unit, blank SKU/BARCODE.
        String[] headers = {"CATEGORY", "ITEM_TYPE", "ITEM_NAME", "VARIANT_NAME", "PRICE", "COST_PRICE", "STOCK", "BARCODE", "SKU"};
        Object[][] rows = {
                {"Bag", "Unit", "Maya HC", "Xl", 460d, 440d, 3d, "", ""},
                {"Bag", "Unit", "Maya HC", "Medium", 460d, 440d, 4d, "", ""},
        };
        byte[] xlsx = buildXlsx(headers, rows);

        MockMultipartFile file = new MockMultipartFile(
                "file", "inventory1.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);
        mockMvc.perform(multipart("/api/v1/integrations/imports/items")
                        .file(file)
                        .param("dryRun", "false")
                        .param("groupByName", "true")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andExpect(jsonPath("$.rowsCommitted").value(2));

        // ITEM_NAME alias resolved both rows to one product: a single "Maya HC" family parent
        // (the children's stored names become composed display names once attached).
        List<Item> parents = itemRepository.findByBusinessIdAndDeletedAtIsNull(TENANT).stream()
                .filter(i -> "Maya HC".equals(i.getName()) && i.getVariantOfItemId() == null)
                .toList();
        assertThat(parents).hasSize(1);
        Item parent = parents.get(0);

        List<Item> children = itemRepository
                .findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(TENANT, parent.getId());
        assertThat(children).hasSize(2);
        // SKUs were auto-allocated (sheet had none).
        assertThat(children).allSatisfy(i -> assertThat(i.getSku()).isNotBlank());
        assertThat(children).extracting(Item::getVariantName).containsExactlyInAnyOrder("Xl", "Medium");
        // CATEGORY alias created/linked the category.
        assertThat(parent.getCategoryId()).isNotNull();
    }

    private Item itemByName(String businessId, String name) {
        Item found = itemByNameOrNull(businessId, name);
        assertThat(found).as("expected an item named '%s' for business %s", name, businessId).isNotNull();
        return found;
    }

    private Item itemByNameOrNull(String businessId, String name) {
        return itemRepository.findByBusinessIdAndDeletedAtIsNull(businessId).stream()
                .filter(i -> name.equals(i.getName()))
                .findFirst()
                .orElse(null);
    }

    private ResultActions uploadItems(String csv, boolean dryRun, boolean groupByName) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "items.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        return mockMvc.perform(multipart("/api/v1/integrations/imports/items")
                .file(file)
                .param("dryRun", Boolean.toString(dryRun))
                .param("groupByName", Boolean.toString(groupByName))
                .header("X-Tenant-Id", TENANT)
                .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                .contentType(MULTIPART_FORM_DATA));
    }

    private ResultActions uploadItemsNoGroupParam(String csv) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "items.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        return mockMvc.perform(multipart("/api/v1/integrations/imports/items")
                .file(file)
                .param("dryRun", "false")
                .header("X-Tenant-Id", TENANT)
                .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                .contentType(MULTIPART_FORM_DATA));
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
