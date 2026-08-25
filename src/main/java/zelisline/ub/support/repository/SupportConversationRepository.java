package zelisline.ub.support.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.support.domain.SupportConversation;

public interface SupportConversationRepository extends JpaRepository<SupportConversation, String> {

    /** The one TENANT thread of a business (never a STOREFRONT buyer thread). */
    Optional<SupportConversation> findByConversationTypeAndBusinessId(String conversationType, String businessId);

    /** A guest's existing thread for (type, business) — one per guest. */
    Optional<SupportConversation> findByConversationTypeAndBusinessIdAndGuestId(
            String conversationType, String businessId, String guestId);

    /** Every thread a guest owns across businesses (token check on ticket mint). */
    List<SupportConversation> findByGuestId(String guestId);

    /** Newest activity first; conversations with no messages sort by creation. */
    @Query("""
            SELECT c FROM SupportConversation c
            WHERE c.status = :status AND c.conversationType = :conversationType
            ORDER BY COALESCE(c.lastMessageAt, c.createdAt) DESC
            """)
    List<SupportConversation> findByStatusAndConversationTypeOrderByLastMessageAtDesc(
            @Param("status") String status, @Param("conversationType") String conversationType);

    @Query("""
            SELECT c FROM SupportConversation c
            WHERE c.conversationType = :conversationType
            ORDER BY COALESCE(c.lastMessageAt, c.createdAt) DESC
            """)
    List<SupportConversation> findAllByConversationTypeOrderByLastMessageAtDesc(
            @Param("conversationType") String conversationType);

    /** Storefront buyer threads belonging to one tenant (staff inbox tab). */
    @Query("""
            SELECT c FROM SupportConversation c
            WHERE c.conversationType = :conversationType AND c.businessId = :businessId
            ORDER BY COALESCE(c.lastMessageAt, c.createdAt) DESC
            """)
    List<SupportConversation> findByConversationTypeAndBusinessIdOrderByLastMessageAtDesc(
            @Param("conversationType") String conversationType, @Param("businessId") String businessId);

    @Query("""
            SELECT c FROM SupportConversation c
            WHERE c.conversationType = :conversationType
              AND c.businessId = :businessId
              AND c.status = :status
            ORDER BY COALESCE(c.lastMessageAt, c.createdAt) DESC
            """)
    List<SupportConversation> findByConversationTypeAndBusinessIdAndStatusOrderByLastMessageAtDesc(
            @Param("conversationType") String conversationType,
            @Param("businessId") String businessId,
            @Param("status") String status);

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
