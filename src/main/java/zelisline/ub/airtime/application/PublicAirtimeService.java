package zelisline.ub.airtime.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.airtime.api.dto.AirtimeOrderResponse;
import zelisline.ub.airtime.api.dto.PublicAirtimeConfigResponse;
import zelisline.ub.airtime.api.dto.PublicAirtimeOrderRequest;
import zelisline.ub.airtime.api.dto.PublicAirtimeOrderResponse;
import zelisline.ub.airtime.api.dto.PublicAirtimeRecentsResponse;
import zelisline.ub.airtime.domain.AirtimeOrder;
import zelisline.ub.airtime.domain.AirtimeOrderStatuses;
import zelisline.ub.airtime.repository.AirtimeOrderRepository;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.payments.application.StkPushRetryHelper;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Storefront airtime for shoppers.
 *
 * <p>Nothing is reserved or sent until the shopper's M-Pesa payment confirms —
 * the STK confirmation triggers {@link AirtimeSaleService#markPaidAndDispatch},
 * so an abandoned prompt costs the merchant nothing.
 */
@Service
@RequiredArgsConstructor
public class PublicAirtimeService {

    private static final Logger log = LoggerFactory.getLogger(PublicAirtimeService.class);

    private final BusinessRepository businessRepository;
    private final AirtimeOrderRepository orderRepository;
    private final AirtimeSaleService saleService;
    private final BusinessAirtimeSettingsService settingsService;
    private final GatewayStkPushService gatewayStkPushService;
    private final StkPushRetryHelper stkPushRetryHelper;

    @Transactional(readOnly = true)
    public PublicAirtimeConfigResponse config(String slug) {
        return configForBusiness(requireBusiness(slug).getId());
    }

    @Transactional(readOnly = true)
    public PublicAirtimeConfigResponse configForBusiness(String businessId) {
        return configForCustomer(businessId, null);
    }

    @Transactional(readOnly = true)
    public PublicAirtimeConfigResponse configForCustomer(String businessId, String customerId) {
        var availability = settingsService.availability(businessId, true);
        return new PublicAirtimeConfigResponse(
                availability.available(),
                availability.minAmount(),
                availability.maxAmount(),
                availability.currency(),
                availability.quickAmounts(),
                availability.available() ? null : "Airtime is not available from this store right now",
                recentsForCustomer(businessId, customerId));
    }

    /**
     * Not {@code @Transactional}: the STK initiation can wait on the provider for
     * several seconds. Persistence happens in nested transactional services.
     */
    public PublicAirtimeOrderResponse createOrder(String slug, PublicAirtimeOrderRequest body) {
        return createOrderForBusiness(requireBusiness(slug).getId(), body);
    }

    public PublicAirtimeOrderResponse createOrderForBusiness(String businessId, PublicAirtimeOrderRequest body) {
        return createOrderForBusiness(businessId, body, null);
    }

    public PublicAirtimeOrderResponse createOrderForBusiness(
            String businessId,
            PublicAirtimeOrderRequest body,
            String customerId
    ) {
        AirtimeOrderResponse order = saleService.createAwaitingPayment(
                businessId,
                null,
                null,
                zelisline.ub.airtime.domain.AirtimeChannels.STOREFRONT,
                zelisline.ub.airtime.domain.AirtimeTenders.MPESA,
                body.phoneNumber(),
                body.amount(),
                customerId,
                body.payerPhone(),
                UUID.randomUUID().toString());

        String payerRaw = body.payerPhone() != null && !body.payerPhone().isBlank()
                ? body.payerPhone()
                : body.phoneNumber();
        String payer = StkPhoneNormalizer.normalize(payerRaw);
        if (payer == null) {
            saleService.cancelUnpaid(order.id(), "No valid paying number was given");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A valid M-Pesa number is required to pay");
        }

        PaymentGatewayStkService.StkPushOutcome outcome = stkPushRetryHelper.initiateAfterClearingPhone(
                businessId,
                body.configId(),
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
            return new PublicAirtimeOrderResponse(
                    order.id(), order.phoneNumber(), order.network(), order.amount(), order.currency(),
                    AirtimeOrderStatuses.FAILED, false, true, false, null, null, message);
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

        log.info("Storefront airtime awaiting payment: order={} business={} amount={}",
                order.id(), businessId, order.amount());

        return new PublicAirtimeOrderResponse(
                order.id(), order.phoneNumber(), order.network(), order.amount(), order.currency(),
                AirtimeOrderStatuses.AWAITING_PAYMENT, false, false, true,
                outcome.checkoutRequestId(), null,
                "Enter your M-Pesa PIN on your phone to complete the purchase");
    }

    @Transactional(readOnly = true)
    public PublicAirtimeOrderResponse status(String slug, String orderId) {
        return statusForBusiness(requireBusiness(slug).getId(), orderId);
    }

    @Transactional(readOnly = true)
    public PublicAirtimeOrderResponse statusForBusiness(String businessId, String orderId) {
        AirtimeOrder order = orderRepository.findByIdAndBusinessId(orderId.trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Airtime order not found"));

        boolean delivered = AirtimeOrderStatuses.SUCCESS.equals(order.getStatus());
        boolean failed = AirtimeOrderStatuses.FAILED.equals(order.getStatus());
        boolean awaiting = AirtimeOrderStatuses.AWAITING_PAYMENT.equals(order.getStatus());
        return new PublicAirtimeOrderResponse(
                order.getId(),
                order.getPhoneNumber(),
                order.getNetwork(),
                order.getAmount(),
                order.getCurrency(),
                order.getStatus(),
                delivered,
                failed,
                awaiting,
                null,
                order.getReceipt(),
                shopperMessage(order.getStatus()));
    }

    private PublicAirtimeRecentsResponse recentsForCustomer(String businessId, String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return PublicAirtimeRecentsResponse.empty();
        }
        List<AirtimeOrder> orders = orderRepository
                .findByBusinessIdAndCustomerIdAndStatusOrderByCompletedAtDesc(
                        businessId,
                        customerId,
                        AirtimeOrderStatuses.SUCCESS,
                        PageRequest.of(0, 24));
        Set<String> recipients = new LinkedHashSet<>();
        Set<String> payers = new LinkedHashSet<>();
        String lastRecipient = null;
        String lastPayer = null;
        BigDecimal lastAmount = null;
        for (AirtimeOrder order : orders) {
            String recv = KenyanPhoneForms.toLocal07(order.getPhoneNumber());
            String payRaw = order.getPayerPhone() != null && !order.getPayerPhone().isBlank()
                    ? order.getPayerPhone()
                    : order.getPhoneNumber();
            String pay = KenyanPhoneForms.toLocal07(payRaw);
            if (lastRecipient == null && recv != null) {
                lastRecipient = recv;
                lastPayer = pay;
                lastAmount = order.getAmount();
            }
            if (recv != null) {
                recipients.add(recv);
            }
            if (pay != null) {
                payers.add(pay);
            }
        }
        return new PublicAirtimeRecentsResponse(
                new ArrayList<>(recipients).stream().limit(6).toList(),
                new ArrayList<>(payers).stream().limit(6).toList(),
                lastRecipient,
                lastPayer,
                lastAmount);
    }

    private static String shopperMessage(String status) {
        return switch (status) {
            case AirtimeOrderStatuses.AWAITING_PAYMENT ->
                    "Waiting for your M-Pesa payment";
            case AirtimeOrderStatuses.REQUESTED, AirtimeOrderStatuses.SUBMITTED,
                 AirtimeOrderStatuses.PENDING ->
                    "Payment received — sending your airtime";
            case AirtimeOrderStatuses.SUCCESS ->
                    "Airtime sent";
            // Any failure the shopper sees is either an unpaid prompt or a telco
            // reject; either way their money was not taken for airtime.
            case AirtimeOrderStatuses.FAILED ->
                    "This airtime purchase did not go through";
            default -> null;
        };
    }

    private Business requireBusiness(String slug) {
        return businessRepository.findBySlugAndDeletedAtIsNull(slug.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found"));
    }
}
