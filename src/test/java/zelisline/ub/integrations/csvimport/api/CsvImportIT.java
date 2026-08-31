package zelisline.ub.integrations.csvimport.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.media.MediaStore;
import zelisline.ub.pricing.domain.BuyingPrice;
import zelisline.ub.pricing.repository.BuyingPriceRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class CsvImportIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbd3";
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
    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private SupplierProductRepository supplierProductRepository;
    @Autowired
    private BuyingPriceRepository buyingPriceRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @MockitoBean
    private MediaStore mediaStore;

    private User user;

    @BeforeEach
    void seed() {
        supplierRepository.deleteAll();
        itemRepository.deleteAll();
        userRepository.deleteAll();
        rolePermissionRepository.deleteAll();
        roleRepository.deleteAll();
        permissionRepository.deleteAll();
        branchRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Import Co");
        b.setSlug("import-co");
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
        role.setRoleKey("import_tester");
        role.setName("Import Tester");
        role.setSystem(true);
        roleRepository.save(role);

        for (String pid : List.of(P_IMPORT, P_ITEMS_READ, P_ITEMS_WRITE, P_LINK_SUPPLIERS)) {
            RolePermission rp = new RolePermission();
            rp.setId(new RolePermission.Id(ROLE, pid));
            rolePermissionRepository.save(rp);
        }

        user = new User();
        user.setBusinessId(TENANT);
        user.setEmail("csv-import-it@test");
        user.setName("CSV Import IT");
        user.setRoleId(ROLE);
        user.setBranchId(br.getId());
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        userRepository.save(user);

        catalogBootstrapService.seedDefaultItemTypesIfMissing(TENANT);
    }

    @Test
    void templateItems_containsExpectedHeader() throws Exception {
        mockMvc.perform(get("/api/v1/integrations/imports/templates/items")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("sku,name,item_type_key"));
    }

    @Test
    void exportItems_matchesTemplateHeaderAndIncludesImportedRows() throws Exception {
        uploadSuppliers("""
                name,code,supplier_type,vat_pin,status,notes
                Export Vendor,EV1,distributor,P123,active,hello
                """, false);
        uploadItems("""
                sku,name,item_type_key,barcode,unit_type,is_stocked,is_sellable,category_name,brand,size,buying_price,selling_price,on_hand,min_stock_level,reorder_level,supplier_name,image_url
                SKU-EXP-1,Export One,goods,1234567890123,each,true,true,Drinks,Afia,1L,40.00,99.50,12,2,5,Export Vendor,https://example.com/img.png
                """, false);

        mockMvc.perform(get("/api/v1/integrations/imports/exports/items")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(body).contains(
                            "sku,name,item_type_key,barcode,unit_type,is_stocked,is_sellable,category_name,brand,size,buying_price,selling_price,on_hand,min_stock_level,reorder_level,supplier_name,supplier_code,image_url");
                    assertThat(body).contains("SKU-EXP-1");
                    assertThat(body).contains("Export One");
                    assertThat(body).contains("40.00");
                    assertThat(body).contains("99.50");
                    assertThat(body).contains("12.0000");
                    assertThat(body).contains("Export Vendor");
                    assertThat(body).contains("https://example.com/img.png");
                });
    }

    @Test
    void exportSuppliers_includesCommittedSupplier() throws Exception {
        uploadSuppliers("""
                name,code,supplier_type,vat_pin,status,notes
                Export Vendor,EV1,distributor,P123,active,hello
                """, false);

        mockMvc.perform(get("/api/v1/integrations/imports/exports/suppliers")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(body).contains("name,code,supplier_type,vat_pin,status,notes");
                    assertThat(body).contains("Export Vendor");
                    assertThat(body).contains("EV1");
                });
    }

    @Test
    void exportOpeningStock_includesOnHandAfterImport() throws Exception {
        uploadItems("""
                sku,name,item_type_key,is_stocked
                SKU-OS-1,Opening Export,goods,true
                """, false);

        MockMultipartFile openingFile = new MockMultipartFile(
                "file",
                "opening.csv",
                "text/csv",
                """
                        branch_name,sku,quantity,unit_cost
                        Main,SKU-OS-1,7,2.25
                        """.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/integrations/imports/opening-stock")
                        .file(openingFile)
                        .param("dryRun", "false")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/integrations/imports/exports/opening-stock")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(body).contains("branch_name,sku,quantity,unit_cost,notes");
                    assertThat(body).contains("Main");
                    assertThat(body).contains("SKU-OS-1");
                    assertThat(body).contains("7.0000");
                    assertThat(body).contains("2.25");
                });
    }

    @Test
    void itemsDryRun_duplicateSkuInFile_returnsErrors() throws Exception {
        String csv = """
                sku,name,item_type_key
                dupe,Name A,goods
                dupe,Name B,goods
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "items.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/integrations/imports/items")
                        .file(file)
                        .param("dryRun", "true")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.errors.length()", greaterThan(0)));
    }

    @Test
    void itemsCommit_thenOpeningStock_increasesOnHand() throws Exception {
        assertThat(itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "SKU-CSV-1")).isEmpty();

        String itemsCsv = """
                sku,name,item_type_key,is_stocked
                SKU-CSV-1,Imported One,goods,true
                SKU-CSV-2,Imported Two,goods,true
                """;
        uploadItems(itemsCsv, false);
        assertThat(itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "SKU-CSV-1")).isPresent();

        String openingCsv = """
                branch_name,sku,quantity,unit_cost
                Main,SKU-CSV-1,12,3.50
                """;
        MockMultipartFile openingFile = new MockMultipartFile(
                "file",
                "opening.csv",
                "text/csv",
                openingCsv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/integrations/imports/opening-stock")
                        .file(openingFile)
                        .param("dryRun", "false")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsCommitted").value(1));

        var item = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "SKU-CSV-1").orElseThrow();
        assertThat(item.getCurrentStock().stripTrailingZeros().toPlainString()).isEqualTo("12");
    }

    @Test
    void itemsCommit_supplierColumn_linksMatchingSupplierAndWarnsForUnknown() throws Exception {
        uploadSuppliers("""
                name,code
                Acme Vendor,ACME1
                """, false);

        String csv = """
                supplier_name,sku,name,item_type_key,is_stocked,is_sellable
                Acme Vendor,SKU-SUP-1,Linked One,goods,true,true
                Unknown Co,SKU-SUP-2,Unlinked Two,goods,true,true
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "items.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/integrations/imports/items")
                        .file(file)
                        .param("dryRun", "false")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsCommitted").value(2))
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("Unknown Co"));

        Supplier acme = supplierRepository
                .findByBusinessIdAndCodeAndDeletedAtIsNull(TENANT, "ACME1").orElseThrow();
        Supplier unassigned = supplierRepository
                .findByBusinessIdAndCodeAndDeletedAtIsNull(TENANT, "SYS-UNASSIGNED").orElseThrow();

        // Matched supplier: linked as the only active primary link; the synthetic
        // SYS-UNASSIGNED link is retired so the item leaves "Suppliers Not Linked".
        Item linked = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "SKU-SUP-1")
                .orElseThrow();
        List<SupplierProduct> linkedLinks = supplierProductRepository.listForItem(TENANT, linked.getId());
        assertThat(linkedLinks).extracting(SupplierProduct::getSupplierId)
                .containsExactly(acme.getId());
        assertThat(linkedLinks).allMatch(SupplierProduct::isActive);
        assertThat(linkedLinks).anyMatch(SupplierProduct::isPrimaryLink);

        // Unknown supplier: imports unlinked and stays under "Suppliers Not Linked".
        Item unlinked = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "SKU-SUP-2")
                .orElseThrow();
        List<SupplierProduct> unlinkedLinks = supplierProductRepository.listForItem(TENANT, unlinked.getId());
        assertThat(unlinkedLinks).hasSize(1);
        assertThat(unlinkedLinks.get(0).getSupplierId()).isEqualTo(unassigned.getId());
    }

    @Test
    void itemsDryRun_supplierColumn_unknownSupplierReportsWarning() throws Exception {
        String csv = """
                supplier_code,sku,name,item_type_key
                NOPE,SKU-DR-1,Dry Warn One,goods
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "items.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/integrations/imports/items")
                        .file(file)
                        .param("dryRun", "true")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("NOPE"));
    }

    @Test
    void linkToRealSupplier_migratesUnassignedBuyingPrice() throws Exception {
        uploadItems("""
                sku,name,item_type_key
                SKU-BP-1,Bp One,goods
                """, false);
        Item item = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "SKU-BP-1")
                .orElseThrow();
        Supplier unassigned = supplierRepository
                .findByBusinessIdAndCodeAndDeletedAtIsNull(TENANT, "SYS-UNASSIGNED").orElseThrow();

        // Legacy-style open-ended buying price attached to the unassigned supplier.
        BuyingPrice bp = new BuyingPrice();
        bp.setBusinessId(TENANT);
        bp.setItemId(item.getId());
        bp.setSupplierId(unassigned.getId());
        bp.setUnitCost(new BigDecimal("12.5000"));
        bp.setEffectiveFrom(LocalDate.now());
        bp.setSourceType("legacy_json");
        bp.setNotes("legacy cost");
        buyingPriceRepository.save(bp);

        uploadSuppliers("""
                name,code
                Acme Vendor,ACME1
                """, false);
        Supplier acme = supplierRepository
                .findByBusinessIdAndCodeAndDeletedAtIsNull(TENANT, "ACME1").orElseThrow();

        mockMvc.perform(post("/api/v1/items/" + item.getId() + "/supplier-links")
                        .contentType(APPLICATION_JSON)
                        .content("{\"supplierId\":\"" + acme.getId() + "\",\"setPrimary\":true}")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE))
                .andExpect(status().isCreated());

        // The active buying price re-pointed to the real supplier, with a move note.
        List<BuyingPrice> moved = buyingPriceRepository.findOpenEnded(TENANT, item.getId(), acme.getId());
        assertThat(moved).hasSize(1);
        assertThat(moved.get(0).getUnitCost()).isEqualByComparingTo("12.5000");
        assertThat(moved.get(0).getNotes()).contains("Suppliers Not Linked");
        assertThat(buyingPriceRepository.findOpenEnded(TENANT, item.getId(), unassigned.getId())).isEmpty();
    }

    @Test
    void setPrimaryLinkOnRealLink_demotesSystemUnassignedAndMigratesPrice() throws Exception {
        uploadItems("""
                sku,name,item_type_key
                SKU-PR-1,Primary One,goods
                """, false);
        Item item = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "SKU-PR-1")
                .orElseThrow();
        Supplier unassigned = supplierRepository
                .findByBusinessIdAndCodeAndDeletedAtIsNull(TENANT, "SYS-UNASSIGNED").orElseThrow();

        // Item sits in the "Suppliers Not Linked" bucket: the synthetic unassigned link is primary.
        SupplierProduct unassignedLink = new SupplierProduct();
        unassignedLink.setSupplierId(unassigned.getId());
        unassignedLink.setItemId(item.getId());
        unassignedLink.setPrimaryLink(true);
        unassignedLink.setActive(true);
        supplierProductRepository.save(unassignedLink);

        // Legacy-style open-ended buying price attached to the unassigned supplier.
        BuyingPrice bp = new BuyingPrice();
        bp.setBusinessId(TENANT);
        bp.setItemId(item.getId());
        bp.setSupplierId(unassigned.getId());
        bp.setUnitCost(new BigDecimal("8.7500"));
        bp.setEffectiveFrom(LocalDate.now());
        bp.setSourceType("legacy_json");
        bp.setNotes("legacy cost");
        buyingPriceRepository.save(bp);

        uploadSuppliers("""
                name,code
                Prime Vendor,PR1
                """, false);
        Supplier prime = supplierRepository
                .findByBusinessIdAndCodeAndDeletedAtIsNull(TENANT, "PR1").orElseThrow();

        // Real supplier already linked but not primary — the stuck state from the bucket page.
        SupplierProduct primeLink = new SupplierProduct();
        primeLink.setSupplierId(prime.getId());
        primeLink.setItemId(item.getId());
        primeLink.setPrimaryLink(false);
        primeLink.setActive(true);
        supplierProductRepository.save(primeLink);

        mockMvc.perform(post("/api/v1/items/" + item.getId() + "/supplier-links/" + primeLink.getId() + "/set-primary")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE))
                .andExpect(status().isNoContent());

        // The unassigned link is retired so the item leaves the bucket.
        SupplierProduct unassignedAfter = supplierProductRepository.findById(unassignedLink.getId()).orElseThrow();
        assertThat(unassignedAfter.isActive()).isFalse();
        assertThat(unassignedAfter.getDeletedAt()).isNotNull();
        assertThat(unassignedAfter.isPrimaryLink()).isFalse();
        SupplierProduct primeAfter = supplierProductRepository.findById(primeLink.getId()).orElseThrow();
        assertThat(primeAfter.isPrimaryLink()).isTrue();

        // The open-ended price was re-pointed to the real supplier.
        List<BuyingPrice> moved = buyingPriceRepository.findOpenEnded(TENANT, item.getId(), prime.getId());
        assertThat(moved).hasSize(1);
        assertThat(moved.get(0).getUnitCost()).isEqualByComparingTo("8.7500");
        assertThat(moved.get(0).getNotes()).contains("Suppliers Not Linked");
        assertThat(buyingPriceRepository.findOpenEnded(TENANT, item.getId(), unassigned.getId())).isEmpty();
    }

    @Test
    void bulkImageImport_setsImagesBySkuAndReportsIssues() throws Exception {
        uploadItems("""
                sku,name,item_type_key
                IMG-SKU-1,Img One,goods
                IMG-SKU-2,Img Two,goods
                """, false);

        String csv = """
                sku,image_url
                IMG-SKU-1,https://example.com/a.png
                IMG-SKU-2,
                IMG-SKU-9,https://example.com/missing.png
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "imgs.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/items/images/import")
                        .file(file)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsParsed").value(3))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.notFound.length()").value(1))
                .andExpect(jsonPath("$.invalid.length()").value(1));

        Item item = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "IMG-SKU-1")
                .orElseThrow();
        assertThat(item.getImageKey()).isEqualTo("https://example.com/a.png");
    }

    @Test
    void bulkImageUpload_skuNamedFiles_setsCoversAndReportsIssues() throws Exception {
        when(mediaStore.isConfigured()).thenReturn(true);
        when(mediaStore.uploadImage(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn(new CloudinaryUploadResult(
                        "items/pid-1",
                        "https://res.cloudinary.com/x/image/upload/items/pid-1",
                        100, 100, 2048L, "png", "image/png", "v1", "#ffffff", null));

        uploadItems("""
                sku,name,item_type_key
                IMG-F-1,File One,goods
                IMG-F-2,File Two,goods
                """, false);

        MockMultipartFile good = new MockMultipartFile(
                "files", "IMG-F-1.png", "image/png", new byte[]{1, 2, 3});
        MockMultipartFile unknown = new MockMultipartFile(
                "files", "IMG-F-9.png", "image/png", new byte[]{1});
        MockMultipartFile notImage = new MockMultipartFile(
                "files", "IMG-F-2.txt", "text/plain", new byte[]{1});

        mockMvc.perform(multipart("/api/v1/items/images/upload-bulk")
                        .file(good)
                        .file(unknown)
                        .file(notImage)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowsParsed").value(3))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.notFound.length()").value(1))
                .andExpect(jsonPath("$.invalid.length()").value(1));

        Item item = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "IMG-F-1")
                .orElseThrow();
        assertThat(item.getImageKey()).isEqualTo("https://res.cloudinary.com/x/image/upload/items/pid-1");
    }

    @Test
    void bulkImageUpload_midBatchFailure_rollsBackAndDestroysUploadedAssets() throws Exception {
        when(mediaStore.isConfigured()).thenReturn(true);
        when(mediaStore.uploadImage(any(byte[].class), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String filename = inv.getArgument(1);
                    if (filename.startsWith("IMG-FB-BAD")) {
                        throw new RuntimeException("boom");
                    }
                    return new CloudinaryUploadResult(
                            "items/pid-ok",
                            "https://res.cloudinary.com/x/image/upload/items/pid-ok",
                            100, 100, 2048L, "png", "image/png", "v1", "#ffffff", null);
                });

        uploadItems("""
                sku,name,item_type_key
                IMG-FB-1,Bad Batch One,goods
                IMG-FB-BAD,Bad Batch Two,goods
                """, false);

        MockMultipartFile good = new MockMultipartFile(
                "files", "IMG-FB-1.png", "image/png", new byte[]{1});
        MockMultipartFile bad = new MockMultipartFile(
                "files", "IMG-FB-BAD.png", "image/png", new byte[]{1});

        mockMvc.perform(multipart("/api/v1/items/images/upload-bulk")
                        .file(good)
                        .file(bad)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().is5xxServerError());

        // The first file's asset was uploaded before the batch rolled back — it must be destroyed.
        verify(mediaStore).destroyImage("items/pid-ok");

        // Rollback also means no item received the image.
        Item item = itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(TENANT, "IMG-FB-1")
                .orElseThrow();
        assertThat(item.getImageKey()).isNull();
    }

    @Test
    void suppliersCommit_duplicateNameSecondCommit_returns400() throws Exception {
        String csv = """
                name,code
                Vendor CSV,VEND1
                """;
        uploadSuppliers(csv, false);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sup.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/integrations/imports/suppliers")
                        .file(file)
                        .param("dryRun", "false")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()", greaterThan(0)));
    }

    private void uploadItems(String csv, boolean dryRun) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "items.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/integrations/imports/items")
                        .file(file)
                        .param("dryRun", Boolean.toString(dryRun))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().isOk());
    }

    private void uploadSuppliers(String csv, boolean dryRun) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sup.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/integrations/imports/suppliers")
                        .file(file)
                        .param("dryRun", Boolean.toString(dryRun))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE)
                        .contentType(MULTIPART_FORM_DATA))
                .andExpect(status().isOk());
    }

    private static Permission perm(String id, String key, String desc) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(desc);
        return p;
    }
}
