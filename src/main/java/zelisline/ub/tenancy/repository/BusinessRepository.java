package zelisline.ub.tenancy.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.tenancy.domain.Business;

public interface BusinessRepository extends JpaRepository<Business, String> {
    boolean existsBySlug(String slug);

    Optional<Business> findBySlug(String slug);

    Optional<Business> findByIdAndDeletedAtIsNull(String id);
}
