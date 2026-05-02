package zelisline.ub.catalog.application;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Prevents category parent chains from forming a cycle (PHASE_1_PLAN.md §4.3).
 */
public final class CategoryCycleDetector {

    private CategoryCycleDetector() {
    }

    /**
     * @param categoryBeingEditedId id of the category whose parent is changing; {@code null} on create
     * @param proposedParentId      new parent id, or {@code null} for root
     * @param parentIdByCategoryId  resolves parent id for an existing category in this tenant
     */
    public static boolean wouldIntroduceCycle(
            String categoryBeingEditedId,
            String proposedParentId,
            Function<String, Optional<String>> parentIdByCategoryId
    ) {
        if (proposedParentId == null) {
            return false;
        }
        if (categoryBeingEditedId != null && categoryBeingEditedId.equals(proposedParentId)) {
            return true;
        }
        String current = proposedParentId;
        Set<String> visited = new HashSet<>();
        while (current != null) {
            if (categoryBeingEditedId != null && current.equals(categoryBeingEditedId)) {
                return true;
            }
            if (!visited.add(current)) {
                return true;
            }
            current = parentIdByCategoryId.apply(current).orElse(null);
        }
        return false;
    }
}
