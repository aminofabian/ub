package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerOrigins;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.domain.CustomerPhoneNormalizer;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.credits.domain.MaskedMsisdn;
import zelisline.ub.credits.domain.PayerNameNormalizer;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.payments.domain.spi.WebhookResult;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.repository.SaleRepository;

/**
 * Resolve or create inferred customers from Kopokopo buygoods payer identity
 * (first name + last name + masked MSISDN fingerprint).
 */
@Service
@RequiredArgsConstructor
public class MpesaPayerIdentityService {

    private static final Logger log = LoggerFactory.getLogger(MpesaPayerIdentityService.class);

    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final SaleRepository saleRepository;

    @Transactional
    public Optional<Customer> resolveFromWebhook(String businessId, WebhookResult parsed) {
        if (parsed == null) {
            return Optional.empty();
        }
        return resolveOrCreate(
                businessId,
                parsed.firstName(),
                parsed.lastName(),
                parsed.phoneIsMasked() ? parsed.maskedPhone() : parsed.phoneNumber(),
                parsed.phoneIsMasked());
    }

    @Transactional
    public Optional<Customer> resolveOrCreate(
            String businessId,
            String firstNameRaw,
            String lastNameRaw,
            String phoneRaw,
            boolean phoneIsMasked
    ) {
        String first = PayerNameNormalizer.normalize(firstNameRaw);
        String last = PayerNameNormalizer.normalize(lastNameRaw);
        if (first.isEmpty()) {
            return Optional.empty();
        }
        String fingerprint = MaskedMsisdn.fingerprint(phoneRaw);
        if (fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }
        String identityKey = PayerNameNormalizer.identityKey(first, last, fingerprint);
        if (identityKey == null) {
            return Optional.empty();
        }

        if (!phoneIsMasked && phoneRaw != null) {
            Optional<Customer> byPhone = findByRealPhone(businessId, phoneRaw);
            if (byPhone.isPresent()) {
                Customer existing = byPhone.get();
                applyNamesIfAllowed(existing, first, last);
                ensureIdentityKey(existing, identityKey);
                ensureMaskedPhoneRow(existing, phoneRaw, phoneIsMasked);
                return Optional.of(existing);
            }
        }

        Optional<Customer> byKey = customerRepository
                .findByBusinessIdAndMpesaIdentityKeyAndDeletedAtIsNull(businessId, identityKey);
        if (byKey.isPresent()) {
            Customer existing = byKey.get();
            applyNamesIfAllowed(existing, first, last);
            ensureMaskedPhoneRow(existing, phoneRaw, phoneIsMasked);
            return Optional.of(existing);
        }

        Optional<Customer> byMaskFit = findByNameAndMaskFit(businessId, first, last, phoneRaw);
        if (byMaskFit.isPresent()) {
            Customer existing = byMaskFit.get();
            applyNamesIfAllowed(existing, first, last);
            ensureIdentityKey(existing, identityKey);
            ensureMaskedPhoneRow(existing, phoneRaw, phoneIsMasked);
            return Optional.of(existing);
        }

        return Optional.of(createInferred(businessId, first, last, identityKey, phoneRaw, phoneIsMasked));
    }

    /**
     * Staff / cashier registering a real number: if an inferred payer has the same
     * first+last name and a mask that this number fits, attach instead of duplicating.
     */
    @Transactional
    public Optional<Customer> attachStaffRegistration(
            String businessId,
            String fullName,
            String realPhoneRaw
    ) {
        String[] parts = PayerNameNormalizer.splitDisplayName(fullName);
        String first = PayerNameNormalizer.normalize(parts[0]);
        String last = PayerNameNormalizer.normalize(parts[1]);
        if (first.isEmpty() || last.isEmpty()) {
            return Optional.empty();
        }
        Optional<Customer> match = findByNameAndMaskFit(businessId, first, last, realPhoneRaw);
        if (match.isEmpty()) {
            return Optional.empty();
        }
        Customer customer = match.get();
        fillRealPhone(customer, realPhoneRaw, false);
        return Optional.of(customer);
    }

    @Transactional
    public void attachToSaleIfUnassigned(String businessId, String saleId, String customerId) {
        if (businessId == null || saleId == null || customerId == null) {
            return;
        }
        Sale sale = saleRepository.findByIdAndBusinessId(saleId, businessId).orElse(null);
        if (sale == null) {
            return;
        }
        if (sale.getCustomerId() != null && !sale.getCustomerId().isBlank()) {
            return;
        }
        sale.setCustomerId(customerId);
        saleRepository.save(sale);
    }

    /**
     * After a credit-tab STK, copy Kopokopo names onto the customer when the paying
     * number is already on file.
     */
    @Transactional
    public void applyStkPayerNames(
            String businessId,
            String customerId,
            String payingPhoneRaw,
            String firstNameRaw,
            String lastNameRaw
    ) {
        String first = PayerNameNormalizer.normalize(firstNameRaw);
        String last = PayerNameNormalizer.normalize(lastNameRaw);
        if (first.isEmpty() && last.isEmpty()) {
            return;
        }
        if ("CUSTOMER".equals(first) && last.isEmpty()) {
            return;
        }
        Customer customer = customerRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(customerId, businessId)
                .orElse(null);
        if (customer == null) {
            return;
        }
        if (!phoneBelongsToCustomer(customerId, payingPhoneRaw)) {
            return;
        }
        applyNamesIfAllowed(customer, first, last);
    }

