package zelisline.ub.inventory.domain;

import java.io.Serializable;
import java.util.Objects;

public class DailyStockAuditItemId implements Serializable {

    private String auditId;
    private String itemId;

    public DailyStockAuditItemId() {
    }

    public DailyStockAuditItemId(String auditId, String itemId) {
        this.auditId = auditId;
        this.itemId = itemId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DailyStockAuditItemId that)) {
            return false;
        }
        return Objects.equals(auditId, that.auditId)
                && Objects.equals(itemId, that.itemId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(auditId, itemId);
    }
}
