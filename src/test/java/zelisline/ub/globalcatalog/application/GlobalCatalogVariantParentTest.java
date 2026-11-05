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

    private static AdoptLineRequest line(String globalProductId) {
        return new AdoptLineRequest(
                globalProductId,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void orderParentsBeforeVariantsPutsChildrenLast() {
        GlobalProduct parent = new GlobalProduct();
        parent.setId("parent");
        GlobalProduct child = new GlobalProduct();
        child.setId("child");
        child.setVariantOfGlobalProductId("parent");

        Map<String, GlobalProduct> byId = Map.of("parent", parent, "child", child);
        List<AdoptLineRequest> ordered = GlobalCatalogService.orderParentsBeforeVariants(
                List.of(line("child"), line("parent")),
                byId);

        assertEquals("parent", ordered.get(0).globalProductId());
        assertEquals("child", ordered.get(1).globalProductId());
    }

    @Test
    void orderParentsBeforeVariantsIsTopologicalForDeepChains() {
        // grandparent ← parent ← child, submitted child-first: the old 2-bucket split
        // mis-ordered this (scope §8 fix 1); the topological order must not.
        GlobalProduct grandparent = new GlobalProduct();
        grandparent.setId("a");
        GlobalProduct parent = new GlobalProduct();
        parent.setId("b");
        parent.setVariantOfGlobalProductId("a");
        GlobalProduct child = new GlobalProduct();
        child.setId("c");
        child.setVariantOfGlobalProductId("b");

        Map<String, GlobalProduct> byId = Map.of("a", grandparent, "b", parent, "c", child);
        List<AdoptLineRequest> ordered = GlobalCatalogService.orderParentsBeforeVariants(
                List.of(line("c"), line("b"), line("a")),
                byId);

        assertEquals(
                List.of("a", "b", "c"),
                ordered.stream().map(AdoptLineRequest::globalProductId).toList());
    }

    @Test
    void orderParentsBeforeVariantsTreatsOutOfBatchParentsAsRoots() {
        GlobalProduct child = new GlobalProduct();
        child.setId("child");
        child.setVariantOfGlobalProductId("not-in-batch");

        List<AdoptLineRequest> ordered = GlobalCatalogService.orderParentsBeforeVariants(
                List.of(line("child")),
                Map.of("child", child));

        assertEquals(1, ordered.size());
        assertEquals("child", ordered.get(0).globalProductId());
    }

    @Test
    void orderParentsBeforeVariantsSurvivesCycles() {
        GlobalProduct a = new GlobalProduct();
        a.setId("a");
        a.setVariantOfGlobalProductId("b");
        GlobalProduct b = new GlobalProduct();
        b.setId("b");
        b.setVariantOfGlobalProductId("a");

        // A cycle cannot be ordered; both lines must still come back (they fail at write).
        List<AdoptLineRequest> ordered = GlobalCatalogService.orderParentsBeforeVariants(
                List.of(line("a"), line("b")),
                Map.of("a", a, "b", b));

        assertEquals(2, ordered.size());
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
