package zelisline.ub.credits.sms.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.ContactCounts;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.ContactRow;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.ContactsPageResponse;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.DailyTotal;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.StatusCounts;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.MessageRow;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.ReasonTotal;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.SummaryResponse;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.UpdateConsentRequest;
import zelisline.ub.credits.sms.domain.SmsMarketingStatus;
import zelisline.ub.credits.sms.domain.SmsPhones;
import zelisline.ub.credits.sms.domain.SmsSendWindow;
import zelisline.ub.messaging.application.SmsMessageLogService;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsMessage;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.sales.repository.SaleRepository;

/**
 * Read side of the SMS Hub (ADR 0013): who the shop can text, and what it has sent.
 * Contacts are the existing customers directory seen through SMS reachability; there
 * is no second address book.
 */
@Service
@RequiredArgsConstructor
public class SmsHubContactsService {

    public static final String REACH_CAN_TEXT = "CAN_TEXT";
    public static final String REACH_RECEIPTS_ONLY = "RECEIPTS_ONLY";
    public static final String REACH_OPTED_OUT = "OPTED_OUT";
    public static final String REACH_NO_NUMBER = "NO_NUMBER";

    private static final int MAX_PAGE_SIZE = 100;

    private final CustomerRepository customerRepository;
    private final SmsAudienceService audienceService;
    private final SaleRepository saleRepository;
    private final SmsMessageLogService messageLog;
    private final CustomerSmsConsentService consentService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;

    private record Entry(Customer customer, CustomerPhone phone, String reach) {
    }

    @Transactional(readOnly = true)
    public ContactsPageResponse contacts(String businessId, String reachFilter, String query, int page, int size) {
        List<Entry> all = entries(businessId);
        ContactCounts counts = counts(all);

        String reach = reachFilter == null || reachFilter.isBlank()
                ? null
                : reachFilter.trim().toUpperCase(Locale.ROOT);
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String needleDigits = needle.replaceAll("\\D+", "");

        List<Entry> filtered = new ArrayList<>();
        for (Entry entry : all) {
            if (reach != null && !reach.equals(entry.reach())) {
                continue;
            }
            if (!needle.isEmpty() && !matches(entry, needle, needleDigits)) {
                continue;
            }
            filtered.add(entry);
        }
        filtered.sort(Comparator.comparing(
                e -> SmsAudienceService.displayName(e.customer()).toLowerCase(Locale.ROOT)));

        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        int pageIndex = Math.max(0, page);
        int from = Math.min(filtered.size(), pageIndex * pageSize);
        int to = Math.min(filtered.size(), from + pageSize);
        List<Entry> slice = filtered.subList(from, to);

        Map<String, Object[]> stats = purchaseStats(businessId);
        List<ContactRow> rows = new ArrayList<>();
        for (Entry entry : slice) {
            Customer c = entry.customer();
            Object[] stat = stats.get(c.getId());
            rows.add(new ContactRow(
                    c.getId(),
                    SmsAudienceService.displayName(c),
                    SmsPhones.masked(entry.phone()) ? null : SmsAudienceService.displayPhone(entry.phone()),
                    SmsPhones.masked(entry.phone()) ? entry.phone().getMaskedMsisdn() : null,
                    c.getOrigin(),
                    entry.reach(),
                    c.getSmsMarketingSource(),
                    c.getSmsMarketingUpdatedAt(),
                    stat != null && stat[4] instanceof Instant last ? last : null,
                    stat != null ? SmsMessageLogService.asLong(stat[1]) : 0L));
        }
        int totalPages = filtered.isEmpty() ? 0 : (filtered.size() + pageSize - 1) / pageSize;
        return new ContactsPageResponse(rows, pageIndex, pageSize, filtered.size(), totalPages, counts);
    }

