package zelisline.ub.identity.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.identity.domain.UserItemType;

public interface UserItemTypeRepository extends JpaRepository<UserItemType, UserItemType.Id> {

    @Query("""
            select uit.id.itemTypeId
              from UserItemType uit
             where uit.id.userId = :userId
            """)
    List<String> findItemTypeIdsByUserId(@Param("userId") String userId);

    @Modifying
    @Query("delete from UserItemType uit where uit.id.userId = :userId")
    int deleteAllByUserId(@Param("userId") String userId);
}
