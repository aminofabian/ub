package zelisline.ub.pharmacy.api.dto;

public record MedicineClassCountsResponse(
        long total,
        long unclassified,
        long otc,
        long prescriptionOnly,
        long controlled
) {
}
