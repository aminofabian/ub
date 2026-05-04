package zelisline.ub.integrations.privacy.application;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;

/**
 * Erases direct customer identifiers while keeping stable foreign keys on sales / credit ledgers.
 */
@Service
@RequiredArgsConstructor
public class CustomerAnonymisationService {

    /** Display label stored after anonymisation (no PII). */
    public static final String REDACTED_NAME = "Redacted";

    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;

    @Transactional
    public void anonymiseCustomer(String businessId, String customerId) {
        Customer c = customerRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
        if (c.getAnonymisedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Customer already anonymised");
        }
        customerPhoneRepository.deleteByCustomerId(customerId);
        c.setName(REDACTED_NAME);
        c.setEmail(null);
        c.setNotes(null);
        c.setAnonymisedAt(Instant.now());
        customerRepository.save(c);
    }
}
