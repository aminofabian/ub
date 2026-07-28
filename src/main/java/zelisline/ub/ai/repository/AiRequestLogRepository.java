package zelisline.ub.ai.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.ai.domain.AiRequestLog;

public interface AiRequestLogRepository extends JpaRepository<AiRequestLog, String> {

    Optional<AiRequestLog> findByIdAndBusinessId(String id, String businessId);
}
