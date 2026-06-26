package zelisline.ub.globalcatalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.globalcatalog.domain.GlobalProduct;

public interface GlobalProductRepository extends JpaRepository<GlobalProduct, String> {

    Optional<GlobalProduct> findByIdAndCatalogIdAndStatus(String id, String catalogId, String status);

    List<GlobalProduct> findByCatalogIdAndStatusOrderBySortOrderAscNameAsc(String catalogId, String status);

    @Query("""
            select gp from GlobalProduct gp
             where gp.catalogId = :catalogId
               and gp.status = :status
               and (:categoryId is null or gp.globalCategoryId = :categoryId)
               and (:q is null or :q = ''
                    or lower(gp.name) like lower(concat('%', :q, '%'))
                    or lower(coalesce(gp.brand, '')) like lower(concat('%', :q, '%'))
                    or lower(coalesce(gp.barcode, '')) like lower(concat('%', :q, '%')))
               and (:barcode is null or :barcode = '' or gp.barcode = :barcode)
            """)
    Page<GlobalProduct> search(
            @Param("catalogId") String catalogId,
            @Param("status") String status,
            @Param("categoryId") String categoryId,
            @Param("q") String q,
            @Param("barcode") String barcode,
            Pageable pageable);

    @Query("""
            select gp.id from GlobalProduct gp
             where gp.catalogId = :catalogId
               and gp.status = :status
               and gp.id in :ids
            """)
    List<String> findPublishedIdsByCatalogAndIdIn(
            @Param("catalogId") String catalogId,
            @Param("status") String status,
            @Param("ids") List<String> ids);

    @Query("""
            select gp from GlobalProduct gp
             where gp.catalogId = :catalogId
               and gp.status = :status
               and (gp.barcode = :barcode or lower(gp.name) like lower(concat('%', :q, '%')))
             order by gp.sortOrder asc, gp.name asc
            """)
    List<GlobalProduct> lookup(
            @Param("catalogId") String catalogId,
            @Param("status") String status,
            @Param("barcode") String barcode,
            @Param("q") String q,
            Pageable pageable);
}
