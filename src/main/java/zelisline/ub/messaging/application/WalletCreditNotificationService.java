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
    static final int MAX_ITEM_LINES = 5;

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
        StringBuilder sb = new StringBuilder();
        String greeting = (customerName == null || customerName.isBlank()) ? "Hi" : "Hi " + customerName.trim();
        sb.append(greeting).append(",\n\n");

        BigDecimal walletAmt = walletCreditedAmount != null ? walletCreditedAmount : BigDecimal.ZERO;
        BigDecimal tabAmt = tabAppliedAmount != null ? tabAppliedAmount : BigDecimal.ZERO;

        if (tabAmt.signum() > 0 && walletAmt.signum() > 0) {
            sb.append(formatMoney(tabAmt, currency))
                    .append(" went to your tab and ")
                    .append(formatMoney(walletAmt, currency))
                    .append(" to your wallet at ")
                    .append(shopName)
                    .append(" (change from your purchase):\n");
        } else if (tabAmt.signum() > 0) {
            sb.append(formatMoney(tabAmt, currency))
                    .append(" was applied to your tab at ")
                    .append(shopName)
                    .append(" (change from your purchase):\n");
        } else {
            sb.append(formatMoney(walletAmt, currency))
                    .append(" was added to your wallet at ")
                    .append(shopName)
                    .append(" (change from your purchase):\n");
        }

        List<CreditSaleReminderLineItem> lines = items != null ? items : List.of();
        if (lines.isEmpty()) {
            int count = Math.max(1, itemCount);
            String label = count == 1 ? "item" : "items";
            sb.append("• ").append(count).append(" ").append(label).append("\n");
        } else {
            int shown = 0;
            for (CreditSaleReminderLineItem line : lines) {
                if (shown >= MAX_ITEM_LINES) {
                    break;
                }
                sb.append("• ")
                        .append(line.name() != null ? line.name() : "Item")
                        .append(" — ")
                        .append(formatMoney(line.lineTotal(), currency))
                        .append("\n");
                shown++;
            }
            int remaining = lines.size() - shown;
            if (remaining > 0) {
                sb.append("• and ").append(remaining).append(" more\n");
            }
        }

        sb.append("\n");
        if (tabAmt.signum() > 0 || (balanceOwed != null && balanceOwed.signum() > 0)) {
            sb.append("Tab balance: ").append(formatMoney(balanceOwed != null ? balanceOwed : BigDecimal.ZERO, currency));
            sb.append("\n");
        }
        sb.append("Wallet balance: ").append(formatMoney(walletBalance, currency));
        sb.append("\n\nView purchases: ").append(accountUrl);
        return sb.toString();
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
