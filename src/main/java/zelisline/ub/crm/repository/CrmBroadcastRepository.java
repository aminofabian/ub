package zelisline.ub.crm.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.crm.domain.CrmBroadcast;

public interface CrmBroadcastRepository extends JpaRepository<CrmBroadcast, String> {

    Page<CrmBroadcast> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    Optional<CrmBroadcast> findByIdAndBusinessId(String id, String businessId);
}
