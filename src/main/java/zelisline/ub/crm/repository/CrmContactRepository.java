package zelisline.ub.crm.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.crm.domain.CrmContact;

public interface CrmContactRepository extends JpaRepository<CrmContact, String> {

    Optional<CrmContact> findByBusinessIdAndPhoneE164(String businessId, String phoneE164);

    /** Audience source for broadcasts (oldest first); the service caps + filters in memory (M4). */
    List<CrmContact> findByBusinessIdOrderByCreatedAtAsc(String businessId);
}
