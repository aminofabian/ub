package zelisline.ub.pharmacy.api.dto;

/** One of the asked-about products that is a medicine (so receiving it needs a lot and an expiry). */
public record MedicineAmongResponse(String itemId, String name) {
}
