package zelisline.ub.catalog.api.dto;

/** JPQL constructor target for row-type aggregate queries (3 fields only). */
public record CatalogRowTypeSum(
        long parents,
        long variants,
        long standalones
) {
}
