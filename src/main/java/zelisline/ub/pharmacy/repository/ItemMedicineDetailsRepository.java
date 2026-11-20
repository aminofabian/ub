package zelisline.ub.pharmacy.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.pharmacy.domain.DispensingClass;
import zelisline.ub.pharmacy.domain.ItemMedicineDetails;

public interface ItemMedicineDetailsRepository extends JpaRepository<ItemMedicineDetails, String> {

    Optional<ItemMedicineDetails> findByItemIdAndBusinessId(String itemId, String businessId);

    List<ItemMedicineDetails> findByBusinessIdAndItemIdIn(String businessId, Collection<String> itemIds);

    boolean existsByBusinessIdAndItemIdIn(String businessId, Collection<String> itemIds);

    /** True once the shop has marked at least one product as a medicine. */
    boolean existsByBusinessId(String businessId);

    @Query("select d.itemId from ItemMedicineDetails d where d.businessId = :businessId")
    List<String> findItemIdsByBusinessId(@Param("businessId") String businessId);

    List<ItemMedicineDetails> findByBusinessIdAndDispensingClass(
            String businessId, zelisline.ub.pharmacy.domain.DispensingClass dispensingClass);

    /** Rows are {@code [ItemMedicineDetails, Item]}; deleted items are left out. */
    @Query("""
            select d, i
              from ItemMedicineDetails d
              join zelisline.ub.catalog.domain.Item i on i.id = d.itemId
             where d.businessId = :businessId
               and i.businessId = :businessId
               and i.deletedAt is null
               and (:anyClass = true or d.dispensingClass = :cls)
               and (:q is null
                    or lower(i.name) like :q
                    or lower(coalesce(d.genericName, '')) like :q
                    or lower(coalesce(i.sku, '')) like :q
                    or lower(coalesce(i.barcode, '')) like :q)
             order by i.name asc, i.id asc
            """)
    List<Object[]> search(
            @Param("businessId") String businessId,
            @Param("anyClass") boolean anyClass,
            @Param("cls") DispensingClass cls,
            @Param("q") String likePattern,
            Pageable pageable
    );

    @Query("""
            select count(d)
              from ItemMedicineDetails d
              join zelisline.ub.catalog.domain.Item i on i.id = d.itemId
             where d.businessId = :businessId
               and i.businessId = :businessId
               and i.deletedAt is null
               and (:anyClass = true or d.dispensingClass = :cls)
               and (:q is null
                    or lower(i.name) like :q
                    or lower(coalesce(d.genericName, '')) like :q
                    or lower(coalesce(i.sku, '')) like :q
                    or lower(coalesce(i.barcode, '')) like :q)
            """)
    long countSearch(
            @Param("businessId") String businessId,
            @Param("anyClass") boolean anyClass,
            @Param("cls") DispensingClass cls,
            @Param("q") String likePattern
    );

    /** Rows are {@code [DispensingClass, Long]}. */
    @Query("""
            select d.dispensingClass, count(d)
              from ItemMedicineDetails d
              join zelisline.ub.catalog.domain.Item i on i.id = d.itemId
             where d.businessId = :businessId
               and i.businessId = :businessId
               and i.deletedAt is null
             group by d.dispensingClass
            """)
    List<Object[]> countByClass(@Param("businessId") String businessId);

    /** Live items that are a non-OTC medicine, or a pack variant of one. */
    @Query("""
            select i.id
              from zelisline.ub.catalog.domain.Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and (i.id in (select d.itemId from ItemMedicineDetails d
                              where d.businessId = :businessId and d.dispensingClass <> :otc)
                    or (i.variantOfItemId is not null
                        and i.id not in (select d3.itemId from ItemMedicineDetails d3
                                          where d3.businessId = :businessId)
                        and i.variantOfItemId in (select d2.itemId from ItemMedicineDetails d2
                                                   where d2.businessId = :businessId
                                                     and d2.dispensingClass <> :otc)))
             order by i.id asc
            """)
    List<String> findCounterOnlyItemIds(
            @Param("businessId") String businessId,
            @Param("otc") DispensingClass otc,
            Pageable pageable
    );

    /** Ids of live, top-level items in a department that are not yet marked as medicines. */
    @Query("""
            select i.id
              from zelisline.ub.catalog.domain.Item i
             where i.businessId = :businessId
               and i.deletedAt is null
               and i.itemTypeId = :itemTypeId
               and i.id not in (select d.itemId from ItemMedicineDetails d where d.businessId = :businessId)
            """)
    List<String> findUnmarkedItemIdsInItemType(
            @Param("businessId") String businessId,
            @Param("itemTypeId") String itemTypeId
    );
}
