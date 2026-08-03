package zelisline.ub.posdraft.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.posdraft.domain.PosDraftAuditLog;

public interface PosDraftAuditLogRepository extends JpaRepository<PosDraftAuditLog, String> {

    List<PosDraftAuditLog> findByDraftIdOrderByCreatedAtAsc(String draftId);
}
