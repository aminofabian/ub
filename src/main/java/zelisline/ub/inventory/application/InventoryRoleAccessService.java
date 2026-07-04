package zelisline.ub.inventory.application;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.tenancy.api.dto.StockLevelsSettingsResponse;
import zelisline.ub.tenancy.api.dto.StocktakeSettingsResponse;
import zelisline.ub.tenancy.application.BusinessInventorySettingsService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Delegates {@code inventory.read} / {@code inventory.write} to restricted
 * roles when an admin has enabled it in business inventory settings.
 */
@Service
@RequiredArgsConstructor
public class InventoryRoleAccessService {

    private static final String STOCK_MANAGER = "stock_manager";
    private static final String GROCERY_CLERK = "grocery_clerk";

    private final BusinessRepository businessRepository;
    private final RoleRepository roleRepository;
    private final BusinessInventorySettingsService businessInventorySettingsService;

    public boolean grantsDelegatedInventoryWrite(String businessId, String roleId) {
        StockLevelsSettingsResponse settings = readStockLevels(businessId);
        return switch (resolveRoleKey(roleId)) {
            case STOCK_MANAGER -> settings.allowStockEditForStockManager();
            case GROCERY_CLERK -> settings.allowStockEditForGroceryClerk();
            default -> false;
        };
    }

    /**
     * Grocery clerks normally have no inventory access; when stock editing is
     * enabled they also need read access for the Stock page and allocation preview.
     */
    public boolean grantsDelegatedInventoryRead(String businessId, String roleId) {
        if (!GROCERY_CLERK.equals(resolveRoleKey(roleId))) {
            return false;
        }
        return readStockLevels(businessId).allowStockEditForGroceryClerk();
    }

    /**
     * Whether counters may see on-hand system quantity during stock take / daily audit.
     * Owners, admins, and users with {@code stocktake.approve} always see it.
     */
    public boolean canSeeSystemStockDuringCount(
            String businessId,
            String roleId,
            boolean hasStocktakeApprove
    ) {
        if (hasStocktakeApprove) {
            return true;
        }
        String roleKey = resolveRoleKey(roleId);
        if ("owner".equals(roleKey) || "admin".equals(roleKey)) {
            return true;
        }
        if (!STOCK_MANAGER.equals(roleKey)) {
            return false;
        }
        return readStocktake(businessId).showSystemStockToStockManager();
    }

    private StocktakeSettingsResponse readStocktake(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return StocktakeSettingsResponse.defaults();
        }
        return businessRepository.findById(businessId.trim())
                .map(Business::getSettings)
                .map(businessInventorySettingsService::readFromSettingsJson)
                .map(inventory -> inventory.stocktake())
                .orElse(StocktakeSettingsResponse.defaults());
    }

    private StockLevelsSettingsResponse readStockLevels(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return StockLevelsSettingsResponse.defaults();
        }
        return businessRepository.findById(businessId.trim())
                .map(Business::getSettings)
                .map(businessInventorySettingsService::readFromSettingsJson)
                .map(inventory -> inventory.stockLevels())
                .orElse(StockLevelsSettingsResponse.defaults());
    }

    private String resolveRoleKey(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return "";
        }
        return roleRepository.findById(roleId.trim())
                .map(Role::getRoleKey)
                .map(key -> key == null ? "" : key.trim().toLowerCase())
                .orElse("");
    }
}
