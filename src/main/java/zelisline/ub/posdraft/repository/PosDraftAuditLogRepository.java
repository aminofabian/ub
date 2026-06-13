package zelisline.ub.posdraft.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.posdraft.domain.PosDraftAuditLog;

public interface PosDraftAuditLogRepository extends JpaRepository<PosDraftAuditLog, String> {
}
