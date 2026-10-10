package zelisline.ub.crm.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.crm.domain.CrmWebhookEvent;

public interface CrmWebhookEventRepository extends JpaRepository<CrmWebhookEvent, String> {

    boolean existsByWamid(String wamid);
}
