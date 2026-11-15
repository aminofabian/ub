package zelisline.ub.restaurant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.JsonNode;

import zelisline.ub.identity.domain.User;
import zelisline.ub.platform.media.CloudinaryImageService;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.security.TestAuthenticationFilter;
import zelisline.ub.restaurant.repository.MenuItemRepository;
import zelisline.ub.serviceops.api.ServiceSaleTestSupport;

/**
 * A picture on a dish and on a service: what is accepted, who may set it, that it is filed under
 * the business that owns it, and that another business can neither set nor remove it.
 */
@TestPropertySource(properties = "app.media.cloudinary.enabled=true")
class PicturesIT extends ServiceSaleTestSupport {

    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0x10, 'J', 'F', 'I', 'F'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0};

    @Autowired
    private MenuItemRepository menuItemRepository;

    @MockitoBean
    private CloudinaryImageService store;

    private String burger;
    private int uploads;

    @BeforeEach
    void seedPictures() throws Exception {
        menuItemRepository.deleteAll();
        String id = "5a1e1111-1111-4111-8111-111111111180";
        permissionRepository.save(perm(id, "restaurant.menu.manage"));
        grant(ROLE_OWNER, id);
        uploads = 0;
        when(store.isConfigured()).thenReturn(true);
        when(store.uploadImageToFolder(any(), any(), anyString(), anyBoolean())).thenAnswer(call ->
                new CloudinaryUploadResult(call.getArgument(2) + "/pic" + (++uploads), "https://img.test/" + call.getArgument(2) + "/pic.jpg",
                        800, 600, 10L, "jpg", "image/jpeg", "1", null, null));
        burger = objectMapper.readTree(mockMvc.perform(as(post("/api/v1/restaurant/menu/items"), TENANT, owner, ROLE_OWNER)
                        .content("{\"name\":\"Burger\",\"kind\":\"DISH\",\"price\":400,\"description\":\"Beef, cheese, chips\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void aDishTakesAPictureAndADescriptionAndGivesThePictureUp() throws Exception {
        JsonNode set = upload("/api/v1/restaurant/menu/items/" + burger + "/picture", JPEG, TENANT, owner, ROLE_OWNER, 200);
        assertThat(set.get("description").asText()).isEqualTo("Beef, cheese, chips");
        assertThat(set.get("imageUrl").asText()).startsWith("https://img.test/ub/" + TENANT + "/menu/");

        ArgumentCaptor<String> folder = ArgumentCaptor.forClass(String.class);
        verify(store).uploadImageToFolder(any(), any(), folder.capture(), anyBoolean());
        assertThat(folder.getValue()).startsWith("ub/" + TENANT + "/");

        // A second picture replaces the first, and the first is thrown away.
        upload("/api/v1/restaurant/menu/items/" + burger + "/picture", PNG, TENANT, owner, ROLE_OWNER, 200);
        verify(store).destroyImage(folder.getValue() + "/pic1");

        JsonNode menu = objectMapper.readTree(mockMvc.perform(as(get("/api/v1/restaurant/menu"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(menu.get("items").get(0).get("imageUrl").asText()).contains("/menu/");

        JsonNode cleared = objectMapper.readTree(mockMvc.perform(
                        as(delete("/api/v1/restaurant/menu/items/" + burger + "/picture"), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cleared.hasNonNull("imageUrl")).isFalse();
    }

    @Test
    void onlyARealPictureIsAcceptedWhateverItIsCalled() throws Exception {
        String path = "/api/v1/restaurant/menu/items/" + burger + "/picture";
        upload(path, "<svg onload=alert(1)>".getBytes(), TENANT, owner, ROLE_OWNER, 400);
        upload(path, "%PDF-1.7".getBytes(), TENANT, owner, ROLE_OWNER, 400);
        upload(path, new byte[0], TENANT, owner, ROLE_OWNER, 400);
        verify(store, never()).uploadImageToFolder(any(), any(), anyString(), anyBoolean());
        assertThat(menuItemRepository.findById(burger).orElseThrow().getImageUrl()).isNull();
    }

    @Test
    void aStoreThatIsDownSaysSoPlainlyAndKeepsItsReasonsToItself() throws Exception {
        when(store.uploadImageToFolder(any(), any(), anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("Cloudinary upload failed: HTTP 401 — disabled account"));
        String body = mockMvc.perform(multipart("/api/v1/restaurant/menu/items/" + burger + "/picture")
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", JPEG))
                        .header("X-Tenant-Id", TENANT)
                        .header(TestAuthenticationFilter.HEADER_USER_ID, owner.getId())
                        .header(TestAuthenticationFilter.HEADER_ROLE_ID, ROLE_OWNER))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("Pictures cannot be saved right now").doesNotContain("Cloudinary").doesNotContain("401");
        assertThat(menuItemRepository.findById(burger).orElseThrow().getImageUrl()).isNull();
    }

    @Test
    void aCashierAndAnotherBusinessCannotTouchADishPicture() throws Exception {
        String path = "/api/v1/restaurant/menu/items/" + burger + "/picture";
        upload(path, JPEG, TENANT, cashier, ROLE_CASHIER, 403);
        upload(path, JPEG, OTHER, otherOwner, ROLE_OWNER, 404);
        upload(path, JPEG, TENANT, owner, ROLE_OWNER, 200);
        mockMvc.perform(as(delete(path), OTHER, otherOwner, ROLE_OWNER)).andExpect(status().isNotFound());
        assertThat(menuItemRepository.findById(burger).orElseThrow().getImageUrl()).isNotNull();
    }

    @Test
    void aServiceTakesAPictureAndAnotherBusinessCannotTouchIt() throws Exception {
        String path = "/api/v1/services/" + haircut + "/picture";
        upload(path, JPEG, TENANT, cashier, ROLE_CASHIER, 403);
        upload(path, JPEG, OTHER, otherOwner, ROLE_OWNER, 404);
        upload(path, "not a picture".getBytes(), TENANT, owner, ROLE_OWNER, 400);

        JsonNode set = upload(path, JPEG, TENANT, owner, ROLE_OWNER, 200);
        assertThat(set.get("imageUrl").asText()).startsWith("https://img.test/ub/" + TENANT + "/services/" + haircut);

        JsonNode listed = objectMapper.readTree(mockMvc.perform(as(get("/api/v1/services"), TENANT, cashier, ROLE_CASHIER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(listed.toString()).contains("/services/" + haircut + "/pic.jpg");

        mockMvc.perform(as(delete(path), OTHER, otherOwner, ROLE_OWNER)).andExpect(status().isNotFound());
        JsonNode cleared = objectMapper.readTree(mockMvc.perform(as(delete(path), TENANT, owner, ROLE_OWNER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cleared.hasNonNull("imageUrl")).isFalse();
    }

    @Test
    void theMenuIsOnTheWebPageUnlessTheOwnerTakesItOffAndShowsNothingPrivate() throws Exception {
        String web = "/api/v1/public/businesses/kinyozi-sale/menu";
        // Shown from the start: a restaurant's page without its menu is an empty page.
        mockMvc.perform(get(web)).andExpect(status().isOk());

        upload("/api/v1/restaurant/menu/items/" + burger + "/picture", JPEG, TENANT, owner, ROLE_OWNER, 200);
        mockMvc.perform(as(put("/api/v1/restaurant/settings"), TENANT, cashier, ROLE_CASHIER).content("{\"menuOnline\":false}"))
                .andExpect(status().isForbidden());

        String body = mockMvc.perform(get(web)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode menu = objectMapper.readTree(body);
        JsonNode dish = menu.get("dishes").get(0);
        assertThat(dish.get("name").asText()).isEqualTo("Burger");
        assertThat(dish.get("description").asText()).isEqualTo("Beef, cheese, chips");
        assertThat(dish.get("price").decimalValue()).isEqualByComparingTo("400");
        assertThat(dish.get("imageUrl").asText()).contains("/menu/");
        assertThat(body).doesNotContain("cost").doesNotContain("stock").doesNotContain("imagePublicId");

        // A slug nobody owns has no menu; another business's page shows only its own.
        mockMvc.perform(get("/api/v1/public/businesses/nobody-here/menu")).andExpect(status().isNotFound());
        // A business with nothing on a menu has no menu page, whatever its setting says.
        mockMvc.perform(get("/api/v1/public/businesses/other-sale/menu")).andExpect(status().isNotFound());

        mockMvc.perform(as(put("/api/v1/restaurant/settings"), TENANT, owner, ROLE_OWNER).content("{\"menuOnline\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(web)).andExpect(status().isNotFound());
    }

    private JsonNode upload(String path, byte[] bytes, String tenant, User user, String role, int expected) throws Exception {
        // Named and typed as a picture every time: the check must not trust either.
        var request = multipart(path)
                .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", bytes))
                .header("X-Tenant-Id", tenant)
                .header(TestAuthenticationFilter.HEADER_USER_ID, user.getId())
                .header(TestAuthenticationFilter.HEADER_ROLE_ID, role);
        String body = mockMvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return expected == 200 ? objectMapper.readTree(body) : null;
    }
}
