package zelisline.ub.integrations.csvimport.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

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

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

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

        Role role = new Role();
        role.setId(ROLE);
        role.setBusinessId(null);
        role.setRoleKey("import_tester");
        role.setName("Import Tester");
        role.setSystem(true);
        roleRepository.save(role);

        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(ROLE, P_IMPORT));
        rolePermissionRepository.save(rp);

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
        uploadItems("""
                sku,name,item_type_key,barcode,unit_type,is_stocked,is_sellable,selling_price,reorder_level
                SKU-EXP-1,Export One,goods,1234567890123,each,true,true,99.50,5
                """, false);

        mockMvc.perform(get("/api/v1/integrations/imports/exports/items")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(body).contains(
                            "sku,name,item_type_key,barcode,unit_type,is_stocked,is_sellable,selling_price,reorder_level");
                    assertThat(body).contains("SKU-EXP-1");
                    assertThat(body).contains("Export One");
                    assertThat(body).contains("99.50");
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
