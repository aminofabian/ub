package zelisline.ub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import zelisline.ub.catalog.api.dto.CatalogListSort;

class CatalogListSortTest {

    @Test
    void nameAscUsesIgnoreCaseNameAndSku() {
        Sort sort = ItemCatalogService.sortForCatalogList(CatalogListSort.NAME_ASC);
        assertThat(sort.getOrderFor("name")).isNotNull();
        assertThat(sort.getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(sort.getOrderFor("sku")).isNotNull();
    }

    @Test
    void sellAscOrdersByBundlePrice() {
        Sort sort = ItemCatalogService.sortForCatalogList(CatalogListSort.SELL_ASC);
        assertThat(sort.getOrderFor("bundlePrice")).isNotNull();
        assertThat(sort.getOrderFor("bundlePrice").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void profitDescUsesUnsafeExpression() {
        Sort sort = ItemCatalogService.sortForCatalogList(CatalogListSort.PROFIT_DESC);
        assertThat(sort.toString()).contains("coalesce(bundlePrice");
        assertThat(sort.toString()).containsIgnoringCase("desc");
    }

    @Test
    void marginAscUsesNullifBuyPrice() {
        Sort sort = ItemCatalogService.sortForCatalogList(CatalogListSort.MARGIN_ASC);
        assertThat(sort.toString()).contains("nullif(buyingPrice");
    }
}
