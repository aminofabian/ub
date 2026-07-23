package zelisline.ub.globalcatalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.globalcatalog.api.dto.AdoptLineRequest;
import zelisline.ub.globalcatalog.domain.GlobalProduct;

class GlobalCatalogVariantParentTest {

    @Test
    void orderParentsBeforeVariantsPutsChildrenLast() {
        GlobalProduct parent = new GlobalProduct();
        parent.setId("parent");
        GlobalProduct child = new GlobalProduct();
        child.setId("child");
        child.setVariantOfGlobalProductId("parent");

        Map<String, GlobalProduct> byId = Map.of("parent", parent, "child", child);
        List<AdoptLineRequest> ordered = GlobalCatalogService.orderParentsBeforeVariants(
                List.of(
                        new AdoptLineRequest("child", null, null, null, null, null, null, null, null, null, null),
                        new AdoptLineRequest("parent", null, null, null, null, null, null, null, null, null, null)),
                byId);

        assertEquals("parent", ordered.get(0).globalProductId());
        assertEquals("child", ordered.get(1).globalProductId());
    }

    @Test
    void resolveParentGlobalProductIdPrefersBatchMap() {
        Item child = new Item();
        child.setVariantOfItemId("tenant-parent");

        Map<String, String> batch = new HashMap<>();
        batch.put("tenant-parent", "global-parent");

        SuperAdminGlobalCatalogPromoteService.GlobalMatchIndex index =
                SuperAdminGlobalCatalogPromoteService.GlobalMatchIndex.build(List.of());

        String resolved = SuperAdminGlobalCatalogPromoteService.resolveParentGlobalProductId(
                child,
                batch,
                index,
                parentId -> Optional.empty());

        assertEquals("global-parent", resolved);
    }

    @Test
    void resolveParentGlobalProductIdFallsBackToMatchIndex() {
        Item child = new Item();
        child.setVariantOfItemId("tenant-parent");

        Item parent = new Item();
        parent.setId("tenant-parent");
        parent.setSku("EGG-BASE");
        parent.setName("Eggs");

        GlobalProduct globalParent = new GlobalProduct();
        globalParent.setId("global-parent");
        globalParent.setSkuTemplate("EGG-BASE");
        globalParent.setName("Eggs");
        globalParent.setStatus("published");

        SuperAdminGlobalCatalogPromoteService.GlobalMatchIndex index =
                SuperAdminGlobalCatalogPromoteService.GlobalMatchIndex.build(List.of(globalParent));

        String resolved = SuperAdminGlobalCatalogPromoteService.resolveParentGlobalProductId(
                child,
                Map.of(),
                index,
                parentId -> Optional.of(parent));

        assertEquals("global-parent", resolved);
    }

    @Test
    void resolveParentGlobalProductIdReturnsNullWhenStandalone() {
        Item standalone = new Item();
        assertNull(SuperAdminGlobalCatalogPromoteService.resolveParentGlobalProductId(
                standalone,
                Map.of(),
                SuperAdminGlobalCatalogPromoteService.GlobalMatchIndex.build(List.of()),
                parentId -> Optional.empty()));
    }
}
