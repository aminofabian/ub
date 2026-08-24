package zelisline.ub.support.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.support.domain.SupportConversation;

public interface SupportConversationRepository extends JpaRepository<SupportConversation, String> {

    Optional<SupportConversation> findByBusinessId(String businessId);

    List<SupportConversation> findByStatusOrderByLastMessageAtDesc(String status);

    List<SupportConversation> findAllByOrderByLastMessageAtDesc();

    /** Platform inbox rows with an unread count of tenant messages per conversation. */
    @Query("""
            SELECT c
            FROM SupportConversation c
            WHERE (:status IS NULL OR c.status = :status)
            ORDER BY COALESCE(c.lastMessageAt, c.createdAt) DESC
            """)
    List<SupportConversation> listForAdmin(@Param("status") String status);

    @Query("""
            SELECT COUNT(c)
            FROM SupportConversation c
            WHERE c.status = :status
            """)
    long countByStatus(@Param("status") String status);
}
