package zelisline.ub.inventory.domain;

import java.io.Serializable;
import java.util.Objects;

public class StocktakeChecklistItemId implements Serializable {

    private String businessId;
    private String itemId;

    public StocktakeChecklistItemId() {
    }

    public StocktakeChecklistItemId(String businessId, String itemId) {
        this.businessId = businessId;
        this.itemId = itemId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StocktakeChecklistItemId that = (StocktakeChecklistItemId) o;
        return Objects.equals(businessId, that.businessId)
                && Objects.equals(itemId, that.itemId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(businessId, itemId);
    }
}
