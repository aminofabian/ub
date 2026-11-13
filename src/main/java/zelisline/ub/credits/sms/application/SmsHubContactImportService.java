package zelisline.ub.credits.sms.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.CreateCustomerRequest;
import zelisline.ub.credits.api.dto.CustomerPhoneDraft;
import zelisline.ub.credits.api.dto.CustomerResponse;
import zelisline.ub.credits.api.dto.PatchCustomerRequest;
import zelisline.ub.credits.application.CustomerDirectoryService;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.ImportContactRow;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.ImportContactsRequest;
import zelisline.ub.credits.sms.api.dto.SmsHubDtos.ImportContactsResponse;
import zelisline.ub.credits.sms.domain.SmsMarketingStatus;
import zelisline.ub.credits.sms.domain.SmsTemplates;

/**
 * Adds phone numbers to the SMS Hub by hand or from a file (ADR 0013). A number is a
 * customer: there is no second address book, so each new number goes through
 * {@link CustomerDirectoryService} like any customer staff register. Consent is only
 * recorded when staff confirm the people agreed, and an opt-out is never undone here.
 *
 * <p>Deliberately not transactional: each row commits on its own, so one bad row
 * cannot roll back the rest and a retried upload simply finds its numbers on file.
 */
@Service
@RequiredArgsConstructor
public class SmsHubContactImportService {

    public static final int MAX_ROWS = 500;
    private static final int MAX_SAMPLES = 20;
    private static final int MAX_NAME = 200;

    private static final Logger log = LoggerFactory.getLogger(SmsHubContactImportService.class);

    private final CustomerDirectoryService directoryService;
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CustomerSmsConsentService consentService;

    public ImportContactsResponse importContacts(String businessId, String actorUserId, ImportContactsRequest request) {
        List<ImportContactRow> rows = request.rows() == null ? List.of() : request.rows();
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add at least one phone number");
        }
        if (rows.size() > MAX_ROWS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Too many numbers at once (max " + MAX_ROWS + ")");
        }
        boolean agreed = Boolean.TRUE.equals(request.customerAgreed());

        int added = 0;
        int alreadyThere = 0;
        int markedAgreed = 0;
        int keptOptedOut = 0;
        int renamed = 0;
        int invalid = 0;
        int failed = 0;
        List<String> invalidSamples = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (ImportContactRow row : rows) {
            String raw = row == null || row.phone() == null ? "" : row.phone().trim();
            String msisdn = KenyanPhoneForms.toMsisdn254(raw);
            if (msisdn == null) {
                invalid++;
                if (invalidSamples.size() < MAX_SAMPLES) {
                    invalidSamples.add(raw.isEmpty() ? "(blank)" : raw.substring(0, Math.min(raw.length(), 30)));
                }
                continue;
            }
            if (!seen.add(msisdn)) {
                continue;
            }
            String name = cleanName(row.name());
            try {
                Customer customer = holderOf(businessId, msisdn).orElse(null);
                if (customer == null) {
                    customer = create(businessId, actorUserId, msisdn, name);
                    added++;
                } else {
                    alreadyThere++;
                    if (name != null && SmsTemplates.usableName(customer.getName()) == null) {
                        // The number was on file under a placeholder (the number itself).
                        directoryService.patch(
                                businessId,
                                customer.getId(),
                                new PatchCustomerRequest(name, null, null, null, null, null, null, null),
                                actorUserId);
                        renamed++;
                        customer = customerRepository
                                .findByIdAndBusinessIdAndDeletedAtIsNull(customer.getId(), businessId)
                                .orElse(customer);
                    }
                }
                if (agreed) {
                    if (SmsMarketingStatus.OPTED_OUT.equals(customer.getSmsMarketingStatus())) {
                        keptOptedOut++;
                    } else if (consentService.apply(
                            customer, SmsMarketingStatus.OPTED_IN, SmsMarketingStatus.SOURCE_STAFF)) {
                        markedAgreed++;
                    }
                }
            } catch (RuntimeException ex) {
                // No number or name in the log: both are customer data.
                log.warn("sms_hub contact import row failed business={}: {}", businessId, ex.getClass().getSimpleName());
                failed++;
            }
        }
        return new ImportContactsResponse(
                added, alreadyThere, markedAgreed, keptOptedOut, renamed, invalid, failed, invalidSamples);
    }

    private Customer create(String businessId, String actorUserId, String msisdn, String name) {
        String local = KenyanPhoneForms.toLocal07(msisdn);
        String phone = local != null ? local : msisdn;
        // Without a name the number stands in for it, the same placeholder a till M-Pesa
        // prompt leaves; the payer's M-Pesa name replaces it on their next payment.
        CustomerResponse created = directoryService.create(
                businessId,
                new CreateCustomerRequest(
                        name != null ? name : phone,
                        null,
                        null,
                        null,
                        List.of(new CustomerPhoneDraft(phone, true)),
                        null),
                actorUserId);
        return customerRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(created.id(), businessId)
                .orElseThrow(() -> new IllegalStateException("Customer not found after create"));
    }

    private Optional<Customer> holderOf(String businessId, String msisdn) {
        for (String candidate : KenyanPhoneForms.lookupCandidates(msisdn)) {
            Optional<CustomerPhone> row = customerPhoneRepository.findFirstByBusinessIdAndPhone(businessId, candidate);
            if (row.isPresent()) {
                return customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull(
                        row.get().getCustomerId(), businessId);
            }
        }
        return Optional.empty();
    }

    /** A name with at least one letter, trimmed and bounded; otherwise null. */
    private static String cleanName(String raw) {
        String usable = SmsTemplates.usableName(raw);
        if (usable == null) {
            return null;
        }
        String collapsed = usable.replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
        return collapsed.length() > MAX_NAME ? collapsed.substring(0, MAX_NAME).trim() : collapsed;
    }
}
