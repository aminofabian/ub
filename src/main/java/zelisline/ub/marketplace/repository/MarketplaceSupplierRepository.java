package zelisline.ub.marketplace.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.marketplace.domain.MarketplaceSupplier;

public interface MarketplaceSupplierRepository extends JpaRepository<MarketplaceSupplier, String> {

    @Query("""
            SELECT m FROM MarketplaceSupplier m
            WHERE (:status IS NULL OR m.status = :status)
              AND (
                :q IS NULL
                OR LOWER(m.name) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(m.contactEmail, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(m.contactPhone, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(m.username, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(m.supplierNumber, '')) LIKE LOWER(CONCAT('%', :q, '%'))
              )
            """)
    Page<MarketplaceSupplier> search(
            @Param("q") String q,
            @Param("status") String status,
            Pageable pageable);

    long countByStatus(String status);

    Optional<MarketplaceSupplier> findByIdAndStatus(String id, String status);

    Optional<MarketplaceSupplier> findByUsernameIgnoreCase(String username);

    Optional<MarketplaceSupplier> findFirstByContactPhoneOrderByCreatedAtAsc(String contactPhone);

    Optional<MarketplaceSupplier> findBySupplierNumber(String supplierNumber);

    boolean existsByUsernameIgnoreCase(String username);
}
