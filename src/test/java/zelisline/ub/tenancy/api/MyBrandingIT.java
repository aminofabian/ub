package zelisline.ub.tenancy.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.identity.PermissionCacheProbeController;
import zelisline.ub.identity.domain.Permission;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.RolePermission;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.RolePermissionRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.media.CloudinaryImageService;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Verifies tenant self-service branding: JSON patch (display name, colors)
 * and multipart logo upload via the {@code /api/v1/businesses/me/branding}
 * surface. Cloudinary is mocked so the test never touches the network.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PermissionCacheProbeController.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class MyBrandingIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String PERM_MANAGE = "11111111-1111-1111-1111-1111111111aa";
    private static final String ROLE_OWNER = "22222222-2222-2222-2222-2222222222a1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @MockitoBean
    private CloudinaryImageService cloudinaryImageService;

    private User owner;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        businessRepository.deleteAll();

        Business business = new Business();
        business.setId(TENANT);
        business.setName("Sunny Shop");
        business.setSlug("sunny-shop-branding");
        businessRepository.save(business);

        permissionRepository.save(perm(PERM_MANAGE, "business.manage_settings", "Settings"));
        Role ownerRole = role(ROLE_OWNER, "owner");
        roleRepository.save(ownerRole);
        grant(ROLE_OWNER, PERM_MANAGE);

        owner = user(TENANT, "owner@branding.test", ROLE_OWNER);
        userRepository.save(owner);
    }

    @Test
    void patchBrandingPersistsAndReturnsValues() throws Exception {
        mockMvc.perform(patch("/api/v1/businesses/me/branding")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"displayName":"Sunny Online",
                                 "primaryColor":"#0F766E","accentColor":"#F59E0B"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branding.displayName").value("Sunny Online"))
                .andExpect(jsonPath("$.branding.primaryColor").value("#0F766E"))
                .andExpect(jsonPath("$.branding.accentColor").value("#F59E0B"));

        mockMvc.perform(get("/api/v1/businesses/me")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branding.displayName").value("Sunny Online"))
                .andExpect(jsonPath("$.branding.primaryColor").value("#0F766E"));
    }

    @Test
    void patchBrandingRejectsBadHexColor() throws Exception {
        mockMvc.perform(patch("/api/v1/businesses/me/branding")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"primaryColor\":\"teal\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchBrandingClearsFieldsWithEmptyString() throws Exception {
        mockMvc.perform(patch("/api/v1/businesses/me/branding")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"displayName\":\"Step 1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/businesses/me/branding")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER)
                        .contentType(APPLICATION_JSON)
                        .content("{\"displayName\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branding.displayName").value("Sunny Shop"));
    }

    @Test
    void uploadLogoPersistsSecureUrlAndDestroysPrevious() throws Exception {
        when(cloudinaryImageService.uploadImageToFolder(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(uploadResult("ub/" + TENANT + "/branding/logo/abc", "https://res.cloudinary.com/x/abc.png"))
                .thenReturn(uploadResult("ub/" + TENANT + "/branding/logo/xyz", "https://res.cloudinary.com/x/xyz.png"));

        MockMultipartFile firstFile = new MockMultipartFile(
                "file", "logo.png", "image/png", new byte[]{1, 2, 3, 4});
        mockMvc.perform(multipart("/api/v1/businesses/me/branding/logo")
                        .file(firstFile)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.branding.logoUrl").value("https://res.cloudinary.com/x/abc.png"));

        MockMultipartFile second = new MockMultipartFile(
                "file", "logo2.png", "image/png", new byte[]{5, 6, 7, 8});
        mockMvc.perform(multipart("/api/v1/businesses/me/branding/logo")
                        .file(second)
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.branding.logoUrl").value("https://res.cloudinary.com/x/xyz.png"));

        verify(cloudinaryImageService, atLeastOnce()).destroyImage("ub/" + TENANT + "/branding/logo/abc");
    }

    @Test
    void deleteLogoClearsUrlAndDestroysAsset() throws Exception {
        when(cloudinaryImageService.uploadImageToFolder(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(uploadResult("ub/" + TENANT + "/branding/logo/abc", "https://res.cloudinary.com/x/abc.png"));

        mockMvc.perform(multipart("/api/v1/businesses/me/branding/logo")
                        .file(new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1}))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/businesses/me/branding/logo")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branding.logoUrl").doesNotExist());

        verify(cloudinaryImageService, times(1)).destroyImage("ub/" + TENANT + "/branding/logo/abc");
    }

    @Test
    void uploadFaviconPersistsSecureUrlAndDestroysPrevious() throws Exception {
        when(cloudinaryImageService.uploadImageToFolder(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(uploadResult("ub/" + TENANT + "/branding/favicon/a", "https://res.cloudinary.com/x/a.png"))
                .thenReturn(uploadResult("ub/" + TENANT + "/branding/favicon/b", "https://res.cloudinary.com/x/b.png"));

        mockMvc.perform(multipart("/api/v1/businesses/me/branding/favicon")
                        .file(new MockMultipartFile("file", "favicon.png", "image/png", new byte[]{1, 2}))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.branding.faviconUrl").value("https://res.cloudinary.com/x/a.png"));

        mockMvc.perform(multipart("/api/v1/businesses/me/branding/favicon")
                        .file(new MockMultipartFile("file", "f2.png", "image/png", new byte[]{3, 4}))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.branding.faviconUrl").value("https://res.cloudinary.com/x/b.png"));

        verify(cloudinaryImageService, atLeastOnce()).destroyImage("ub/" + TENANT + "/branding/favicon/a");
    }

    @Test
    void deleteFaviconClearsUrlAndDestroysAsset() throws Exception {
        when(cloudinaryImageService.uploadImageToFolder(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(uploadResult("ub/" + TENANT + "/branding/favicon/x", "https://res.cloudinary.com/x/fav.png"));

        mockMvc.perform(multipart("/api/v1/businesses/me/branding/favicon")
                        .file(new MockMultipartFile("file", "favicon.png", "image/png", new byte[]{9}))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/businesses/me/branding/favicon")
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branding.faviconUrl").doesNotExist());

        verify(cloudinaryImageService, times(1)).destroyImage("ub/" + TENANT + "/branding/favicon/x");
    }

    private static CloudinaryUploadResult uploadResult(String publicId, String secureUrl) {
        return new CloudinaryUploadResult(
                publicId,
                secureUrl,
                256,
                256,
                1024L,
                "png",
                "image/png",
                "1",
                "#0F766E",
                null
        );
    }

    private static Permission perm(String id, String key, String description) {
        Permission p = new Permission();
        p.setId(id);
        p.setPermissionKey(key);
        p.setDescription(description);
        return p;
    }

    private static Role role(String id, String key) {
        Role r = new Role();
        r.setId(id);
        r.setBusinessId(null);
        r.setRoleKey(key);
        r.setName(key);
        r.setSystem(true);
        return r;
    }

    private void grant(String roleId, String permissionId) {
        RolePermission rp = new RolePermission();
        rp.setId(new RolePermission.Id(roleId, permissionId));
        rolePermissionRepository.save(rp);
    }

    private static User user(String tenant, String email, String roleId) {
        User u = new User();
        u.setBusinessId(tenant);
        u.setEmail(email);
        u.setName("User");
        u.setRoleId(roleId);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("$2a$10$stubstubstubstubstubstubstubstubst");
        return u;
    }
}
