package zelisline.ub.messages.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
