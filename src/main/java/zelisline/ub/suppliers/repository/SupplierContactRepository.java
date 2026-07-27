package zelisline.ub.suppliers.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.suppliers.domain.SupplierContact;

public interface SupplierContactRepository extends JpaRepository<SupplierContact, String> {

    List<SupplierContact> findBySupplierIdOrderByPrimaryContactDescNameAsc(String supplierId);

    Optional<SupplierContact> findByIdAndSupplierId(String id, String supplierId);

    @Query("""
            SELECT c FROM SupplierContact c
            WHERE c.phone IS NOT NULL
              AND (
                c.phone = :phone
                OR c.phone = :altPhone
                OR c.phone LIKE CONCAT('%', :phoneTail)
              )
            """)
    List<SupplierContact> findByPhoneVariants(
            @Param("phone") String phone,
            @Param("altPhone") String altPhone,
            @Param("phoneTail") String phoneTail);

    @Query("""
            SELECT c FROM SupplierContact c
            WHERE c.email IS NOT NULL
              AND LOWER(c.email) = LOWER(:email)
            """)
    List<SupplierContact> findByEmailIgnoreCase(@Param("email") String email);

    @Query("""
            SELECT c FROM SupplierContact c, Supplier s
            WHERE c.supplierId = s.id
              AND s.businessId = :businessId
              AND s.deletedAt IS NULL
              AND (:ignoreSupplierId IS NULL OR s.id <> :ignoreSupplierId)
              AND c.phone IS NOT NULL
              AND (
                c.phone = :phone
                OR c.phone = :altPhone
                OR c.phone LIKE CONCAT('%', :phoneTail)
              )
            """)
    List<SupplierContact> findOwnBusinessByPhoneVariants(
            @Param("businessId") String businessId,
            @Param("phone") String phone,
            @Param("altPhone") String altPhone,
            @Param("phoneTail") String phoneTail,
            @Param("ignoreSupplierId") String ignoreSupplierId);

    @Query("""
            SELECT c FROM SupplierContact c, Supplier s
            WHERE c.supplierId = s.id
              AND s.businessId = :businessId
              AND s.deletedAt IS NULL
              AND (:ignoreSupplierId IS NULL OR s.id <> :ignoreSupplierId)
              AND c.email IS NOT NULL
              AND LOWER(c.email) = LOWER(:email)
            """)
    List<SupplierContact> findOwnBusinessByEmail(
            @Param("businessId") String businessId,
            @Param("email") String email,
            @Param("ignoreSupplierId") String ignoreSupplierId);
}
