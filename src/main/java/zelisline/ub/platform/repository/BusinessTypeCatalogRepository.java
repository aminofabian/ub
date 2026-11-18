package zelisline.ub.platform.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.platform.domain.BusinessTypeCatalogEntry;

public interface BusinessTypeCatalogRepository
        extends JpaRepository<BusinessTypeCatalogEntry, String> {

    /** Ordered for display: by section-agnostic display order, then code for a stable tie-break. */
    List<BusinessTypeCatalogEntry> findAllByOrderByDisplayOrderAscCodeAsc();
}
