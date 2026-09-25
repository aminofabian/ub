package zelisline.ub.globalcatalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.media.MediaStore;

/**
 * Guards against holding a DB connection across media-store latency: the CDN upload/destroy in the
 * super-admin product-image methods must run with no transaction active, while the row update still
 * persists.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SuperAdminGlobalCatalogImageTxIT {

    @Autowired
    private SuperAdminGlobalCatalogService service;

    @Autowired
    private SuperAdminGlobalCatalogPromoteLineExecutor promoteLineExecutor;

    @Autowired
    private GlobalCatalogRepository catalogRepository;

    @Autowired
    private GlobalProductRepository productRepository;

    @MockitoBean
    private MediaStore mediaStore;

    private String catalogId;
    private String productId;

    @BeforeEach
    void seed() {
        productRepository.deleteAll();
        catalogRepository.deleteAll();

        GlobalCatalog catalog = new GlobalCatalog();
        catalog.setCode("default");
        catalog.setName("Default");
        catalog.setRegionCode("KE");
        catalog.setCurrency("KES");
        catalog.setStatus("published");
        catalogId = catalogRepository.save(catalog).getId();

        GlobalProduct product = new GlobalProduct();
        product.setCatalogId(catalogId);
        product.setName("Image Product");
        product.setUnitType("each");
        product.setStatus("published");
        productId = productRepository.save(product).getId();
    }

    @Test
    void uploadRehostsOutsideAnyTransaction() {
        when(mediaStore.isConfigured()).thenReturn(true);
        when(mediaStore.uploadImageToFolder(any(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .as("CDN upload must not run inside a DB transaction")
                            .isFalse();
                    return new CloudinaryUploadResult(
                            "public-1", "https://cdn.example/1.png",
                            null, null, null, null, null, null, null, null);
                });

        MockMultipartFile file = new MockMultipartFile("file", "cover.png", "image/png", new byte[] {1, 2, 3});
        var response = service.uploadProductImage(productId, catalogId, file);

        assertThat(response).isNotNull();
        assertThat(productRepository.findById(productId).orElseThrow().getImageUrl())
                .isEqualTo("https://cdn.example/1.png");
    }

    @Test
    void clearDestroysOutsideAnyTransaction() {
        GlobalProduct product = productRepository.findById(productId).orElseThrow();
        product.setImageUrl("https://cdn.example/old.png");
        product.setImagePublicId("public-old");
        productRepository.save(product);

        when(mediaStore.isConfigured()).thenReturn(true);
        AtomicBoolean destroyed = new AtomicBoolean(false);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("CDN destroy must not run inside a DB transaction")
                    .isFalse();
            destroyed.set(true);
            return null;
        }).when(mediaStore).destroyImage(anyString());

        service.clearProductImage(productId, catalogId);

        assertThat(destroyed).isTrue();
        assertThat(productRepository.findById(productId).orElseThrow().getImageUrl()).isNull();
    }

    @Test
    void promoteRehostRunsOutsideAnyTransaction() {
        when(mediaStore.isConfigured()).thenReturn(true);
        when(mediaStore.uploadFromRemoteUrl(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .as("CDN re-host must not run inside a DB transaction")
                            .isFalse();
                    return new CloudinaryUploadResult(
                            "promoted-1", "https://cdn.example/promoted.png",
                            null, null, null, null, null, null, null, null);
                });

        boolean rehosted = promoteLineExecutor.rehostImages(
                productId, List.of("https://source.example/a.png"));

        assertThat(rehosted).isTrue();
        assertThat(productRepository.findById(productId).orElseThrow().getImageUrl())
                .isEqualTo("https://cdn.example/promoted.png");
    }
}
