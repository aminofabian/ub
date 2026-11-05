package zelisline.ub.purchasing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;

@ExtendWith(MockitoExtension.class)
class SupplyCostDefaultsServiceTest {

    private static final String BUSINESS = "biz-1";
    private static final String SUPPLIER = "sup-1";
    private static final String ITEM = "item-1";

    @Mock
    private SupplierProductRepository supplierProductRepository;
    @Mock
    private ItemRepository itemRepository;

    private SupplyCostDefaultsService service;
    private SupplierProduct link;
    private Item item;

    @BeforeEach
    void setUp() {
        service = new SupplyCostDefaultsService(supplierProductRepository, itemRepository);
        link = new SupplierProduct();
        link.setActive(true);
        link.setDefaultCostPrice(new BigDecimal("50.0000"));
        item = new Item();
        item.setBuyingPrice(new BigDecimal("50.00"));
    }

    @Test
    void paidCost_becomesTheDefaultOnTheLinkAndTheCatalogBuyingPrice() {
        when(supplierProductRepository.findBySupplierIdAndItemId(SUPPLIER, ITEM)).thenReturn(Optional.of(link));
        when(itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(ITEM, BUSINESS)).thenReturn(Optional.of(item));

        service.recordSupplyCost(BUSINESS, SUPPLIER, ITEM, new BigDecimal("120.5000"));

        assertThat(link.getDefaultCostPrice()).isEqualByComparingTo("120.5000");
        assertThat(link.getLastCostPrice()).isEqualByComparingTo("120.5000");
        assertThat(link.getLastPurchaseAt()).isNotNull();
        assertThat(item.getBuyingPrice()).isEqualByComparingTo("120.50");
        verify(supplierProductRepository).save(link);
        verify(itemRepository).save(item);
    }

    @Test
    void catalogBuyingPrice_isRoundedHalfUpToTheColumnScale() {
        when(supplierProductRepository.findBySupplierIdAndItemId(SUPPLIER, ITEM)).thenReturn(Optional.empty());
        when(itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(ITEM, BUSINESS)).thenReturn(Optional.of(item));

        service.recordSupplyCost(BUSINESS, SUPPLIER, ITEM, new BigDecimal("7.3350"));

        assertThat(item.getBuyingPrice()).isEqualByComparingTo("7.34");
    }

    @Test
    void freeGoods_recordTheLastCostButNeverOverwriteTheDefaults() {
        when(supplierProductRepository.findBySupplierIdAndItemId(SUPPLIER, ITEM)).thenReturn(Optional.of(link));

        service.recordSupplyCost(BUSINESS, SUPPLIER, ITEM, BigDecimal.ZERO);

        assertThat(link.getLastCostPrice()).isEqualByComparingTo("0");
        assertThat(link.getDefaultCostPrice()).isEqualByComparingTo("50.0000");
        verifyNoInteractions(itemRepository);
    }

    @Test
    void inactiveLink_isLeftAlone_butTheProductStillFollowsTheSupply() {
        link.setActive(false);
        when(supplierProductRepository.findBySupplierIdAndItemId(SUPPLIER, ITEM)).thenReturn(Optional.of(link));
        when(itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(ITEM, BUSINESS)).thenReturn(Optional.of(item));

        service.recordSupplyCost(BUSINESS, SUPPLIER, ITEM, new BigDecimal("80"));

        assertThat(link.getDefaultCostPrice()).isEqualByComparingTo("50.0000");
        assertThat(link.getLastCostPrice()).isNull();
        verify(supplierProductRepository, never()).save(any());
        assertThat(item.getBuyingPrice()).isEqualByComparingTo("80.00");
    }

    @Test
    void noSupplierLink_stillUpdatesTheCatalogBuyingPrice() {
        when(supplierProductRepository.findBySupplierIdAndItemId(SUPPLIER, ITEM)).thenReturn(Optional.empty());
        when(itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(ITEM, BUSINESS)).thenReturn(Optional.of(item));

        service.recordSupplyCost(BUSINESS, SUPPLIER, ITEM, new BigDecimal("65"));

        assertThat(item.getBuyingPrice()).isEqualByComparingTo("65.00");
        verify(supplierProductRepository, never()).save(any());
    }

    @Test
    void costBelowOneCent_doesNotZeroTheCatalogBuyingPrice() {
        when(supplierProductRepository.findBySupplierIdAndItemId(SUPPLIER, ITEM)).thenReturn(Optional.empty());

        service.recordSupplyCost(BUSINESS, SUPPLIER, ITEM, new BigDecimal("0.0040"));

        verifyNoInteractions(itemRepository);
    }
}