    @Transactional
    public void markSelfVerified(Customer customer, String realPhoneRaw) {
        fillRealPhone(customer, realPhoneRaw, true);
        customer.setOrigin(CustomerOrigins.SELF_VERIFIED);
        customerRepository.save(customer);
    }

    public static String genericClaimFailure() {
        return "No matching M-Pesa payer found for that name and number";
    }

    private Customer createInferred(
            String businessId,
            String first,
            String last,
            String identityKey,
            String phoneRaw,
            boolean phoneIsMasked
    ) {
        Customer customer = new Customer();
        customer.setBusinessId(businessId);
        customer.setCustomerNo(customerRepository.nextCustomerNo(businessId));
        customer.setFirstName(title(first));
        customer.setLastName(title(last));
        customer.setFirstNameNorm(first);
        customer.setLastNameNorm(last);
        customer.setName(PayerNameNormalizer.displayName(title(first), title(last)));
        customer.setOrigin(CustomerOrigins.MPESA_INFERRED);
        customer.setMpesaIdentityKey(identityKey);
        customer.setMpesaNameUpdatedAt(Instant.now());
        try {
            customerRepository.save(customer);
        } catch (DataIntegrityViolationException e) {
            log.info("Inferred customer identity race business={} key={}", businessId, identityKey);
            return customerRepository
                    .findByBusinessIdAndMpesaIdentityKeyAndDeletedAtIsNull(businessId, identityKey)
                    .orElseThrow(() -> e);
        }

        persistPhoneRow(customer, phoneRaw, phoneIsMasked);
        openZeroLimitTab(businessId, customer.getId());
        log.info("Created inferred M-Pesa payer business={} customerNo={} name={}",
                businessId, customer.getCustomerNo(), customer.getName());
        return customer;
    }

    private void persistPhoneRow(Customer customer, String phoneRaw, boolean phoneIsMasked) {
        List<CustomerPhone> existing = customerPhoneRepository
                .findByCustomerIdOrderByCreatedAtAsc(customer.getId());
        if (!existing.isEmpty()) {
            ensureMaskedPhoneRow(customer, phoneRaw, phoneIsMasked);
            return;
        }
        CustomerPhone row = new CustomerPhone();
        row.setBusinessId(customer.getBusinessId());
        row.setCustomerId(customer.getId());
        row.setPrimary(true);
        applyPhoneFields(row, phoneRaw, phoneIsMasked);
        customerPhoneRepository.save(row);
    }

    private void ensureMaskedPhoneRow(Customer customer, String phoneRaw, boolean phoneIsMasked) {
        List<CustomerPhone> existing = customerPhoneRepository
                .findByCustomerIdOrderByCreatedAtAsc(customer.getId());
        CustomerPhone row = existing.stream().filter(CustomerPhone::isPrimary).findFirst()
                .orElse(existing.isEmpty() ? null : existing.getFirst());
        if (row == null) {
            persistPhoneRow(customer, phoneRaw, phoneIsMasked);
            return;
        }
        applyPhoneFields(row, phoneRaw, phoneIsMasked);
        customerPhoneRepository.save(row);
    }

    private void applyPhoneFields(CustomerPhone row, String phoneRaw, boolean phoneIsMasked) {
        String compact = MaskedMsisdn.compact(phoneRaw);
        String fingerprint = MaskedMsisdn.fingerprint(phoneRaw);
        String assigned = MaskedMsisdn.assignedMsisdn(phoneRaw);
        if (phoneIsMasked || MaskedMsisdn.isMasked(phoneRaw)) {
            row.setMaskedMsisdn(compact);
            row.setMaskFingerprint(fingerprint);
            row.setAssignedMsisdn(assigned);
            // Real phone stays null until OTP / unmasked webhook.
        } else if (compact != null && compact.indexOf('X') < 0) {
            if (row.getPhone() == null || row.getPhone().isBlank()) {
                String local = KenyanPhoneForms.toLocal07(compact);
                row.setPhone(local != null ? local : CustomerPhoneNormalizer.normalize(compact));
            }
            if (row.getMaskedMsisdn() == null) {
                row.setMaskFingerprint(fingerprint);
                row.setAssignedMsisdn(assigned);
            }
        }
    }

