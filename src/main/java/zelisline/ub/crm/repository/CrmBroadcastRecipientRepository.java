package zelisline.ub.crm.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.crm.domain.CrmBroadcastRecipient;

public interface CrmBroadcastRecipientRepository extends JpaRepository<CrmBroadcastRecipient, String> {

    List<CrmBroadcastRecipient> findByBroadcastIdOrderByCreatedAtAsc(String broadcastId);

    Page<CrmBroadcastRecipient> findByBroadcastIdOrderByCreatedAtAsc(String broadcastId, Pageable pageable);

    /** Delivery/read callbacks arrive by wamid; unique across messages. */
    Optional<CrmBroadcastRecipient> findByWaMessageId(String waMessageId);

    long countByBroadcastIdAndStatus(String broadcastId, String status);
}
