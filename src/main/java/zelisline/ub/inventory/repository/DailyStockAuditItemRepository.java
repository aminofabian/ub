package zelisline.ub.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.inventory.domain.DailyStockAuditItem;
import zelisline.ub.inventory.domain.DailyStockAuditItemId;

public interface DailyStockAuditItemRepository
        extends JpaRepository<DailyStockAuditItem, DailyStockAuditItemId> {}