    private void fillRealPhone(Customer customer, String realPhoneRaw, boolean verified) {
        String msisdn = StkPhoneNormalizer.normalize(realPhoneRaw);
        if (msisdn == null) {
            return;
        }
        String local = KenyanPhoneForms.toLocal07(msisdn);
        String store = local != null ? local : CustomerPhoneNormalizer.normalize(msisdn);
        List<CustomerPhone> existing = customerPhoneRepository
                .findByCustomerIdOrderByCreatedAtAsc(customer.getId());
        CustomerPhone row = existing.stream().filter(CustomerPhone::isPrimary).findFirst()
                .orElse(existing.isEmpty() ? null : existing.getFirst());
        if (row == null) {
            row = new CustomerPhone();
            row.setBusinessId(customer.getBusinessId());
            row.setCustomerId(customer.getId());
            row.setPrimary(true);
        }
        row.setPhone(store);
        if (verified) {
            row.setVerifiedAt(Instant.now());
        }
        if (row.getMaskFingerprint() == null) {
            row.setMaskFingerprint(MaskedMsisdn.fingerprint(msisdn));
            row.setAssignedMsisdn(MaskedMsisdn.assignedMsisdn(msisdn));
        }
        customerPhoneRepository.save(row);
    }

    private void ensureIdentityKey(Customer customer, String identityKey) {
        if (customer.getMpesaIdentityKey() == null || customer.getMpesaIdentityKey().isBlank()) {
            customer.setMpesaIdentityKey(identityKey);
            customerRepository.save(customer);
        }
    }

    private void applyNamesIfAllowed(Customer customer, String firstNorm, String lastNorm) {
        boolean inferred = CustomerOrigins.MPESA_INFERRED.equals(customer.getOrigin());
        boolean namesEmpty = (customer.getFirstName() == null || customer.getFirstName().isBlank())
                && (customer.getLastName() == null || customer.getLastName().isBlank());
        if (!inferred && !namesEmpty) {
            return;
        }
        if (firstNorm != null && !firstNorm.isBlank()) {
            customer.setFirstName(title(firstNorm));
            customer.setFirstNameNorm(firstNorm);
        }
        if (lastNorm != null && !lastNorm.isBlank()) {
            customer.setLastName(title(lastNorm));
            customer.setLastNameNorm(lastNorm);
        }
        customer.setName(PayerNameNormalizer.displayName(customer.getFirstName(), customer.getLastName()));
        customer.setMpesaNameUpdatedAt(Instant.now());
        customerRepository.save(customer);
    }

    private Optional<Customer> findByRealPhone(String businessId, String phoneRaw) {
        for (String candidate : KenyanPhoneForms.lookupCandidates(phoneRaw)) {
            Optional<CustomerPhone> row = customerPhoneRepository.findFirstByBusinessIdAndPhone(businessId, candidate);
            if (row.isPresent()) {
                return customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull(
                        row.get().getCustomerId(), businessId);
            }
        }
        return Optional.empty();
    }

    private Optional<Customer> findByNameAndMaskFit(
            String businessId,
            String firstNorm,
            String lastNorm,
            String phoneRaw
    ) {
        String fingerprint = MaskedMsisdn.fingerprint(phoneRaw);
        if (fingerprint == null) {
            return Optional.empty();
        }
        List<CustomerPhone> candidates = customerPhoneRepository
                .findByBusinessIdAndMaskFingerprint(businessId, fingerprint);
        for (CustomerPhone phone : candidates) {
            Customer c = customerRepository
                    .findByIdAndBusinessIdAndDeletedAtIsNull(phone.getCustomerId(), businessId)
                    .orElse(null);
            if (c == null) {
                continue;
            }
            // A stored blank last name is the same payer seen before M-Pesa gave us the
            // full name; the shared mask fingerprint is the same number either way.
            String storedLast = PayerNameNormalizer.normalize(c.getLastName());
            boolean nameMatch = firstNorm.equals(PayerNameNormalizer.normalize(c.getFirstName()))
                    && (lastNorm.equals(storedLast) || storedLast.isEmpty());
            if (!nameMatch) {
                continue;
            }
            if (phone.getMaskedMsisdn() != null && MaskedMsisdn.isMasked(phone.getMaskedMsisdn())) {
                if (!MaskedMsisdn.isMasked(phoneRaw)
                        && !MaskedMsisdn.fitsMask(phone.getMaskedMsisdn(), phoneRaw)) {
                    continue;
                }
            }
            return Optional.of(c);
        }
        return Optional.empty();
    }

    private boolean phoneBelongsToCustomer(String customerId, String payingPhoneRaw) {
        if (payingPhoneRaw == null || payingPhoneRaw.isBlank()) {
            return false;
        }
        List<String> candidates = KenyanPhoneForms.lookupCandidates(payingPhoneRaw);
        for (CustomerPhone p : customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId)) {
            if (p.getPhone() == null) {
                continue;
            }
            String digits = CustomerPhoneNormalizer.normalize(p.getPhone());
            for (String candidate : candidates) {
                if (candidate.equals(digits) || candidate.equals(p.getPhone())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void openZeroLimitTab(String businessId, String customerId) {
        if (creditAccountRepository.findByCustomerIdAndBusinessId(customerId, businessId).isPresent()) {
            return;
        }
        CreditAccount row = new CreditAccount();
        row.setBusinessId(businessId);
        row.setCustomerId(customerId);
        row.setCreditLimit(BigDecimal.ZERO);
        creditAccountRepository.save(row);
    }

    private static String title(String norm) {
        if (norm == null || norm.isBlank()) {
            return norm;
        }
        String lower = norm.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
