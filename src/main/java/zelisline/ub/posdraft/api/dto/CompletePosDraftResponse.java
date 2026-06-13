package zelisline.ub.posdraft.api.dto;

import zelisline.ub.sales.api.dto.SaleResponse;

public record CompletePosDraftResponse(
        String draftId,
        long ticketNumber,
        String status,
        String saleId,
        SaleResponse sale,
        boolean createdNew
) {
}
