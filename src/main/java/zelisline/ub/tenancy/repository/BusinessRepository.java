package zelisline.ub.tenancy.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.tenancy.domain.Business;

public interface BusinessRepository extends JpaRepository<Business, String> {
    @Query(value = "SELECT settings FROM businesses WHERE id = :id", nativeQuery = true)
    Optional<String> findSettingsJsonById(@Param("id") String id);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndDeletedAtIsNull(String slug);

    Optional<Business> findBySlug(String slug);

    Optional<Business> findBySlugAndDeletedAtIsNull(String slug);

    Optional<Business> findByIdAndDeletedAtIsNull(String id);

    Page<Business> findByDeletedAtIsNull(Pageable pageable);
}
