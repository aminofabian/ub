package zelisline.ub.credits.sms.application;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.sms.domain.SmsMarketingStatus;

/**
 * Records SMS marketing consent on customers (SMS Hub, ADR 0013). Every change keeps
 * its source and time so the shop can show how and when a customer agreed or opted out.
 */
@Service
@RequiredArgsConstructor
public class CustomerSmsConsentService {

    public static final int MAX_BATCH = 500;

    private final CustomerRepository customerRepository;

    /**
     * Staff-recorded consent for selected customers of one business.
     *
     * @return number of customers whose status changed
     */
    @Transactional
    public int setForCustomers(String businessId, Collection<String> customerIds, String rawStatus, String source) {
        String status = SmsMarketingStatus.parse(rawStatus);
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown consent status");
        }
        Set<String> ids = new LinkedHashSet<>();
        if (customerIds != null) {
            for (String id : customerIds) {
                if (id != null && !id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        }
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one customer");
        }
        if (ids.size() > MAX_BATCH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Too many customers at once (max " + MAX_BATCH + ")");
        }
        List<Customer> customers = customerRepository.findByIdInAndBusinessId(ids, businessId);
        int changed = 0;
        for (Customer customer : customers) {
            if (apply(customer, status, source)) {
                changed++;
            }
        }
        return changed;
    }

    /** Applies a status to one already-loaded customer. Returns false when nothing changed. */
    @Transactional
    public boolean apply(Customer customer, String status, String source) {
        if (customer == null || customer.getDeletedAt() != null || customer.getAnonymisedAt() != null) {
            return false;
        }
        if (status.equals(customer.getSmsMarketingStatus())) {
            return false;
        }
        customer.setSmsMarketingStatus(status);
        customer.setSmsMarketingSource(source);
        customer.setSmsMarketingUpdatedAt(Instant.now());
        customerRepository.save(customer);
        return true;
    }
}
