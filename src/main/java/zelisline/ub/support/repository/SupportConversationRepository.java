package zelisline.ub.support.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import zelisline.ub.support.domain.SupportConversation;

public interface SupportConversationRepository extends JpaRepository<SupportConversation, String> {

    Optional<SupportConversation> findByBusinessId(String businessId);

    /** Newest activity first; conversations with no messages sort by creation. */
    @Query("""
            SELECT c FROM SupportConversation c
            WHERE c.status = :status
            ORDER BY COALESCE(c.lastMessageAt, c.createdAt) DESC
            """)
    List<SupportConversation> findByStatusOrderByLastMessageAtDesc(String status);

    @Query("""
            SELECT c FROM SupportConversation c
            ORDER BY COALESCE(c.lastMessageAt, c.createdAt) DESC
            """)
    List<SupportConversation> findAllByOrderByLastMessageAtDesc();
}
