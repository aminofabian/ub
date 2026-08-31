package zelisline.ub.messages.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.messages.domain.ContactMessage;
import zelisline.ub.messages.domain.ContactMessageScope;
import zelisline.ub.messages.domain.ContactMessageStatus;

public interface ContactMessageRepository extends JpaRepository<ContactMessage, String> {

    Page<ContactMessage> findByScopeAndBusinessIdOrderByCreatedAtDesc(
            ContactMessageScope scope, String businessId, Pageable pageable);

    Page<ContactMessage> findByScopeAndBusinessIdAndStatusOrderByCreatedAtDesc(
            ContactMessageScope scope, String businessId, ContactMessageStatus status, Pageable pageable);

    Page<ContactMessage> findByScopeOrderByCreatedAtDesc(ContactMessageScope scope, Pageable pageable);

    Page<ContactMessage> findByScopeAndStatusOrderByCreatedAtDesc(
            ContactMessageScope scope, ContactMessageStatus status, Pageable pageable);

    Optional<ContactMessage> findByIdAndScopeAndBusinessId(
            String id, ContactMessageScope scope, String businessId);

    Optional<ContactMessage> findByIdAndScope(String id, ContactMessageScope scope);

    /**
     * Desktop pull (docs/scopes/DESKTOP_MESSAGES_SCOPE.md §7.4): ids of the
     * shop's TENANT-scope messages that are <em>active</em> after {@code since}
     * — created after it, or holding a reply created after it (activity cursor,
     * so a reply to an old message re-activates the thread). Ordered by activity
     * ascending so the till can advance its cursor past everything it has seen
     * with no gaps, and capped so the till pages through in bounded batches.
     */
    @Query(value = """
            SELECT m.id
            FROM contact_messages m
            LEFT JOIN contact_message_replies r ON r.contact_message_id = m.id
            WHERE m.scope = 'TENANT' AND m.business_id = :businessId
              AND (m.created_at > :since OR r.created_at > :since)
            GROUP BY m.id
            ORDER BY GREATEST(m.created_at, COALESCE(MAX(r.created_at), m.created_at)) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<String> findActiveSinceIds(
            @Param("businessId") String businessId,
            @Param("since") java.time.Instant since,
            @Param("limit") int limit);
}
