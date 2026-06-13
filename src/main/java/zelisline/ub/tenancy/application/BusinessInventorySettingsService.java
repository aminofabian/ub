package zelisline.ub.tenancy.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.InventoryPatchRequest;
import zelisline.ub.tenancy.api.dto.InventorySettingsResponse;
import zelisline.ub.tenancy.api.dto.StockLevelsPatchRequest;
import zelisline.ub.tenancy.api.dto.StockLevelsSettingsResponse;
import zelisline.ub.tenancy.api.dto.StocktakePatchRequest;
import zelisline.ub.tenancy.api.dto.StocktakeSettingsResponse;

@Service
@RequiredArgsConstructor
public class BusinessInventorySettingsService {

    private static final String KEY_INVENTORY = "inventory";
    private static final String KEY_STOCKTAKE = "stocktake";
    private static final String KEY_STOCK_LEVELS = "stockLevels";
    private static final String KEY_SHOW_SYSTEM_STOCK =
            "showSystemStockToStockManager";
    private static final String KEY_ALLOW_EDIT_STOCK_MANAGER =
            "allowStockEditForStockManager";
    private static final String KEY_ALLOW_EDIT_GROCERY_CLERK =
            "allowStockEditForGroceryClerk";

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
            return new InventorySettingsResponse(
                    readStocktake(root.path(KEY_INVENTORY)),
                    readStockLevels(root.path(KEY_INVENTORY))
            );
        } catch (Exception e) {
            return InventorySettingsResponse.defaults();
        }
    }

    public String merge(String currentSettings, InventoryPatchRequest patch) {
        if (patch == null) {
            return currentSettings;
        }
        if (patch.stocktake() == null && patch.stockLevels() == null) {
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
                stocktake.path(KEY_SHOW_SYSTEM_STOCK).asBoolean(false)
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
                stockLevels.path(KEY_ALLOW_EDIT_GROCERY_CLERK).asBoolean(false)
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
