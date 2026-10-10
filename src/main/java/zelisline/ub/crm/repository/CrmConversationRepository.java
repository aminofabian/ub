package zelisline.ub.crm.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.crm.domain.CrmConversation;

public interface CrmConversationRepository extends JpaRepository<CrmConversation, String> {

    Optional<CrmConversation> findFirstByBusinessIdAndContactIdAndStatusOrderByUpdatedAtDesc(
            String businessId, String contactId, String status);

    Optional<CrmConversation> findByIdAndBusinessId(String id, String businessId);

    @Query("""
            select c from CrmConversation c
            where c.businessId = :businessId
              and (:status is null or c.status = :status)
              and (:assigneeId is null or c.assignedUserId = :assigneeId)
            order by c.lastMessageAt desc, c.createdAt desc
            """)
    Page<CrmConversation> search(
            @Param("businessId") String businessId,
            @Param("status") String status,
            @Param("assigneeId") String assigneeId,
            Pageable pageable);

    /** Contacts with an open Meta 24h service window — the reachable audience for a free-form
     *  broadcast (M4). One row per contact; duplicates collapsed by the caller via a set. */
    @Query("""
            select distinct c.contactId from CrmConversation c
            where c.businessId = :businessId
              and c.windowExpiresAt is not null
              and c.windowExpiresAt > :now
            """)
    List<String> findContactIdsWithOpenWindow(
            @Param("businessId") String businessId,
            @Param("now") Instant now);
}
