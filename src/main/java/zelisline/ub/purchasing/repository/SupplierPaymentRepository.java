package zelisline.ub.purchasing.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.purchasing.domain.SupplierPayment;

public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, String> {

    @Query("""
            select p from SupplierPayment p
            where p.supplierId in :supplierIds
            order by p.paidAt desc
            """)
    List<SupplierPayment> findBySupplierIdInOrderByPaidAtDesc(
            @Param("supplierIds") Collection<String> supplierIds,
            Pageable pageable
    );
}
