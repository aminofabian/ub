package zelisline.ub.globalcatalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductStatus;

class SuperAdminGlobalCatalogPromoteStatusTest {

    @Test
    void draftPromoteKeepsPublished() {
        GlobalProduct existing = new GlobalProduct();
        existing.setStatus(GlobalProductStatus.PUBLISHED);
        assertEquals(
                GlobalProductStatus.PUBLISHED,
                SuperAdminGlobalCatalogPromoteService.resolvePromoteStatus(
                        existing, false, GlobalProductStatus.DRAFT)
        );
    }

    @Test
    void publishFlagElevatesDraft() {
        GlobalProduct existing = new GlobalProduct();
        existing.setStatus(GlobalProductStatus.DRAFT);
        assertEquals(
                GlobalProductStatus.PUBLISHED,
                SuperAdminGlobalCatalogPromoteService.resolvePromoteStatus(
                        existing, true, GlobalProductStatus.PUBLISHED)
        );
    }

    @Test
    void archivedRevivesToTarget() {
        GlobalProduct existing = new GlobalProduct();
        existing.setStatus(GlobalProductStatus.ARCHIVED);
        assertEquals(
                GlobalProductStatus.PUBLISHED,
                SuperAdminGlobalCatalogPromoteService.resolvePromoteStatus(
                        existing, true, GlobalProductStatus.PUBLISHED)
        );
        assertEquals(
                GlobalProductStatus.DRAFT,
                SuperAdminGlobalCatalogPromoteService.resolvePromoteStatus(
                        existing, false, GlobalProductStatus.DRAFT)
        );
    }

    @Test
    void createUsesTarget() {
        assertEquals(
                GlobalProductStatus.DRAFT,
                SuperAdminGlobalCatalogPromoteService.resolvePromoteStatus(
                        null, false, GlobalProductStatus.DRAFT)
        );
    }
}
