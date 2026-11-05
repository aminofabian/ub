package zelisline.ub.globalcatalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;

/** §4.3 invariants, enforced by the single shared writer (scope §4.2). */
class GlobalCatalogProductGraphWriterTest {

    private GlobalProductRepository repository;
    private GlobalCatalogProductGraphWriter writer;

    @BeforeEach
    void setUp() {
        repository = mock(GlobalProductRepository.class);
        writer = new GlobalCatalogProductGraphWriter(repository);
    }

    private static GlobalProduct product(String id, String catalogId, String status) {
        GlobalProduct p = new GlobalProduct();
        p.setId(id);
        p.setCatalogId(catalogId);
        p.setName("P-" + id);
        p.setStatus(status);
        return p;
    }

    @Test
    void missingParentIsRejected() {
        var check = writer.checkParent("cat", "child", "draft", null, false);
        assertThat(check.code()).isEqualTo("relation.parent_not_found");
    }

    @Test
    void selfParentIsRejected() {
        GlobalProduct parent = product("same", "cat", "published");
        var check = writer.checkParent("cat", "same", "draft", parent, false);
        assertThat(check.code()).isEqualTo("relation.parent_is_self");
    }

    @Test
    void crossCatalogParentIsRejected() {
        GlobalProduct parent = product("p", "other-catalog", "published");
        var check = writer.checkParent("cat", "child", "draft", parent, false);
        assertThat(check.code()).isEqualTo("relation.parent_wrong_catalog");
    }

    @Test
    void parentThatIsItselfAVariantIsRejected() {
        GlobalProduct parent = product("p", "cat", "published");
        parent.setVariantOfGlobalProductId("grandparent");
        var check = writer.checkParent("cat", "child", "draft", parent, false);
        assertThat(check.code()).isEqualTo("relation.parent_is_variant");
    }

    @Test
    void archivedParentIsRejectedEvenWithDraftChild() {
        GlobalProduct parent = product("p", "cat", "archived");
        var check = writer.checkParent("cat", "child", "draft", parent, false);
        assertThat(check.code()).isEqualTo("relation.parent_not_published");
    }

    @Test
    void publishedChildUnderDraftParentIsRejectedByDefault() {
        GlobalProduct parent = product("p", "cat", "draft");
        var check = writer.checkParent("cat", "child", "published", parent, false);
        assertThat(check.code()).isEqualTo("relation.parent_not_published");
    }

    @Test
    void publishedChildUnderDraftParentPassesWhenExplicitlyAllowed() {
        GlobalProduct parent = product("p", "cat", "draft");
        var check = writer.checkParent("cat", "child", "published", parent, true);
        assertThat(check.ok()).isTrue();
    }

    @Test
    void draftChildUnderDraftParentIsFine() {
        GlobalProduct parent = product("p", "cat", "draft");
        var check = writer.checkParent("cat", "child", "draft", parent, false);
        assertThat(check.ok()).isTrue();
    }

    @Test
    void applyParentSetsTheLinkInsideTheChildWrite() {
        GlobalProduct parent = product("parent", "cat", "published");
        when(repository.findById("parent")).thenReturn(Optional.of(parent));
        GlobalProduct child = product("child", "cat", "published");

        writer.applyParent(child, "parent", false);

        assertThat(child.getVariantOfGlobalProductId()).isEqualTo("parent");
    }

    @Test
    void applyParentThrowsInsteadOfFlatteningOnViolation() {
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        GlobalProduct child = product("child", "cat", "published");

        assertThatThrownBy(() -> writer.applyParent(child, "ghost", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relation.parent_not_found");
        assertThat(child.getVariantOfGlobalProductId()).isNull();
    }

    @Test
    void applyParentWithBlankClearsTheLink() {
        GlobalProduct child = product("child", "cat", "draft");
        child.setVariantOfGlobalProductId("old-parent");

        writer.applyParent(child, " ", false);

        assertThat(child.getVariantOfGlobalProductId()).isNull();
    }
}
