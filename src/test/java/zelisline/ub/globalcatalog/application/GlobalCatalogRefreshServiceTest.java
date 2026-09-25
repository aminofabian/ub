package zelisline.ub.globalcatalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.globalcatalog.api.dto.RefreshCatalogRequest;
import zelisline.ub.globalcatalog.api.dto.RefreshCatalogResponse;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductStatus;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.pricing.repository.SellingPriceRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * The refresh image re-host (CDN I/O) must run after the price transaction commits, so a slow media
 * store never holds a pooled DB connection. A real {@link TransactionTemplate} over a mocked
 * transaction manager lets us assert the commit-then-rehost ordering.
 */
@ExtendWith(MockitoExtension.class)
class GlobalCatalogRefreshServiceTest {

    private static final String BUSINESS = "biz-1";
    private static final String BRANCH = "branch-1";
    private static final String CATALOG = "catalog-1";
    private static final String GLOBAL_ID = "global-1";
    private static final String ITEM_ID = "item-1";
    private static final String IMAGE_URL = "https://cdn.example/template.png";

    @Mock
    private GlobalCatalogResolver globalCatalogResolver;
    @Mock
    private GlobalProductRepository globalProductRepository;
    @Mock
    private ItemRepository itemRepository;
    @Mock
    private BranchRepository branchRepository;
    @Mock
    private SellingPriceRepository sellingPriceRepository;
    @Mock
    private PricingService pricingService;
    @Mock
    private SupplierProductRepository supplierProductRepository;
    @Mock
    private GlobalCatalogAdoptImageAttacher imageAttacher;
    @Mock
    private PlatformTransactionManager transactionManager;

    private GlobalCatalogRefreshService service;

    @BeforeEach
    void setUp() {
        service = new GlobalCatalogRefreshService(
                globalCatalogResolver,
                globalProductRepository,
                itemRepository,
                branchRepository,
                sellingPriceRepository,
                pricingService,
                supplierProductRepository,
                imageAttacher,
                new TransactionTemplate(transactionManager));
    }

    @Test
    void imageRehostRunsAfterCommitNotInsideTheTransaction() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        GlobalCatalog catalog = new GlobalCatalog();
        catalog.setId(CATALOG);

        GlobalProduct product = new GlobalProduct();
        product.setId(GLOBAL_ID);
        product.setCatalogId(CATALOG);
        product.setStatus(GlobalProductStatus.PUBLISHED);
        product.setImageUrl(IMAGE_URL);

        Item item = new Item();
        item.setId(ITEM_ID);
        item.setBusinessId(BUSINESS);
        item.setGlobalProductSourceId(GLOBAL_ID);
        item.setName("Cola");

        when(branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(BRANCH, BUSINESS))
                .thenReturn(Optional.of(new Branch()));
        when(globalCatalogResolver.resolveForBusiness(BUSINESS)).thenReturn(catalog);
        when(globalProductRepository.findAllById(List.of(GLOBAL_ID))).thenReturn(List.of(product));
        when(itemRepository.findByBusinessIdAndGlobalProductSourceIdInAndDeletedAtIsNull(BUSINESS, List.of(GLOBAL_ID)))
                .thenReturn(List.of(item));
        when(sellingPriceRepository.findOpenEnded(eq(BUSINESS), eq(ITEM_ID), any())).thenReturn(List.of());
        when(imageAttacher.attachFromGlobalUrl(eq(BUSINESS), eq(ITEM_ID), eq(IMAGE_URL), eq(true)))
                .thenReturn(GlobalCatalogAdoptImageAttacher.AttachResult.ok(1));

        RefreshCatalogRequest request = new RefreshCatalogRequest(
                BRANCH, List.of(GLOBAL_ID), null, null, true, false, null);

        RefreshCatalogResponse response = service.refresh(BUSINESS, request, "actor-1");

        assertThat(response.updatedCount()).isEqualTo(1);
        assertThat(response.lines().get(0).imageUpdated()).isTrue();
        assertThat(response.lines().get(0).message()).contains("Image updated");

        InOrder ordered = inOrder(transactionManager, imageAttacher);
        ordered.verify(transactionManager).commit(any(TransactionStatus.class));
        ordered.verify(imageAttacher).attachFromGlobalUrl(anyString(), anyString(), anyString(), eq(true));
    }
}
