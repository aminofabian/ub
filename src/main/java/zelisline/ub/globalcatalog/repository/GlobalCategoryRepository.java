package zelisline.ub.globalcatalog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.globalcatalog.domain.GlobalCategory;

public interface GlobalCategoryRepository extends JpaRepository<GlobalCategory, String> {

    List<GlobalCategory> findByCatalogIdAndActiveTrueOrderByPositionAsc(String catalogId);
}
