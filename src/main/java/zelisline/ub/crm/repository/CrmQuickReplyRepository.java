package zelisline.ub.crm.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.crm.domain.CrmQuickReply;

public interface CrmQuickReplyRepository extends JpaRepository<CrmQuickReply, String> {

    List<CrmQuickReply> findByBusinessIdOrderByShortcutAsc(String businessId);

    Optional<CrmQuickReply> findByIdAndBusinessId(String id, String businessId);

    boolean existsByBusinessIdAndShortcut(String businessId, String shortcut);
}
