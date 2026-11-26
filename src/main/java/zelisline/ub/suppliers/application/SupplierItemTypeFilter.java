package zelisline.ub.suppliers.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * How {@code GET /suppliers?itemTypeIds=} should narrow the list.
 *
 * <ul>
 *   <li>{@code null} / absent — no department filter (owners, stock managers).
 *   <li>present but empty after trim — explicit empty scope (grocery clerk with no
 *       assignments): return no suppliers.
 *   <li>non-empty — only suppliers with an active link to an item in those departments.
 * </ul>
 */
public final class SupplierItemTypeFilter {

    public enum Mode {
        NONE,
        EMPTY,
        IDS
    }

    public record Decision(Mode mode, List<String> itemTypeIds) {}

    private SupplierItemTypeFilter() {}

    public static Decision of(List<String> raw) {
        if (raw == null) {
            return new Decision(Mode.NONE, List.of());
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            String id = entry.trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return new Decision(Mode.EMPTY, List.of());
        }
        return new Decision(Mode.IDS, List.copyOf(new ArrayList<>(ids)));
    }
}
