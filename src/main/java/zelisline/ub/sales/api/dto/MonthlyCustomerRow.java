package zelisline.ub.sales.api.dto;

public record MonthlyCustomerRow(
        int year,
        int month,
        long customerCount
) {}
