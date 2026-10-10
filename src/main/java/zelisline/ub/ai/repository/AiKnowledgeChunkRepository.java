package zelisline.ub.ai.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.ai.domain.AiKnowledgeChunk;

public interface AiKnowledgeChunkRepository extends JpaRepository<AiKnowledgeChunk, String> {

    List<AiKnowledgeChunk> findByBusinessId(String businessId);

    void deleteByDocumentId(String documentId);
}
