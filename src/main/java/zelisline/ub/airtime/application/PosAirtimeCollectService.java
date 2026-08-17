package zelisline.ub.airtime.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.airtime.api.dto.AirtimeOrderResponse;
import zelisline.ub.airtime.domain.AirtimeChannels;
import zelisline.ub.airtime.domain.AirtimeOrderStatuses;
import zelisline.ub.airtime.domain.AirtimeTenders;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.payments.application.StkPushRetryHelper;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.StkPushContextType;

/**
 * Till M-Pesa for airtime: raise an unpaid order, prompt the shopper, dispatch
 * only after the STK webhook confirms — same wallet-safe path as storefront.
 */
@Service
@RequiredArgsConstructor
public class PosAirtimeCollectService {

    private static final Logger log = LoggerFactory.getLogger(PosAirtimeCollectService.class);

    private final AirtimeSaleService saleService;
    private final GatewayStkPushService gatewayStkPushService;
    private final StkPushRetryHelper stkPushRetryHelper;

    /**
     * Not {@code @Transactional}: STK initiation waits on the provider.
     */
    public AirtimeOrderResponse prompt(
            String businessId,
            String branchId,
            String cashierUserId,
            String rawPhone,
            java.math.BigDecimal amount,
            String payerPhone,
            String customerId,
            String idempotencyKey
    ) {
        AirtimeOrderResponse order = saleService.createAwaitingPayment(
                businessId,
                branchId,
                cashierUserId,
                AirtimeChannels.POS,
                AirtimeTenders.MPESA,
                rawPhone,
                amount,
                customerId,
                idempotencyKey);

        if (!AirtimeOrderStatuses.AWAITING_PAYMENT.equals(order.status())) {
            return order;
        }

        String payerRaw = payerPhone != null && !payerPhone.isBlank() ? payerPhone : rawPhone;
        String payer = StkPhoneNormalizer.normalize(payerRaw);
        if (payer == null) {
            saleService.cancelUnpaid(order.id(), "No valid paying number was given");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A valid M-Pesa number is required to pay");
        }

        PaymentGatewayStkService.StkPushOutcome outcome = stkPushRetryHelper.initiateAfterClearingPhone(
                businessId,
                null,
                payer,
                order.amount(),
                order.reference(),
                "Airtime " + order.currency() + " " + order.amount().toPlainString()
                        + " for " + order.phoneNumber());

        if (!outcome.accepted() || outcome.checkoutRequestId() == null) {
            String message = outcome.message() != null
                    ? outcome.message()
                    : "Could not send the M-Pesa prompt";
            saleService.cancelUnpaid(order.id(), message);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }

        gatewayStkPushService.registerPush(
                businessId,
                GatewayType.valueOf(outcome.gatewayType()),
                outcome.configId(),
                outcome.checkoutRequestId(),
                order.reference(),
                StkPushContextType.AIRTIME_ORDER,
                order.id(),
                order.amount(),
                payer);

        log.info("POS airtime awaiting M-Pesa: order={} business={} amount={}",
                order.id(), businessId, order.amount());
        return saleService.get(businessId, order.id());
    }
}
