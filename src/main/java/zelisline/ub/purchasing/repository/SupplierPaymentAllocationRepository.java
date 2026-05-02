package zelisline.ub.purchasing.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.purchasing.domain.SupplierPaymentAllocation;

public interface SupplierPaymentAllocationRepository extends JpaRepository<SupplierPaymentAllocation, String> {

    @Query("select coalesce(sum(a.amount), 0) from SupplierPaymentAllocation a where a.supplierInvoiceId = :invId")
    java.math.BigDecimal sumAmountBySupplierInvoiceId(@Param("invId") String supplierInvoiceId);
}
