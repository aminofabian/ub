package zelisline.ub.ai.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.ai.domain.AiKnowledgeDocument;

public interface AiKnowledgeDocumentRepository extends JpaRepository<AiKnowledgeDocument, String> {

    List<AiKnowledgeDocument> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    Optional<AiKnowledgeDocument> findByIdAndBusinessId(String id, String businessId);
}
