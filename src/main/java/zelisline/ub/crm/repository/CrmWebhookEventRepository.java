package zelisline.ub.crm.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.crm.domain.CrmWebhookEvent;

public interface CrmWebhookEventRepository extends JpaRepository<CrmWebhookEvent, String> {

    boolean existsByWamid(String wamid);

    Page<CrmWebhookEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Unrouted inbound traffic — the super-admin triage queue. */
    Page<CrmWebhookEvent> findByBusinessIdIsNullOrderByCreatedAtDesc(Pageable pageable);
}
