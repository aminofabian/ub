package zelisline.ub.posdraft.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.posdraft.domain.PosDraftAuditLog;

public interface PosDraftAuditLogRepository extends JpaRepository<PosDraftAuditLog, String> {

    List<PosDraftAuditLog> findByDraftIdOrderByCreatedAtAsc(String draftId);

    /** The latest few things that happened to an order, newest first, for the order's history. */
    List<PosDraftAuditLog> findTop50ByDraftIdOrderByCreatedAtDesc(String draftId);
}
