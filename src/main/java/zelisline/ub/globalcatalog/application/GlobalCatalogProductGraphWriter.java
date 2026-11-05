package zelisline.ub.globalcatalog.application;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductStatus;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;

/**
 * The single owner of product-graph invariants (GLOBAL_CATALOG_IMPORT_SCOPE §4.2/§4.3).
 *
 * <p>Every path that writes {@code variant_of_global_product_id} — CSV import, promote,
 * (later) regional clone — validates through this class, so no caller can bypass a rule
 * by writing the column directly. The invariants:
 *
 * <ol>
 *   <li>A variant has at most one parent; the parent is not itself a variant.</li>
 *   <li>Parent and child are in the same catalog.</li>
 *   <li>No self-parent, no cycles, depth ≤ 1.</li>
 *   <li>A published child requires a published parent (unless the caller explicitly
 *       allows draft parents, which downgrades to a warning at plan time).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class GlobalCatalogProductGraphWriter {

    private final GlobalProductRepository globalProductRepository;

    /** {@code code == null} means the link is valid. */
    public record ParentCheck(String code, String message) {

        public static final ParentCheck OK = new ParentCheck(null, null);

        public boolean ok() {
            return code == null;
        }
    }

    /**
     * Validates a resolved parent entity against §4.3 for a child in {@code catalogId}
     * with the given planned status. {@code childId} may be null for a not-yet-created child.
     */
    public ParentCheck checkParent(
            String catalogId,
            String childId,
            String childStatus,
            GlobalProduct parent,
            boolean allowDraftParent
    ) {
        if (parent == null) {
            return new ParentCheck("relation.parent_not_found", "Parent product not found");
        }
        if (childId != null && childId.equals(parent.getId())) {
            return new ParentCheck("relation.parent_is_self", "A product cannot be its own parent");
        }
        if (!catalogId.equals(parent.getCatalogId())) {
            return new ParentCheck("relation.parent_wrong_catalog",
                    "Parent '" + parent.getName() + "' belongs to another catalog");
        }
        if (parent.getVariantOfGlobalProductId() != null
                && !parent.getVariantOfGlobalProductId().isBlank()) {
            return new ParentCheck("relation.parent_is_variant",
                    "Parent '" + parent.getName() + "' is itself a variant — nesting is limited to one level");
        }
        if (GlobalProductStatus.ARCHIVED.equals(parent.getStatus())) {
            return new ParentCheck("relation.parent_not_published",
                    "Parent '" + parent.getName() + "' is archived");
        }
        if (GlobalProductStatus.PUBLISHED.equals(childStatus)
                && !GlobalProductStatus.PUBLISHED.equals(parent.getStatus())
                && !allowDraftParent) {
            return new ParentCheck("relation.parent_not_published",
                    "Parent '" + parent.getName() + "' is " + parent.getStatus()
                            + " but the child is published");
        }
        return ParentCheck.OK;
    }

    /**
     * Write-time enforcement: loads the parent and re-checks the invariants inside the
     * caller's (row) transaction, then sets the link on the child. Throws on violation so
     * the row fails loudly instead of flattening silently.
     */
    public void applyParent(GlobalProduct child, String parentId, boolean allowDraftParent) {
        if (parentId == null || parentId.isBlank()) {
            child.setVariantOfGlobalProductId(null);
            return;
        }
        GlobalProduct parent = globalProductRepository.findById(parentId.trim()).orElse(null);
        ParentCheck check = checkParent(
                child.getCatalogId(),
                child.getId(),
                child.getStatus(),
                parent,
                allowDraftParent);
        if (!check.ok()) {
            throw new IllegalStateException(check.message() + " [" + check.code() + "]");
        }
        child.setVariantOfGlobalProductId(parent.getId());
    }
}
