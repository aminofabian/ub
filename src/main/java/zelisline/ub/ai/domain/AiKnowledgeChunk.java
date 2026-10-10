package zelisline.ub.ai.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One retrievable chunk of a {@link AiKnowledgeDocument}. */
@Getter
@Setter
@Entity
@Table(name = "ai_knowledge_chunk")
public class AiKnowledgeChunk {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "content", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
