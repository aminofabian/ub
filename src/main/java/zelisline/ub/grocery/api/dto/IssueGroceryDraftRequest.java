package zelisline.ub.grocery.api.dto;

public record IssueGroceryDraftRequest(
        String notes,
        Long expectedVersion
) {
}
