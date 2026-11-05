package zelisline.ub.globalcatalog.repository;

import java.util.Collection;
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

    List<GlobalProduct> findByCatalogId(String catalogId);

    List<GlobalProduct> findByCatalogIdAndStatusAndVariantOfGlobalProductIdIn(
            String catalogId, String status, Collection<String> parentIds);

    List<GlobalProduct> findByCatalogIdAndStatusOrderBySortOrderAscNameAsc(String catalogId, String status);

    @Query("""
            select gp from GlobalProduct gp
             where gp.catalogId = :catalogId
               and gp.status = :status
               and (:categoryIdsEmpty = true or gp.globalCategoryId in :categoryIds)
               and (:q is null or :q = ''
                    or lower(gp.name) like lower(concat('%', :q, '%'))
                    or lower(coalesce(gp.brand, '')) like lower(concat('%', :q, '%'))
                    or lower(coalesce(gp.barcode, '')) like lower(concat('%', :q, '%')))
               and (:barcode is null or :barcode = '' or gp.barcode = :barcode)
               and (:storeKitsEmpty = true
                    or gp.storeKitId is null
                    or gp.storeKitId in :storeKits)
            """)
    Page<GlobalProduct> search(
            @Param("catalogId") String catalogId,
            @Param("status") String status,
            @Param("categoryIds") Collection<String> categoryIds,
            @Param("categoryIdsEmpty") boolean categoryIdsEmpty,
            @Param("q") String q,
            @Param("barcode") String barcode,
            @Param("storeKits") Collection<String> storeKits,
            @Param("storeKitsEmpty") boolean storeKitsEmpty,
            Pageable pageable);

    /**
     * SA Curate search. {@code structure}: {@code all} | {@code parents} (no parent link) |
     * {@code variants} (has a parent link) | {@code orphans} (parent link points at a
     * missing or archived product).
     */
    @Query("""
            select gp from GlobalProduct gp
             where gp.catalogId = :catalogId
               and (:status is null or :status = '' or gp.status = :status)
               and (:categoryId is null or gp.globalCategoryId = :categoryId)
               and (:missingImage = false
                    or gp.imageUrl is null
                    or gp.imageUrl = '')
               and (:q is null or :q = ''
                    or lower(gp.name) like lower(concat('%', :q, '%'))
                    or lower(coalesce(gp.brand, '')) like lower(concat('%', :q, '%'))
                    or lower(coalesce(gp.barcode, '')) like lower(concat('%', :q, '%')))
               and (:structure = 'all'
                    or (:structure = 'parents'
                        and (gp.variantOfGlobalProductId is null or gp.variantOfGlobalProductId = ''))
                    or (:structure = 'variants'
                        and gp.variantOfGlobalProductId is not null
                        and gp.variantOfGlobalProductId <> '')
                    or (:structure = 'orphans'
                        and gp.variantOfGlobalProductId is not null
                        and gp.variantOfGlobalProductId <> ''
                        and not exists (select 1 from GlobalProduct par
                                         where par.id = gp.variantOfGlobalProductId
                                           and par.status <> 'archived')))
            """)
    Page<GlobalProduct> searchForSuperAdmin(
            @Param("catalogId") String catalogId,
            @Param("status") String status,
            @Param("categoryId") String categoryId,
            @Param("q") String q,
            @Param("missingImage") boolean missingImage,
            @Param("structure") String structure,
            Pageable pageable);

    List<GlobalProduct> findByCatalogIdAndVariantOfGlobalProductIdInOrderBySortOrderAscNameAsc(
            String catalogId, Collection<String> parentIds);

    List<GlobalProduct> findByCatalogIdAndVariantOfGlobalProductIdOrderBySortOrderAscNameAsc(
            String catalogId, String parentId);

    long countByCatalogIdAndBarcodeAndStatusNotAndIdNot(
            String catalogId, String barcode, String status, String id);

    long countByCatalogIdAndBarcodeAndStatusNot(String catalogId, String barcode, String status);

    Optional<GlobalProduct> findFirstByCatalogIdAndBarcodeAndStatusNotOrderByCreatedAtAsc(
            String catalogId, String barcode, String status);

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
}
