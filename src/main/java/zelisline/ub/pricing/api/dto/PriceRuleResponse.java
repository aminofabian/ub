package zelisline.ub.pricing.api.dto;

public record PriceRuleResponse(
        String id,
        String name,
        String ruleType,
        String paramsJson,
        boolean active
) {
}
