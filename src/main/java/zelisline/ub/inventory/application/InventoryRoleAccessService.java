package zelisline.ub.inventory.application;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.tenancy.api.dto.ReceiveStockSettingsResponse;
import zelisline.ub.tenancy.api.dto.StockLevelsSettingsResponse;
import zelisline.ub.tenancy.api.dto.StocktakeSettingsResponse;
import zelisline.ub.tenancy.api.dto.SuppliersAccessSettingsResponse;
import zelisline.ub.tenancy.application.BusinessInventorySettingsService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Delegates inventory / supplier permissions to restricted roles when an admin
 * has enabled it in business inventory settings.
 */
@Service
@RequiredArgsConstructor
public class InventoryRoleAccessService {

    private static final String STOCK_MANAGER = "stock_manager";
    private static final String GROCERY_CLERK = "grocery_clerk";
    private static final String CASHIER = "cashier";
    private static final String BUTCHER_CASHIER = "butcher_cashier";

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

    /** Delegates {@code suppliers.write} (create/edit suppliers). */
    public boolean grantsDelegatedSupplierWrite(String businessId, String roleId) {
        SuppliersAccessSettingsResponse settings = readSuppliers(businessId);
        return switch (resolveRoleKey(roleId)) {
            case STOCK_MANAGER -> settings.allowSupplierWriteForStockManager();
            case CASHIER, BUTCHER_CASHIER -> settings.allowSupplierWriteForCashier();
            default -> false;
        };
    }

    /** Delegates {@code catalog.items.link_suppliers}. */
    public boolean grantsDelegatedLinkSuppliers(String businessId, String roleId) {
        SuppliersAccessSettingsResponse settings = readSuppliers(businessId);
        return switch (resolveRoleKey(roleId)) {
            case STOCK_MANAGER -> settings.allowLinkProductsForStockManager();
            case CASHIER, BUTCHER_CASHIER -> settings.allowLinkProductsForCashier();
            default -> false;
        };
    }

    /**
     * Delegates {@code purchasing.path_b.write} (receive supplies / receive stock)
     * for cashier and stock manager roles when enabled in business settings.
     */
    public boolean grantsDelegatedPathBWrite(String businessId, String roleId) {
        ReceiveStockSettingsResponse settings = readReceiveStock(businessId);
        return switch (resolveRoleKey(roleId)) {
            case STOCK_MANAGER -> settings.allowReceiveForStockManager();
            case CASHIER, BUTCHER_CASHIER -> settings.allowReceiveForCashier();
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
     * Owners and admins always see it. Stock managers follow
     * {@code showSystemStockToStockManager} even when they also have
     * {@code stocktake.approve}. Other roles with approve see it.
     */
    public boolean canSeeSystemStockDuringCount(
            String businessId,
            String roleId,
            boolean hasStocktakeApprove
    ) {
        String roleKey = resolveRoleKey(roleId);
        if ("owner".equals(roleKey) || "admin".equals(roleKey)) {
            return true;
        }
        // Stock managers ship with stocktake.approve; that must not bypass the toggle.
        if (STOCK_MANAGER.equals(roleKey)) {
            return readStocktake(businessId).showSystemStockToStockManager();
        }
        return hasStocktakeApprove;
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

    private SuppliersAccessSettingsResponse readSuppliers(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return SuppliersAccessSettingsResponse.defaults();
        }
        return businessRepository.findById(businessId.trim())
                .map(Business::getSettings)
                .map(businessInventorySettingsService::readFromSettingsJson)
                .map(inventory -> inventory.suppliers())
                .orElse(SuppliersAccessSettingsResponse.defaults());
    }

    private ReceiveStockSettingsResponse readReceiveStock(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return ReceiveStockSettingsResponse.defaults();
        }
        return businessRepository.findById(businessId.trim())
                .map(Business::getSettings)
                .map(businessInventorySettingsService::readFromSettingsJson)
                .map(inventory -> inventory.receiveStock())
                .orElse(ReceiveStockSettingsResponse.defaults());
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
