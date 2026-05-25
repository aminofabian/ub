package zelisline.ub.grocery.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.grocery.domain.GroceryInvoice;

public interface GroceryInvoiceRepository extends JpaRepository<GroceryInvoice, String> {

    Optional<GroceryInvoice> findByIdAndBusinessId(String id, String businessId);

    Optional<GroceryInvoice> findByBarcodeCodeAndBusinessId(String barcodeCode, String businessId);

    @Query("""
            select gi from GroceryInvoice gi
             where gi.businessId = :businessId
               and gi.branchId = :branchId
               and gi.status = :status
             order by gi.createdAt desc
            """)
    List<GroceryInvoice> findByBusinessIdAndBranchIdAndStatusOrderByCreatedAtDesc(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status
    );

    @Query("""
            select gi from GroceryInvoice gi
             where gi.businessId = :businessId
               and gi.branchId = :branchId
             order by gi.createdAt desc
            """)
    List<GroceryInvoice> findByBusinessIdAndBranchIdOrderByCreatedAtDesc(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId
    );

    @Query("""
            select gi from GroceryInvoice gi
             where gi.businessId = :businessId
               and gi.branchId = :branchId
               and gi.createdBy = :createdBy
             order by gi.createdAt desc
            """)
    List<GroceryInvoice> findByBusinessIdAndBranchIdAndCreatedByOrderByCreatedAtDesc(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("createdBy") String createdBy
    );

    @Query("""
            select gi from GroceryInvoice gi
             where gi.businessId = :businessId
               and gi.branchId = :branchId
               and gi.status = :status
               and gi.createdBy = :createdBy
             order by gi.createdAt desc
            """)
    List<GroceryInvoice> findByBusinessIdAndBranchIdAndStatusAndCreatedByOrderByCreatedAtDesc(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("status") String status,
            @Param("createdBy") String createdBy
    );

    @Query("""
            select gi from GroceryInvoice gi
             where gi.status = :status
               and gi.expiresAt <= :cutoff
            """)
    List<GroceryInvoice> findByStatusAndExpiresAtBefore(
            @Param("status") String status,
            @Param("cutoff") Instant cutoff
    );

    @Query("""
            select gi from GroceryInvoice gi
             where gi.status = :status
               and gi.lockExpiresAt is not null
               and gi.lockExpiresAt <= :cutoff
            """)
    List<GroceryInvoice> findByStatusAndLockExpiresAtBefore(
            @Param("status") String status,
            @Param("cutoff") Instant cutoff
    );
}
