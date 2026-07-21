package zelisline.ub.credits.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.domain.CustomerPhoneNormalizer;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;

/**
 * After a successful public tab STK, keep the paying MSISDN on the customer
 * directory (primary) so future prompts and SMS links stay in sync.
 */
@Service
@RequiredArgsConstructor
public class CustomerPhoneOnPaymentService {

    private static final Logger log = LoggerFactory.getLogger(CustomerPhoneOnPaymentService.class);

    private final CustomerPhoneRepository customerPhoneRepository;
    private final CustomerRepository customerRepository;

    @Transactional
    public void syncPrimaryPhoneAfterPayment(String businessId, String customerId, String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return;
        }
        if (!KenyanPhoneForms.looksLikeKenyanMobile(rawPhone)) {
            log.info("Skip phone sync — not a Kenyan mobile: business={} customer={}", businessId, customerId);
            return;
        }

        Customer customer = customerRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(customerId, businessId)
                .orElse(null);
        if (customer == null) {
            return;
        }

        String storePhone = KenyanPhoneForms.toLocal07(rawPhone);
        if (storePhone == null) {
            storePhone = CustomerPhoneNormalizer.normalize(rawPhone);
        }
        if (storePhone.isEmpty()) {
            return;
        }

        // Another customer already owns this number — do not steal; payment still stands.
        for (String candidate : KenyanPhoneForms.lookupCandidates(rawPhone)) {
            var page = customerRepository.findByBusinessIdAndPhoneNormalized(
                    businessId, candidate, PageRequest.of(0, 1));
            if (!page.isEmpty() && !customerId.equals(page.getContent().getFirst().getId())) {
                log.info(
                        "Skip phone sync — number already on another customer business={} phone={}",
                        businessId, storePhone);
                return;
            }
        }

        List<CustomerPhone> existing = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        CustomerPhone match = null;
        for (CustomerPhone p : existing) {
            String digits = CustomerPhoneNormalizer.normalize(p.getPhone());
            for (String candidate : KenyanPhoneForms.lookupCandidates(rawPhone)) {
                if (candidate.equals(digits) || storePhone.equals(digits)) {
                    match = p;
                    break;
                }
            }
            if (match != null) {
                break;
            }
        }

        if (match != null) {
            if (!match.isPrimary()) {
                clearPrimary(existing);
                match.setPrimary(true);
                customerPhoneRepository.save(match);
            }
            // Normalize stored form to local 07 when we can.
            if (storePhone != null && !storePhone.equals(match.getPhone())) {
                match.setPhone(storePhone);
                customerPhoneRepository.save(match);
            }
            return;
        }

        clearPrimary(existing);
        CustomerPhone row = new CustomerPhone();
        row.setBusinessId(businessId);
        row.setCustomerId(customerId);
        row.setPhone(storePhone);
        row.setPrimary(true);
        customerPhoneRepository.save(row);
        log.info("Added primary phone after tab STK business={} customer={} phone={}",
                businessId, customerId, storePhone);
    }

    private void clearPrimary(List<CustomerPhone> phones) {
        for (CustomerPhone p : phones) {
            if (p.isPrimary()) {
                p.setPrimary(false);
                customerPhoneRepository.save(p);
            }
        }
    }
}
