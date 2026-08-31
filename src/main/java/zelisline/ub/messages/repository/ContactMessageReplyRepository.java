package zelisline.ub.messages.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.messages.domain.ContactMessageReply;

public interface ContactMessageReplyRepository extends JpaRepository<ContactMessageReply, String> {

    List<ContactMessageReply> findByContactMessageIdOrderByCreatedAtAsc(String contactMessageId);

    List<ContactMessageReply> findByContactMessageIdInOrderByCreatedAtAsc(
            java.util.Collection<String> contactMessageIds);

    /**
     * Replies queued on a desktop till that still need relaying to the shop's
     * online instance. Restricted to the till's own business (TENANT-scope
     * messages only — PLATFORM messages have no business id, so the subquery
     * naturally excludes them).
     */
    @Query("""
            SELECT r FROM ContactMessageReply r
            WHERE r.cloudSyncedAt IS NULL
              AND r.outcome = 'queued'
              AND r.contactMessageId IN (
                  SELECT m.id FROM ContactMessage m WHERE m.businessId = :businessId
              )
            ORDER BY r.createdAt ASC
            """)
    List<ContactMessageReply> findQueuedForDesktopSync(
            @Param("businessId") String businessId, Pageable pageable);
}
