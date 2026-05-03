package zelisline.ub.inventory.application;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.CostMethod;

@Component
@RequiredArgsConstructor
public class BusinessInventorySettingsReader {

    private final ObjectMapper objectMapper;

    public CostMethod costMethodFromSettingsJson(String settings) {
        if (settings == null || settings.isBlank()) {
            return CostMethod.FIFO;
        }
        String compact = settings.replaceAll("\\s+", "");
        if (compact.contains("costMethod") && compact.contains("LIFO")) {
            return CostMethod.LIFO;
        }
        if (compact.matches("(?i).*\"costMethod\"\\s*:\\s*\"LIFO\".*")) {
            return CostMethod.LIFO;
        }
        try {
            JsonNode root = objectMapper.readTree(settings);
            String cm = root.path("inventory").path("costMethod").asText("FIFO");
            return CostMethod.fromApiValue(cm);
        } catch (Exception e) {
            return CostMethod.FIFO;
        }
    }
}
