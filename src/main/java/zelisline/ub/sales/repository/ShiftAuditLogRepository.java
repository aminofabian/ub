package zelisline.ub.sales.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.sales.domain.ShiftAuditLog;

public interface ShiftAuditLogRepository extends JpaRepository<ShiftAuditLog, String> {

    List<ShiftAuditLog> findByShiftIdOrderByCreatedAtAsc(String shiftId);
}
