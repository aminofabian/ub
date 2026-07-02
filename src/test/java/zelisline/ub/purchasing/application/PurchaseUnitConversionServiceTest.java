package zelisline.ub.purchasing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;

@ExtendWith(MockitoExtension.class)
class PurchaseUnitConversionServiceTest {

    private static final String TENANT = "biz-1";
    private static final String SUPPLIER = "sup-1";
    private static final String ITEM = "item-1";

    @Mock
    private ItemRepository itemRepository;
    @Mock
    private SupplierProductRepository supplierProductRepository;

    private PurchaseUnitConversionService service;

    @BeforeEach
    void setUp() {
        service = new PurchaseUnitConversionService(itemRepository, supplierProductRepository);

        Item item = new Item();
        item.setId(ITEM);
        item.setBusinessId(TENANT);
        item.setName("Beef");
        item.setSku("BEEF");
        item.setUnitType("kg");
        item.setWeighed(true);
        item.setSellable(true);
        item.setStocked(true);

        SupplierProduct link = new SupplierProduct();
        link.setId("link-1");
        link.setSupplierId(SUPPLIER);
        link.setItemId(ITEM);
        link.setPackUnit("crate");
        link.setPackSize(new BigDecimal("25"));
        link.setActive(true);

        when(itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(ITEM, TENANT))
                .thenReturn(Optional.of(item));
        when(itemRepository.findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(TENANT, ITEM))
                .thenReturn(List.of());
        when(supplierProductRepository.findBySupplierIdAndItemId(SUPPLIER, ITEM))
                .thenReturn(Optional.of(link));
    }

    @Test
    void resolve_cratePackFromSupplierLink() {
        var res = service.resolve(TENANT, SUPPLIER, ITEM, new BigDecimal("2"), "crate");
        assertThat(res.catalogItemId()).isEqualTo(ITEM);
        assertThat(res.usableQty()).isEqualByComparingTo(new BigDecimal("50"));
    }

    @Test
    void assertMatchesPosted_rejectsMismatch() {
        assertThatThrownBy(() -> service.assertMatchesPosted(
                TENANT,
                SUPPLIER,
                ITEM,
                new BigDecimal("40"),
                new BigDecimal("2"),
                "crate"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void assertMatchesPosted_acceptsMatch() {
        service.assertMatchesPosted(
                TENANT,
                SUPPLIER,
                ITEM,
                new BigDecimal("50"),
                new BigDecimal("2"),
                "crate");
    }
}
