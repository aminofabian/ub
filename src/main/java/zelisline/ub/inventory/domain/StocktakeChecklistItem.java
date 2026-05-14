package zelisline.ub.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "stocktake_checklist_items")
@IdClass(StocktakeChecklistItemId.class)
public class StocktakeChecklistItem {

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Id
    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "session_type", nullable = false, length = 16)
    private String sessionType;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
