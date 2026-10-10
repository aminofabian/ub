package zelisline.ub.crm.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.crm.domain.CrmMessage;

public interface CrmMessageRepository extends JpaRepository<CrmMessage, String> {

    boolean existsByWaMessageId(String waMessageId);

    Optional<CrmMessage> findByWaMessageId(String waMessageId);

    List<CrmMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);
}
