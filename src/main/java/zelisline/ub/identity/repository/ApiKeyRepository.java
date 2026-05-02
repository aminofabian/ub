package zelisline.ub.identity.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.identity.domain.ApiKey;

public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {

    Page<ApiKey> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    Optional<ApiKey> findByIdAndBusinessId(String id, String businessId);

    void deleteAllByBusinessId(String businessId);
}
