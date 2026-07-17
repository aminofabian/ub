package zelisline.ub.tenancy.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.CreditTabsPatchRequest;
import zelisline.ub.tenancy.api.dto.CreditTabsSettingsResponse;
import zelisline.ub.tenancy.api.dto.InventoryPatchRequest;
import zelisline.ub.tenancy.api.dto.InventorySettingsResponse;
import zelisline.ub.tenancy.api.dto.ReceiveStockPatchRequest;
import zelisline.ub.tenancy.api.dto.ReceiveStockSettingsResponse;
import zelisline.ub.tenancy.api.dto.StockLevelsPatchRequest;
import zelisline.ub.tenancy.api.dto.StockLevelsSettingsResponse;
import zelisline.ub.tenancy.api.dto.StocktakePatchRequest;
import zelisline.ub.tenancy.api.dto.StocktakeSettingsResponse;
import zelisline.ub.tenancy.api.dto.SuppliersAccessPatchRequest;
import zelisline.ub.tenancy.api.dto.SuppliersAccessSettingsResponse;

@Service
@RequiredArgsConstructor
public class BusinessInventorySettingsService {

    private static final String KEY_INVENTORY = "inventory";
    private static final String KEY_STOCKTAKE = "stocktake";
    private static final String KEY_STOCK_LEVELS = "stockLevels";
    private static final String KEY_SUPPLIERS = "suppliers";
    private static final String KEY_RECEIVE_STOCK = "receiveStock";
    private static final String KEY_CREDIT_TABS = "creditTabs";
    private static final String KEY_SHOW_SYSTEM_STOCK =
            "showSystemStockToStockManager";
    private static final String KEY_DAILY_AUDIT_SAMPLE_SIZE = "dailyAuditSampleSize";
    private static final String KEY_ALLOW_CASHIER_TAB_CLEARANCE =
            "allowCashierTabClearance";
    private static final String KEY_ALLOW_EDIT_STOCK_MANAGER =
            "allowStockEditForStockManager";
    private static final String KEY_ALLOW_EDIT_GROCERY_CLERK =
            "allowStockEditForGroceryClerk";
    private static final String KEY_ALLOW_NEGATIVE_STOCK = "allowNegativeStock";
    private static final String KEY_ALLOW_SUPPLIER_WRITE_STOCK_MANAGER =
            "allowSupplierWriteForStockManager";
    private static final String KEY_ALLOW_SUPPLIER_WRITE_CASHIER =
            "allowSupplierWriteForCashier";
    private static final String KEY_ALLOW_LINK_PRODUCTS_STOCK_MANAGER =
            "allowLinkProductsForStockManager";
    private static final String KEY_ALLOW_LINK_PRODUCTS_CASHIER =
            "allowLinkProductsForCashier";
    private static final String KEY_ALLOW_RECEIVE_CASHIER = "allowReceiveForCashier";
    private static final String KEY_ALLOW_RECEIVE_STOCK_MANAGER =
            "allowReceiveForStockManager";

    private final ObjectMapper objectMapper;