    @Transactional(readOnly = true)
    public SummaryResponse summary(String businessId) {
        Instant since = Instant.now().minus(Duration.ofDays(30));
        long[] totals = messageLog.totalsSince(businessId, since);
        List<ReasonTotal> byReason = new ArrayList<>();
        for (Object[] row : messageLog.totalsByReasonSince(businessId, since)) {
            byReason.add(new ReasonTotal(
                    row[0] == null ? "sms" : String.valueOf(row[0]),
                    SmsMessageLogService.asLong(row[1]),
                    SmsMessageLogService.asLong(row[2])));
        }
        TenantMessagingConfig messaging =
                messagingSettingsService.resolveForTest(businessId, SmsSendReason.CAMPAIGN);
        // Thirty Nairobi days ending today, oldest first, so the chart has no gaps.
        LocalDate today = LocalDate.now(SmsSendWindow.ZONE);
        Map<LocalDate, long[]> perDay = new LinkedHashMap<>();
        for (int back = 29; back >= 0; back--) {
            perDay.put(today.minusDays(back), new long[] {0L, 0L});
        }
        long sent = 0;
        long delivered = 0;
        long failed = 0;
        long blocked = 0;
        Instant chartSince = today.minusDays(29).atStartOfDay(SmsSendWindow.ZONE).toInstant();
        for (Object[] row : messageLog.activitySince(businessId, chartSince)) {
            if (row == null || !(row[0] instanceof Instant at)) {
                continue;
            }
            String status = row[1] == null ? "" : String.valueOf(row[1]);
            switch (status) {
                case SmsMessage.STATUS_DELIVERED -> delivered++;
                case SmsMessage.STATUS_SENT -> sent++;
                case SmsMessage.STATUS_BLOCKED -> blocked++;
                default -> failed++;
            }
            if (SmsMessage.STATUS_SENT.equals(status) || SmsMessage.STATUS_DELIVERED.equals(status)) {
                long[] bucket = perDay.get(at.atZone(SmsSendWindow.ZONE).toLocalDate());
                if (bucket != null) {
                    bucket[0]++;
                    bucket[1] += SmsMessageLogService.asLong(row[2]);
                }
            }
        }
        List<DailyTotal> daily = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> entry : perDay.entrySet()) {
            daily.add(new DailyTotal(entry.getKey().toString(), entry.getValue()[0], entry.getValue()[1]));
        }
        return new SummaryResponse(
                counts(entries(businessId)),
                totals[0],
                totals[1],
                byReason,
                daily,
                new StatusCounts(sent, delivered, failed, blocked),
                messaging.secretsReadable() && messaging.smsConfigured(),
                SmsSendWindow.describe());
    }

    @Transactional
    public int updateConsent(String businessId, UpdateConsentRequest request) {
        String status = SmsMarketingStatus.parse(request.status());
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown consent status");
        }
        if (SmsMarketingStatus.OPTED_IN.equals(status) && !Boolean.TRUE.equals(request.customerAgreed())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Confirm that these customers agreed to receive offers by SMS");
        }
        return consentService.setForCustomers(
                businessId, request.customerIds(), status, SmsMarketingStatus.SOURCE_STAFF);
    }

    @Transactional(readOnly = true)
    public Page<MessageRow> messages(String businessId, String customerId, String campaignId, Pageable pageable) {
        return messageLog.page(businessId, customerId, campaignId, pageable).map(SmsHubContactsService::toRow);
    }

    private static MessageRow toRow(SmsMessage m) {
        return new MessageRow(
                m.getId(),
                m.getCustomerId(),
                m.getPhone(),
                m.getBody(),
                m.getParts(),
                m.getCredits(),
                m.getMessageClass(),
                m.getReason(),
                m.getCampaignId(),
                m.getProvider(),
                m.getStatus(),
                m.getError(),
                m.getCreatedAt(),
                m.getDeliveredAt());
    }

    private List<Entry> entries(String businessId) {
        List<Customer> customers = customerRepository.findByBusinessIdAndDeletedAtIsNull(businessId).stream()
                .filter(c -> c.getAnonymisedAt() == null)
                .toList();
        Map<String, CustomerPhone> phones = audienceService.primaryPhones(customers);
        List<Entry> out = new ArrayList<>();
        for (Customer customer : customers) {
            CustomerPhone phone = phones.get(customer.getId());
            out.add(new Entry(customer, phone, reach(customer, phone)));
        }
        return out;
    }

    static String reach(Customer customer, CustomerPhone phone) {
        if (SmsPhones.textableDigits(phone) == null) {
            return REACH_NO_NUMBER;
        }
        String status = customer.getSmsMarketingStatus();
        if (SmsMarketingStatus.OPTED_OUT.equals(status)) {
            return REACH_OPTED_OUT;
        }
        return SmsMarketingStatus.optedIn(status) ? REACH_CAN_TEXT : REACH_RECEIPTS_ONLY;
    }

    private static ContactCounts counts(List<Entry> entries) {
        int canText = 0;
        int receiptsOnly = 0;
        int optedOut = 0;
        int noNumber = 0;
        for (Entry entry : entries) {
            switch (entry.reach()) {
                case REACH_CAN_TEXT -> canText++;
                case REACH_OPTED_OUT -> optedOut++;
                case REACH_NO_NUMBER -> noNumber++;
                default -> receiptsOnly++;
            }
        }
        return new ContactCounts(entries.size(), canText, receiptsOnly, optedOut, noNumber);
    }

    private static boolean matches(Entry entry, String needle, String needleDigits) {
        if (SmsAudienceService.displayName(entry.customer()).toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        if (needleDigits.length() < 3 || entry.phone() == null || entry.phone().getPhone() == null) {
            return false;
        }
        String digits = entry.phone().getPhone().replaceAll("\\D+", "");
        String local = needleDigits.startsWith("0") ? needleDigits.substring(1) : needleDigits;
        return digits.contains(needleDigits) || digits.contains(local);
    }

    private Map<String, Object[]> purchaseStats(String businessId) {
        Map<String, Object[]> out = new HashMap<>();
        for (Object[] row : saleRepository.aggregatePurchaseStatsByCustomer(businessId)) {
            if (row != null && row[0] != null) {
                out.put(String.valueOf(row[0]), row);
            }
        }
        return out;
    }
}
