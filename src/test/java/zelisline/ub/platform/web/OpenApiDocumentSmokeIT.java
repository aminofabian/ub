package zelisline.ub.platform.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the OpenAPI document endpoint used for {@code docs/openapi/phase-1.yaml}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDocumentSmokeIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openapiDocumentIsPublicAndIncludesCorePaths() throws Exception {
        mockMvc.perform(get("/api/v1/openapi").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi").value(Matchers.startsWith("3.")))
                .andExpect(jsonPath("$.paths['/api/v1/users']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/branches']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/businesses/me']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/categories']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/categories/tree']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/item-types']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/items/{id}/images/upload']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/items/{id}/pricing-context']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/sales']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/sales/intelligence/revenue-by-category']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/web-orders']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/sales/{saleId}/receipt.pdf']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/sales/{saleId}/receipt/thermal']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/suppliers/{supplierId}/item-links']").exists());
    }
}
