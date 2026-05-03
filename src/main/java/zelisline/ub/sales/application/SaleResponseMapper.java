package zelisline.ub.sales.application;

import java.util.ArrayList;
import java.util.List;

import zelisline.ub.sales.api.dto.SaleItemResponse;
import zelisline.ub.sales.api.dto.SalePaymentResponse;
import zelisline.ub.sales.api.dto.SaleResponse;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SalePayment;

public final class SaleResponseMapper {

    private SaleResponseMapper() {
    }

    public static SaleResponse map(Sale sale, List<SaleItem> items, List<SalePayment> pays) {
        List<SaleItemResponse> ir = new ArrayList<>();
        for (SaleItem si : items) {
            ir.add(new SaleItemResponse(
                    si.getId(),
                    si.getLineIndex(),
                    si.getItemId(),
                    si.getBatchId(),
                    si.getQuantity(),
                    si.getUnitPrice(),
                    si.getLineTotal(),
                    si.getUnitCost(),
                    si.getCostTotal(),
                    si.getProfit()
            ));
        }
        List<SalePaymentResponse> paymentDtos = new ArrayList<>();
        for (SalePayment pay : pays) {
            paymentDtos.add(new SalePaymentResponse(pay.getMethod(), pay.getAmount(), pay.getReference()));
        }
        return new SaleResponse(
                sale.getId(),
                sale.getBranchId(),
                sale.getCustomerId(),
                sale.getShiftId(),
                sale.getStatus(),
                sale.getGrandTotal(),
                sale.getRefundedTotal(),
                sale.getJournalEntryId(),
                paymentDtos,
                ir,
                sale.getVoidedAt(),
                sale.getVoidedBy(),
                sale.getVoidJournalEntryId(),
                sale.getVoidNotes()
        );
    }
}
