package zelisline.ub.credits.sms.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.credits.email.api.dto.CustomerEmailCampaignDtos.AudienceFilter;
import zelisline.ub.credits.email.application.CustomerEmailAudienceService;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.sms.api.dto.CustomerSmsCampaignDtos.SmsAudienceRow;
import zelisline.ub.credits.sms.domain.CustomerSmsCampaign;
import zelisline.ub.credits.sms.domain.SmsPhones;

/**
 * Resolves who an SMS campaign would reach. Selection (specific, filtered, everyone)
 * is shared with the email composer; this adds SMS eligibility: a textable number and
 * marketing consent.
 */
@Service
@RequiredArgsConstructor
public class SmsAudienceService {

    private static final int SAMPLE_LIMIT = 40;

    private final CustomerEmailAudienceService emailAudienceService;
    private final CustomerPhoneRepository customerPhoneRepository;

    /** {@code phoneDigits} is null for excluded people; {@code skipReason} is null for eligible ones. */
    public record Person(String customerId, String name, String phoneDigits, String displayPhone, String skipReason) {
    }

    public record Audience(List<Person> matched, List<Person> eligible, List<Person> excluded) {
    }

    @Transactional(readOnly = true)
    public Audience resolve(String businessId, String recipientMethod, List<String> customerIds, AudienceFilter filter) {
        List<Customer> candidates =
                emailAudienceService.matchCustomers(businessId, recipientMethod, customerIds, filter);
        if (candidates.size() > CustomerSmsCampaign.MAX_RECIPIENTS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "This audience has " + candidates.size() + " customers. One campaign can reach at most "
                            + CustomerSmsCampaign.MAX_RECIPIENTS + "; narrow it with a filter.");
        }
        Map<String, CustomerPhone> phones = primaryPhones(candidates);
        List<Person> matched = new ArrayList<>();
        List<Person> eligible = new ArrayList<>();
        List<Person> excluded = new ArrayList<>();
        for (Customer customer : candidates) {
            CustomerPhone phone = phones.get(customer.getId());
            SmsPromoGuard.Decision decision = SmsPromoGuard.eligibility(customer, phone);
            Person person = new Person(
                    customer.getId(),
                    displayName(customer),
                    decision.phoneDigits(),
                    displayPhone(phone),
                    decision.reason());
            matched.add(person);
            if (decision.allowed()) {
                eligible.add(person);
            } else {
                excluded.add(person);
            }
        }
        return new Audience(matched, eligible, excluded);
    }

    public String normalizeMethod(String method) {
        return emailAudienceService.normalizeMethod(method);
    }

    public String serializeFilter(AudienceFilter filter) {
        return emailAudienceService.serializeFilter(filter);
    }

    public AudienceFilter deserializeFilter(String json) {
        return emailAudienceService.deserializeFilter(json);
    }

    public String shopName(String businessId) {
        return emailAudienceService.shopName(businessId);
    }

    public static List<SmsAudienceRow> sample(List<Person> people) {
        List<SmsAudienceRow> rows = new ArrayList<>();
        for (Person p : people) {
            if (rows.size() >= SAMPLE_LIMIT) {
                break;
            }
            rows.add(new SmsAudienceRow(p.customerId(), p.name(), p.displayPhone(), p.skipReason()));
        }
        return rows;
    }

    /** Each customer's primary phone (else their oldest). */
    public Map<String, CustomerPhone> primaryPhones(List<Customer> customers) {
        Map<String, CustomerPhone> out = new HashMap<>();
        if (customers.isEmpty()) {
            return out;
        }
        List<String> ids = customers.stream().map(Customer::getId).toList();
        Map<String, List<CustomerPhone>> grouped = new HashMap<>();
        // Chunked: an IN list with thousands of ids is slow and can exceed driver limits.
        for (int from = 0; from < ids.size(); from += 500) {
            List<String> chunk = ids.subList(from, Math.min(ids.size(), from + 500));
            for (CustomerPhone phone : customerPhoneRepository.findByCustomerIdIn(chunk)) {
                grouped.computeIfAbsent(phone.getCustomerId(), k -> new ArrayList<>()).add(phone);
            }
        }
        for (Map.Entry<String, List<CustomerPhone>> entry : grouped.entrySet()) {
            List<CustomerPhone> list = entry.getValue();
            list.sort((a, b) -> {
                if (a.getCreatedAt() == null || b.getCreatedAt() == null) {
                    return 0;
                }
                return a.getCreatedAt().compareTo(b.getCreatedAt());
            });
            out.put(entry.getKey(), SmsPhones.pick(list));
        }
        return out;
    }

    public static String displayName(Customer customer) {
        if (customer == null || customer.getName() == null || customer.getName().isBlank()) {
            return "Customer";
        }
        return customer.getName().trim();
    }

    /** Local 07… form for a textable number, the M-Pesa mask for a hidden one, else null. */
    public static String displayPhone(CustomerPhone phone) {
        if (phone == null) {
            return null;
        }
        if (SmsPhones.masked(phone)) {
            return phone.getMaskedMsisdn();
        }
        String local = KenyanPhoneForms.toLocal07(phone.getPhone());
        return local != null ? local : phone.getPhone();
    }
}
