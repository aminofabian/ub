package zelisline.ub.grocery.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.grocery.domain.GroceryDraftAuditLog;

public interface GroceryDraftAuditLogRepository extends JpaRepository<GroceryDraftAuditLog, String> {
}
