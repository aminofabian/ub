package zelisline.ub.restaurant.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.restaurant.domain.KitchenRequisitionLine;

public interface KitchenRequisitionLineRepository extends JpaRepository<KitchenRequisitionLine, String> {

    List<KitchenRequisitionLine> findByBusinessIdAndRequisitionIdOrderBySortOrderAsc(String businessId, String requisitionId);

    List<KitchenRequisitionLine> findByBusinessIdAndRequisitionIdInOrderBySortOrderAsc(
            String businessId, Collection<String> requisitionIds);
}
