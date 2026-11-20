package zelisline.ub.posdraft.api.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import zelisline.ub.sales.api.dto.PostSaleLineRequest;
import zelisline.ub.sales.api.dto.PostSalePaymentRequest;

public record CompletePosDraftRequest(
        @NotEmpty @Valid List<PostSalePaymentRequest> payments,
        String customerId,
        Instant clientSoldAt,
        Long expectedVersion,
        /** Wallet airtime parked on the till cart — not stored on the draft. */
        @Valid List<PostSaleLineRequest> additionalLines,
        /** Dispensing details when the cart holds prescription-only or controlled medicines (ADR 0033). */
        @Valid zelisline.ub.sales.api.dto.PostSaleDispensingRequest dispensing
) {

    public CompletePosDraftRequest(
            List<PostSalePaymentRequest> payments,
            String customerId,
            Instant clientSoldAt,
            Long expectedVersion,
            List<PostSaleLineRequest> additionalLines
    ) {
        this(payments, customerId, clientSoldAt, expectedVersion, additionalLines, null);
    }
}
