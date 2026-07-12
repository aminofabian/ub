package zelisline.ub.storefront.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.payments.application.PlatformPaymentGatewayService;
import zelisline.ub.payments.domain.GatewayStkPush;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.PlatformPaymentGateway;
import zelisline.ub.payments.domain.spi.DisplayInstructions;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.api.dto.PublicCheckoutPaymentOptions;
import zelisline.ub.storefront.api.dto.PublicOnlinePaymentMethod;
import zelisline.ub.storefront.api.dto.PublicWebOrderPaymentStatusResponse;
import zelisline.ub.storefront.api.dto.PublicWebStkPushResponse;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class PublicStorefrontPaymentService {

    private static final Set<GatewayType> ONLINE_STK_TYPES = Set.of(
            GatewayType.KOPOKOPO,
            GatewayType.DARAJA,
            GatewayType.PAYSTACK,
            GatewayType.PESAPAL
    );

    private final BusinessRepository businessRepository;
    private final PaymentGatewayConfigRepository configRepository;
    private final PlatformPaymentGatewayService platformPaymentGatewayService;
    private final WebOrderRepository webOrderRepository;
    private final PaymentGatewayStkService paymentGatewayStkService;
    private final GatewayStkPushService gatewayStkPushService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public PublicCheckoutPaymentOptions checkoutOptions(String slug) {
        Business business = requireBusiness(slug);
        String businessId = business.getId();

        List<PlatformPaymentGateway> platformEnabled = platformPaymentGatewayService.listEnabled();
        Set<GatewayType> enabledTypes = platformEnabled.stream()
                .map(PlatformPaymentGateway::getGatewayType)
                .collect(Collectors.toSet());

        List<DisplayInstructions> manual = new ArrayList<>();
        List<PublicOnlinePaymentMethod> online = new ArrayList<>();

        for (PaymentGatewayConfig cfg : configRepository.findByBusinessIdAndStatus(businessId, GatewayStatus.ACTIVE)) {
            GatewayType type = cfg.getGatewayType();
            if (type == GatewayType.MANUAL) {
                DisplayInstructions di = parseManual(cfg);
                if (di != null) {
                    manual.add(di);
                }
            } else if (ONLINE_STK_TYPES.contains(type) && enabledTypes.contains(type)) {
                String displayName = platformEnabled.stream()
                        .filter(pg -> pg.getGatewayType() == type)
                        .map(PlatformPaymentGateway::getDisplayName)
                        .findFirst()
                        .orElse(type.name());
                String label = cfg.getLabel() != null && !cfg.getLabel().isBlank()
                        ? cfg.getLabel()
                        : displayName;
                online.add(new PublicOnlinePaymentMethod(
                        cfg.getId(),
                        type.name(),
                        label,
                        displayName
                ));
            }
        }

        online.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
        return new PublicCheckoutPaymentOptions(manual, online);
    }

    @Transactional
    public PublicWebStkPushResponse initiateOrderStkPush(
            String slug,
            String orderId,
            String preferredConfigId,
            String phoneOverride
    ) {
        Business business = requireBusiness(slug);
        WebOrder order = webOrderRepository.findById(orderId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!business.getId().equals(order.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }

        String phone = phoneOverride != null && !phoneOverride.isBlank()
                ? phoneOverride.trim()
                : order.getCustomerPhone();
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer phone is required for M-Pesa payment");
        }

        gatewayStkPushService.reconcilePendingForPhone(business.getId(), phone);

        PaymentGatewayStkService.StkPushOutcome outcome = paymentGatewayStkService.initiate(
                business.getId(),
                preferredConfigId,
                phone,
                order.getGrandTotal(),
                order.getId(),
                "Web order " + order.getId()
        );

        if (!outcome.accepted() && GatewayStkPushService.isKopokopoPendingPhoneError(outcome.message())) {
            gatewayStkPushService.cancelPendingForPhone(
                    business.getId(),
                    phone,
                    "Cleared after gateway rejected duplicate pending prompt");
            gatewayStkPushService.reconcilePendingForPhone(business.getId(), phone);
            outcome = paymentGatewayStkService.initiate(
                    business.getId(),
                    preferredConfigId,
                    phone,
                    order.getGrandTotal(),
                    order.getId() + "-r",
                    "Web order " + order.getId());
        }

        if (outcome.accepted()) {
            GatewayType gatewayType = GatewayType.valueOf(outcome.gatewayType());
            gatewayStkPushService.registerPush(
                    business.getId(),
                    gatewayType,
                    outcome.configId(),
                    outcome.checkoutRequestId(),
                    order.getId(),
                    StkPushContextType.WEB_ORDER,
                    order.getId(),
                    order.getGrandTotal(),
                    phone);
            order.setPaymentCheckoutId(outcome.checkoutRequestId());
            webOrderRepository.save(order);
            return new PublicWebStkPushResponse(
                    true,
                    outcome.gatewayType(),
                    outcome.checkoutRequestId(),
                    outcome.message()
            );
        }
        return new PublicWebStkPushResponse(
                false,
                outcome.gatewayType(),
                null,
                outcome.message() != null ? outcome.message() : "Could not send M-Pesa prompt"
        );
    }

    @Transactional
    public PublicWebOrderPaymentStatusResponse orderPaymentStatus(String slug, String orderId) {
        Business business = requireBusiness(slug);
        WebOrder order = webOrderRepository.findById(orderId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!business.getId().equals(order.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }

        boolean paid = WebOrderStatuses.PAID.equals(order.getStatus());
        boolean failed = WebOrderStatuses.PAYMENT_FAILED.equals(order.getStatus());
        String checkoutId = order.getPaymentCheckoutId();
        String failureReason = null;

        var pushOpt = gatewayStkPushService.findLatestForWebOrder(order.getId());
        if (pushOpt.isPresent()) {
            GatewayStkPush push = pushOpt.get();
            if (zelisline.ub.payments.domain.GatewayStkPushStatuses.PENDING.equals(push.getStatus())) {
                push = gatewayStkPushService.pollAndUpdate(push).orElse(push);
            }
            checkoutId = push.getGatewayCheckoutId();
            failed = failed || zelisline.ub.payments.domain.GatewayStkPushStatuses.FAILED.equals(push.getStatus());
            paid = paid || zelisline.ub.payments.domain.GatewayStkPushStatuses.SUCCESS.equals(push.getStatus());
            failureReason = push.getFailureReason();
        }

        return new PublicWebOrderPaymentStatusResponse(
                order.getStatus(),
                paid,
                failed,
                checkoutId,
                failureReason);
    }

    private Business requireBusiness(String slug) {
        return businessRepository.findBySlugAndDeletedAtIsNull(slug.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found"));
    }

    private DisplayInstructions parseManual(PaymentGatewayConfig cfg) {
        String json = cfg.getDisplayInstructionsJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(json);
            return new DisplayInstructions(
                    cfg.getId(),
                    node.has("type") ? node.get("type").asText() : null,
                    node.has("label") ? node.get("label").asText() : cfg.getLabel(),
                    node.has("instructions") ? node.get("instructions").asText() : null,
                    node.has("tillNumber") ? node.get("tillNumber").asText() : null,
                    node.has("businessNumber") ? node.get("businessNumber").asText() : null,
                    node.has("accountNumber") ? node.get("accountNumber").asText() : null,
                    node.has("bankName") ? node.get("bankName").asText() : null,
                    node.has("branchName") ? node.get("branchName").asText() : null,
                    node.has("accountName") ? node.get("accountName").asText() : null,
                    node.has("swiftCode") ? node.get("swiftCode").asText() : null
            );
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
