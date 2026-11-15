package zelisline.ub.restaurant.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.restaurant.domain.MenuItem;
import zelisline.ub.restaurant.repository.MenuItemRepository;
import zelisline.ub.serviceops.application.ServiceResourceService;

/**
 * How far a food business has got with setting itself up, for the setup progress banner: is there
 * anything on the menu to ring, are there tables to seat people at, does any dish say what it is
 * made of. Read-only and scoped to one business.
 */
@Service
@RequiredArgsConstructor
public class RestaurantSetupFacts {

    private final MenuItemRepository menuItemRepository;
    private final ServiceResourceService resourceService;
    private final RecipeService recipeService;

    public record Facts(
            /** Menu items that are switched on and can be rung: a dish with a price, or a product. */
            long menuItems,
            /** Tables in use, at any branch. */
            long tables,
            /** At least one dish or choice has a recipe. */
            boolean anyRecipe
    ) {
    }

    @Transactional(readOnly = true)
    public Facts facts(String businessId) {
        long menuItems = 0;
        for (MenuItem item : menuItemRepository.findByBusinessIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(businessId)) {
            if (!item.isActive()) {
                continue;
            }
            boolean dishWithPrice = MenuService.KIND_DISH.equals(item.getKind())
                    && item.getPrice() != null && item.getPrice().signum() > 0;
            if (dishWithPrice || MenuService.KIND_ITEM.equals(item.getKind())) {
                menuItems++;
            }
        }
        return new Facts(menuItems, resourceService.countTables(businessId), recipeService.anyRecipe(businessId));
    }
}
