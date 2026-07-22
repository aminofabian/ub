package zelisline.ub.tenancy.application;

import java.util.List;

import zelisline.ub.tenancy.api.dto.StoreSectionStarterKitResponse;

/**
 * Canonical department starter kits for post-signup onboarding and item-type setup.
 */
public final class StoreSectionStarterKitCatalog {

    private StoreSectionStarterKitCatalog() {}

    public static final List<StoreSectionStarterKitResponse> KITS = List.of(
            new StoreSectionStarterKitResponse(
                    "butchery",
                    "Butchery",
                    List.of(
                            "Beef",
                            "Goat (Chevon)",
                            "Mutton",
                            "Chicken",
                            "Pork",
                            "Fish",
                            "Smoked Meat",
                            "Sausages",
                            "Smokies",
                            "Eggs",
                            "Offals",
                            "Bones",
                            "Minced Meat",
                            "Value-added Meat"
                    )
            ),
            new StoreSectionStarterKitResponse(
                    "mini-mart",
                    "Mini mart",
                    List.of(
                            "General Shop",
                            "Grocery",
                            "Grains & Cereals",
                            "Spices & Seasoning",
                            "Fruits & Vegetables",
                            "Beverages",
                            "Snacks & Confectionery",
                            "Dairy & Refrigerated",
                            "Bakery",
                            "Household Goods",
                            "Cosmetics & Beauty",
                            "Baby Products",
                            "Electronics & Accessories",
                            "Liquor & Wines"
                    )
            ),
            new StoreSectionStarterKitResponse(
                    "full-grocery",
                    "Full grocery",
                    List.of(
                            "Grocery",
                            "Snacks",
                            "Beverages",
                            "Dairy",
                            "Bakery",
                            "Spices",
                            "Grains",
                            "Meat & fish",
                            "Frozen",
                            "Fruits",
                            "Vegetables",
                            "Personal care",
                            "Household",
                            "Liquor"
                    )
            ),
            new StoreSectionStarterKitResponse(
                    "produce-shop",
                    "Produce shop",
                    List.of(
                            "Fruits",
                            "Vegetables",
                            "Grocery",
                            "Dairy",
                            "Bakery",
                            "Meat & fish"
                    )
            ),
            new StoreSectionStarterKitResponse(
                    "mixed-shop",
                    "Mixed shop",
                    List.of(
                            "Grocery",
                            "Fruits",
                            "Vegetables",
                            "Electronics",
                            "Mali mali",
                            "Beverages",
                            "Snacks"
                    )
            ),
            new StoreSectionStarterKitResponse(
                    "cosmetics",
                    "Cosmetics",
                    List.of(
                            "Skin care",
                            "Hair care",
                            "Make-up",
                            "Fragrances",
                            "Personal care",
                            "Bath & body",
                            "Nails",
                            "Men's grooming",
                            "Baby care",
                            "Accessories"
                    )
            ),
            new StoreSectionStarterKitResponse(
                    "wines-spirits",
                    "Wines & spirits",
                    List.of(
                            "Beer",
                            "Wine",
                            "Spirits",
                            "RTD & cider",
                            "Mixers",
                            "Soft drinks",
                            "Snacks",
                            "Tobacco",
                            "Glassware & accessories"
                    )
            )
    );

    public static StoreSectionStarterKitResponse findById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String key = id.trim();
        for (StoreSectionStarterKitResponse kit : KITS) {
            if (kit.id().equals(key)) {
                return kit;
            }
        }
        return null;
    }
}
