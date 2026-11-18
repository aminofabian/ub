package zelisline.ub.platform.api.dto;

/**
 * One business type as shown to merchants and Super Admin (ADR 0029). The merged read model:
 * built-in defaults overlaid by the {@code business_type_catalog} row. {@code imageUrl} is null
 * until an image is uploaded.
 */
public record BusinessTypeResponse(
        String code,
        String label,
        String hint,
        String section,
        int displayOrder,
        boolean enabled,
        String imageUrl) {}
