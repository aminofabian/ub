package zelisline.ub.inventory.api.dto;

public record BatchSafetyCountsResponse(
        long quarantined,
        long expired,
        long shortDated,
        int shortDatedDays,
        long medicineMissingDetails
) {
}
