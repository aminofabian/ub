package zelisline.ub.tenancy.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.tenancy.domain.DomainMapping;

public interface DomainMappingRepository extends JpaRepository<DomainMapping, String> {
    Optional<DomainMapping> findByDomainAndActiveTrue(String domain);

    List<DomainMapping> findByBusinessIdAndDeletedAtIsNull(String businessId);
}
