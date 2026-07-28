package zelisline.ub.messaging.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.domain.WalletCreditNotificationDispatch;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.repository.WalletCreditNotificationDispatchRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.notifications.application.NotificationService;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class WalletCreditNotificationService {

    private static final Logger log = LoggerFactory.getLogger(WalletCreditNotificationService.class);

    static final String NOTIFICATION_TYPE = "wallet_credit.notification";

    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final WalletCreditNotificationDispatchRepository dispatchRepository;
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final ObjectMapper objectMapper;

    @Transactional
    public void dispatch(WalletCreditNotificationEvent event) {
        TenantMessagingConfig messaging = messagingSettingsService.resolveForDispatch(event.businessId());
        if (!messaging.enabled()) {
            return;
        }
        if (dispatchRepository.existsBySaleId(event.saleId())) {
            return;
        }

        Customer customer = customerRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(event.customerId(), event.businessId())
                .orElse(null);
        if (customer == null) {
            saveDispatch(event, "none", "skipped", "customer_not_found", null);
            return;
        }

        Optional<CreditAccount> creditAccount = creditAccountRepository.findByCustomerIdAndBusinessId(
                event.customerId(), event.businessId());
        if (creditAccount.map(CreditAccount::isRemindersOptOut).orElse(false)) {
            saveDispatch(event, "none", "skipped", "reminders_opt_out", null);
            return;
        }

        String phoneDigits = resolvePrimaryPhoneDigits(event.customerId());
        if (phoneDigits == null) {
            saveDispatch(event, "none", "skipped", "no_phone", null);
            return;
        }

        Business business = businessRepository.findById(event.businessId()).orElse(null);
        String currency = business != null && business.getCurrency() != null
                ? business.getCurrency().trim()
                : "KES";
        String shopName = business != null && business.getName() != null
                ? business.getName().trim()
                : "our shop";
        String accountUrl = CustomerTabPaymentUrl.build(messaging.paymentAccountUrl(), phoneDigits);
        BigDecimal walletBalance = event.walletBalance() != null ? event.walletBalance() : BigDecimal.ZERO;
        BigDecimal balanceOwed = event.balanceOwed() != null ? event.balanceOwed() : BigDecimal.ZERO;
        String message = buildMessage(
                customer.getName(),
                shopName,
                event.items(),
                event.itemCount(),
                event.walletCreditedAmount(),
                event.tabAppliedAmount(),
                walletBalance,
                balanceOwed,
                currency,
                accountUrl);

        pushInAppNotification(event, customer, message, accountUrl, currency, walletBalance);

        CustomerMessageDispatcher.DeliveryResult delivery =
                customerMessageDispatcher.deliver(messaging, phoneDigits, message);
        saveDispatch(event, delivery.channel(), delivery.outcome(), delivery.detail(), message);
        log.info("wallet_credit_notification sale={} customer={} channel={} outcome={} detail={}",
                event.saleId(), event.customerId(), delivery.channel(), delivery.outcome(), delivery.detail());
    }

    private void pushInAppNotification(
            WalletCreditNotificationEvent event,
            Customer customer,
            String message,
            String accountUrl,
            String currency,
            BigDecimal walletBalance
    ) {
        String userId = resolveShopperUserId(event.businessId(), customer);
        if (userId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", event.tabAppliedAmount() != null && event.tabAppliedAmount().signum() > 0
                && (event.walletCreditedAmount() == null || event.walletCreditedAmount().signum() <= 0)
                ? "Tab payment"
                : "Wallet credit");
        payload.put("body", message);
        payload.put("accountUrl", accountUrl);
        payload.put("saleId", event.saleId());
        payload.put("customerId", event.customerId());
        payload.put("itemCount", event.itemCount());
        BigDecimal walletAmt = event.walletCreditedAmount() != null ? event.walletCreditedAmount() : BigDecimal.ZERO;
        BigDecimal tabAmt = event.tabAppliedAmount() != null ? event.tabAppliedAmount() : BigDecimal.ZERO;
        payload.put("amount", walletAmt.setScale(2, RoundingMode.HALF_UP).toPlainString());
        payload.put("tabApplied", tabAmt.setScale(2, RoundingMode.HALF_UP).toPlainString());
        payload.put("walletBalance", walletBalance.setScale(2, RoundingMode.HALF_UP).toPlainString());
        payload.put("currency", currency);
        if (event.items() != null && !event.items().isEmpty()) {
            payload.put("items", event.items());
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        String dedupe = "wallet_credit_notification:" + event.saleId();
        notificationService.tryInsertDedupeForUser(
                event.businessId(), userId, NOTIFICATION_TYPE, dedupe, json);
    }

    private String resolveShopperUserId(String businessId, Customer customer) {
        String email = customer.getEmail();
        if (email == null || email.isBlank()) {
            return null;
        }
        String norm = email.trim().toLowerCase(Locale.ROOT);
        return userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(businessId, norm)
                .map(u -> u.getId())
                .orElse(null);
    }

    private String resolvePrimaryPhoneDigits(String customerId) {
        List<CustomerPhone> phones = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        if (phones.isEmpty()) {
            return null;
        }
        CustomerPhone pick = phones.stream().filter(CustomerPhone::isPrimary).findFirst().orElse(phones.getFirst());
        return StkPhoneNormalizer.normalize(pick.getPhone());
    }

    static String buildMessage(
            String customerName,
            String shopName,
            List<CreditSaleReminderLineItem> items,
            int itemCount,
            BigDecimal walletCreditedAmount,
            BigDecimal tabAppliedAmount,
            BigDecimal walletBalance,
            BigDecimal balanceOwed,
            String currency,
            String accountUrl
    ) {
        BigDecimal walletAmt = n(walletCreditedAmount);
        BigDecimal tabAmt = n(tabAppliedAmount);
        BigDecimal owed = n(balanceOwed);
        BigDecimal walletBal = n(walletBalance);
        BigDecimal changeTotal = tabAmt.add(walletAmt);
        BigDecimal wasOwed = owed.add(tabAmt);
        String shop = shortShopLabel(shopName);

        StringBuilder sb = new StringBuilder();
        sb.append(shop).append(": ");

        if (tabAmt.signum() > 0 && walletAmt.signum() > 0) {
            sb.append("Your purchase change — ")
                    .append(formatMoney(tabAmt, currency))
                    .append(" to tab, ")
                    .append(formatMoney(walletAmt, currency))
                    .append(" to wallet. Now owed: ")
                    .append(formatMoney(owed, currency))
                    .append(" (was ")
                    .append(formatMoney(wasOwed, currency))
                    .append("). Wallet: ")
                    .append(formatMoney(walletBal, currency))
                    .append(".");
        } else if (tabAmt.signum() > 0) {
            sb.append("Your purchase change (")
                    .append(formatMoney(changeTotal, currency))
                    .append(") reduced your outstanding balance. Now owed: ")
                    .append(formatMoney(owed, currency))
                    .append(" (was ")
                    .append(formatMoney(wasOwed, currency))
                    .append(").");
        } else {
            sb.append("Your purchase change (")
                    .append(formatMoney(changeTotal, currency))
                    .append(") was added to your wallet. Balance: ")
                    .append(formatMoney(walletBal, currency))
                    .append(".");
        }

        if (accountUrl != null && !accountUrl.isBlank()) {
            sb.append(" ").append(accountUrl.trim());
        }
        return sb.toString();
    }

    /** Prefer a short SMS brand label (first word when multi-word / before &). */
    static String shortShopLabel(String shopName) {
        if (shopName == null || shopName.isBlank()) {
            return "Shop";
        }
        String trimmed = shopName.trim();
        int amp = trimmed.indexOf('&');
        if (amp > 0) {
            trimmed = trimmed.substring(0, amp).trim();
        }
        int space = trimmed.indexOf(' ');
        if (space > 0) {
            return trimmed.substring(0, space);
        }
        if (trimmed.length() > 32) {
            return trimmed.substring(0, 32).trim();
        }
        return trimmed;
    }

    private static BigDecimal n(BigDecimal raw) {
        return raw == null ? BigDecimal.ZERO : raw;
    }

    private static String formatMoney(BigDecimal amount, String currency) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.UK);
        nf.setMinimumFractionDigits(scaled.scale() > 0 && scaled.remainder(BigDecimal.ONE).signum() != 0 ? 2 : 0);
        nf.setMaximumFractionDigits(2);
        return currency + " " + nf.format(scaled);
    }

    private void saveDispatch(
            WalletCreditNotificationEvent event,
            String channel,
            String outcome,
            String detail,
            String messagePreview
    ) {
        WalletCreditNotificationDispatch row = new WalletCreditNotificationDispatch();
        row.setBusinessId(event.businessId());
        row.setSaleId(event.saleId());
        row.setCustomerId(event.customerId());
        row.setChannel(channel);
        row.setOutcome(outcome);
        row.setDetail(truncate(detail, 500));
        row.setMessagePreview(truncate(messagePreview, 500));
        dispatchRepository.save(row);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
