package zelisline.ub.crm.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.crm.domain.CrmNote;

public interface CrmNoteRepository extends JpaRepository<CrmNote, String> {

    List<CrmNote> findByConversationIdOrderByCreatedAtAsc(String conversationId);
}
