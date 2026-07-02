package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VariableWeightBarcodeLookupResponse(
        String itemId,
        String itemName,
        String pluCode,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String embeddedField
) {
}
