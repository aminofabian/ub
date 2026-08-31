package zelisline.ub.catalog.api.dto;

public record GenerateProductDescriptionResponse(
        String description,
        String categoryId,
        String categoryName,
        boolean createCategory,
        String itemTypeId,
        String itemTypeName,
        boolean createItemType
) {
    public static GenerateProductDescriptionResponse descriptionOnly(String description) {
        return new GenerateProductDescriptionResponse(
                description, null, null, false, null, null, false);
    }
}