    public InventorySettingsResponse readFromSettingsJson(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return InventorySettingsResponse.defaults();
        }
        try {
            JsonNode root = parseSettingsDocument(settingsJson);
            if (!root.isObject()) {
                return InventorySettingsResponse.defaults();
            }
            JsonNode inventory = root.path(KEY_INVENTORY);
            return new InventorySettingsResponse(
                    readStocktake(inventory),
                    readStockLevels(inventory),
                    readSuppliers(inventory),
                    readReceiveStock(inventory),
                    readCreditTabs(inventory)
            );
        } catch (Exception e) {
            return InventorySettingsResponse.defaults();
        }
    }

    public String merge(String currentSettings, InventoryPatchRequest patch) {
        if (patch == null) {
            return currentSettings;
        }
        if (
            patch.stocktake() == null
                    && patch.stockLevels() == null
                    && patch.suppliers() == null
                    && patch.receiveStock() == null
                    && patch.creditTabs() == null
        ) {
            return currentSettings;
        }
        ObjectNode root = parseRoot(currentSettings);
        ObjectNode inventory = copyNamespace(root, KEY_INVENTORY);
        if (patch.stocktake() != null) {
            ObjectNode stocktake = copyNamespace(inventory, KEY_STOCKTAKE);
            applyStocktakePatch(stocktake, patch.stocktake());
            inventory.set(KEY_STOCKTAKE, stocktake);
        }
        if (patch.stockLevels() != null) {
            ObjectNode stockLevels = copyNamespace(inventory, KEY_STOCK_LEVELS);
            applyStockLevelsPatch(stockLevels, patch.stockLevels());
            inventory.set(KEY_STOCK_LEVELS, stockLevels);
        }
        if (patch.suppliers() != null) {
            ObjectNode suppliers = copyNamespace(inventory, KEY_SUPPLIERS);
            applySuppliersPatch(suppliers, patch.suppliers());
            inventory.set(KEY_SUPPLIERS, suppliers);
        }
        if (patch.receiveStock() != null) {
            ObjectNode receiveStock = copyNamespace(inventory, KEY_RECEIVE_STOCK);
            applyReceiveStockPatch(receiveStock, patch.receiveStock());
            inventory.set(KEY_RECEIVE_STOCK, receiveStock);
        }
        if (patch.creditTabs() != null) {
            ObjectNode creditTabs = copyNamespace(inventory, KEY_CREDIT_TABS);
            applyCreditTabsPatch(creditTabs, patch.creditTabs());
            inventory.set(KEY_CREDIT_TABS, creditTabs);
        }
        root.set(KEY_INVENTORY, inventory);
        return writeRoot(root);
    }

    private static StocktakeSettingsResponse readStocktake(JsonNode inventoryNode) {
        if (inventoryNode.isMissingNode() || !inventoryNode.isObject()) {
            return StocktakeSettingsResponse.defaults();
        }
        JsonNode stocktake = inventoryNode.path(KEY_STOCKTAKE);
        if (stocktake.isMissingNode() || !stocktake.isObject()) {
            return StocktakeSettingsResponse.defaults();
        }
        return new StocktakeSettingsResponse(
                stocktake.path(KEY_SHOW_SYSTEM_STOCK).asBoolean(false),
                StocktakeSettingsResponse.clampSampleSize(
                        stocktake.path(KEY_DAILY_AUDIT_SAMPLE_SIZE)
                                .asInt(StocktakeSettingsResponse.DEFAULT_DAILY_AUDIT_SAMPLE_SIZE)
                )
        );
    }

    private static StockLevelsSettingsResponse readStockLevels(JsonNode inventoryNode) {
        if (inventoryNode.isMissingNode() || !inventoryNode.isObject()) {
            return StockLevelsSettingsResponse.defaults();
        }
        JsonNode stockLevels = inventoryNode.path(KEY_STOCK_LEVELS);
        if (stockLevels.isMissingNode() || !stockLevels.isObject()) {
            return StockLevelsSettingsResponse.defaults();
        }
        return new StockLevelsSettingsResponse(
                stockLevels.path(KEY_ALLOW_EDIT_STOCK_MANAGER).asBoolean(false),
                stockLevels.path(KEY_ALLOW_EDIT_GROCERY_CLERK).asBoolean(false),
                stockLevels.path(KEY_ALLOW_NEGATIVE_STOCK).asBoolean(false)
        );
    }

    private static SuppliersAccessSettingsResponse readSuppliers(JsonNode inventoryNode) {
        if (inventoryNode.isMissingNode() || !inventoryNode.isObject()) {
            return SuppliersAccessSettingsResponse.defaults();
        }
        JsonNode suppliers = inventoryNode.path(KEY_SUPPLIERS);
        if (suppliers.isMissingNode() || !suppliers.isObject()) {
            return SuppliersAccessSettingsResponse.defaults();
        }
        return new SuppliersAccessSettingsResponse(
                suppliers.path(KEY_ALLOW_SUPPLIER_WRITE_STOCK_MANAGER).asBoolean(false),
                suppliers.path(KEY_ALLOW_SUPPLIER_WRITE_CASHIER).asBoolean(false),
                suppliers.path(KEY_ALLOW_LINK_PRODUCTS_STOCK_MANAGER).asBoolean(false),
                suppliers.path(KEY_ALLOW_LINK_PRODUCTS_CASHIER).asBoolean(false)
        );
    }

    private static ReceiveStockSettingsResponse readReceiveStock(JsonNode inventoryNode) {
        if (inventoryNode.isMissingNode() || !inventoryNode.isObject()) {
            return ReceiveStockSettingsResponse.defaults();
        }
        JsonNode receiveStock = inventoryNode.path(KEY_RECEIVE_STOCK);
        if (receiveStock.isMissingNode() || !receiveStock.isObject()) {
            return ReceiveStockSettingsResponse.defaults();
        }
        // Absent keys default to true (preserve prior always-on receive behaviour).
        return new ReceiveStockSettingsResponse(
                receiveStock.path(KEY_ALLOW_RECEIVE_CASHIER).asBoolean(true),
                receiveStock.path(KEY_ALLOW_RECEIVE_STOCK_MANAGER).asBoolean(true)
        );
    }

    private static CreditTabsSettingsResponse readCreditTabs(JsonNode inventoryNode) {
        if (inventoryNode.isMissingNode() || !inventoryNode.isObject()) {
            return CreditTabsSettingsResponse.defaults();
        }
        JsonNode creditTabs = inventoryNode.path(KEY_CREDIT_TABS);
        if (creditTabs.isMissingNode() || !creditTabs.isObject()) {
            return CreditTabsSettingsResponse.defaults();
        }
        return new CreditTabsSettingsResponse(
                creditTabs.path(KEY_ALLOW_CASHIER_TAB_CLEARANCE).asBoolean(false)
        );
    }

    private static void applyStockLevelsPatch(
            ObjectNode stockLevels,
            StockLevelsPatchRequest patch
    ) {
        if (patch.allowStockEditForStockManager() != null) {
            stockLevels.put(
                    KEY_ALLOW_EDIT_STOCK_MANAGER,
                    patch.allowStockEditForStockManager()
            );
        }
        if (patch.allowStockEditForGroceryClerk() != null) {
            stockLevels.put(
                    KEY_ALLOW_EDIT_GROCERY_CLERK,
                    patch.allowStockEditForGroceryClerk()
            );
        }
        if (patch.allowNegativeStock() != null) {
            stockLevels.put(KEY_ALLOW_NEGATIVE_STOCK, patch.allowNegativeStock());
        }
    }

    private static void applySuppliersPatch(
            ObjectNode suppliers,
            SuppliersAccessPatchRequest patch
    ) {
        if (patch.allowSupplierWriteForStockManager() != null) {
            suppliers.put(
                    KEY_ALLOW_SUPPLIER_WRITE_STOCK_MANAGER,
                    patch.allowSupplierWriteForStockManager()
            );
        }
        if (patch.allowSupplierWriteForCashier() != null) {
            suppliers.put(
                    KEY_ALLOW_SUPPLIER_WRITE_CASHIER,
                    patch.allowSupplierWriteForCashier()
            );
        }
        if (patch.allowLinkProductsForStockManager() != null) {
            suppliers.put(
                    KEY_ALLOW_LINK_PRODUCTS_STOCK_MANAGER,
                    patch.allowLinkProductsForStockManager()
            );
        }
        if (patch.allowLinkProductsForCashier() != null) {
            suppliers.put(
                    KEY_ALLOW_LINK_PRODUCTS_CASHIER,
                    patch.allowLinkProductsForCashier()
            );
        }
    }

    private static void applyReceiveStockPatch(
            ObjectNode receiveStock,
            ReceiveStockPatchRequest patch
    ) {
        if (patch.allowReceiveForCashier() != null) {
            receiveStock.put(KEY_ALLOW_RECEIVE_CASHIER, patch.allowReceiveForCashier());
        }
        if (patch.allowReceiveForStockManager() != null) {
            receiveStock.put(
                    KEY_ALLOW_RECEIVE_STOCK_MANAGER,
                    patch.allowReceiveForStockManager()
            );
        }
    }

    private static void applyCreditTabsPatch(
            ObjectNode creditTabs,
            CreditTabsPatchRequest patch
    ) {
        if (patch.allowCashierTabClearance() != null) {
            creditTabs.put(KEY_ALLOW_CASHIER_TAB_CLEARANCE, patch.allowCashierTabClearance());
        }
    }

    private static void applyStocktakePatch(
            ObjectNode stocktake,
            StocktakePatchRequest patch
    ) {
        if (patch.showSystemStockToStockManager() != null) {
            stocktake.put(
                    KEY_SHOW_SYSTEM_STOCK,
                    patch.showSystemStockToStockManager()
            );
        }
        if (patch.dailyAuditSampleSize() != null) {
            stocktake.put(
                    KEY_DAILY_AUDIT_SAMPLE_SIZE,
                    StocktakeSettingsResponse.clampSampleSize(patch.dailyAuditSampleSize())
            );
        }
    }

    private ObjectNode parseRoot(String currentSettings) {
        if (currentSettings == null || currentSettings.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode root = parseSettingsDocument(currentSettings);
            return root.isObject() ? (ObjectNode) root : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode parseSettingsDocument(String raw) throws JsonProcessingException {
        JsonNode n = objectMapper.readTree(raw);
        if (n.isTextual()) {
            return objectMapper.readTree(n.asText());
        }
        return n;
    }

    private ObjectNode copyNamespace(ObjectNode root, String key) {
        if (root.has(key) && root.get(key).isObject()) {
            return (ObjectNode) root.get(key).deepCopy();
        }
        return objectMapper.createObjectNode();
    }

    private String writeRoot(ObjectNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not save inventory settings"
            );
        }
    }
}
