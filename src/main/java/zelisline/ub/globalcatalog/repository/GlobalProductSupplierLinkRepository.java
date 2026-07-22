package zelisline.ub.globalcatalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.globalcatalog.domain.GlobalProductSupplierLink;

public interface GlobalProductSupplierLinkRepository
        extends JpaRepository<GlobalProductSupplierLink, GlobalProductSupplierLink.Pk> {

    List<GlobalProductSupplierLink> findByGlobalProductId(String globalProductId);

    List<GlobalProductSupplierLink> findByGlobalSupplierTemplateId(String globalSupplierTemplateId);

    Optional<GlobalProductSupplierLink> findByGlobalProductIdAndGlobalSupplierTemplateId(
            String globalProductId, String globalSupplierTemplateId);

    @Query("""
            select l from GlobalProductSupplierLink l
             where l.globalProductId = :globalProductId
               and l.primary = true
            """)
    Optional<GlobalProductSupplierLink> findPrimaryByGlobalProductId(
            @Param("globalProductId") String globalProductId);

    void deleteByGlobalProductIdAndGlobalSupplierTemplateId(String globalProductId, String globalSupplierTemplateId);
}
