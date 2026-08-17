package zelisline.ub.credits.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.PublicPayerClaimLookupResponse;
import zelisline.ub.credits.api.dto.PublicPayerClaimLookupResponse.PayerClaimMatch;
import zelisline.ub.credits.api.dto.PublicPayerClaimVerifyResponse;
import zelisline.ub.credits.api.dto.SendCustomerPhoneVerificationResponse;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.credits.domain.MaskedMsisdn;
import zelisline.ub.credits.domain.PayerNameNormalizer;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;

@Service
@RequiredArgsConstructor
public class PayerPhoneClaimService {

    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CustomerPhoneVerificationService verificationService;
    private final MpesaPayerIdentityService mpesaPayerIdentityService;

    @Transactional(readOnly = true)
    public PublicPayerClaimLookupResponse lookup(
            String businessId,
            String firstName,
            String lastName,
            String lastThree
    ) {
        List<Match> matches = findMatches(businessId, firstName, lastName, lastThree);
        if (matches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, MpesaPayerIdentityService.genericClaimFailure());
        }
        List<PayerClaimMatch> out = new ArrayList<>();
        for (Match m : matches) {
            long no = m.customer().getCustomerNo() == null ? 0L : m.customer().getCustomerNo();
            out.add(new PayerClaimMatch(no, MaskedMsisdn.displayMasked(m.phone().getMaskedMsisdn()), suffixOf(m.phone())));
        }
        return new PublicPayerClaimLookupResponse(out);
    }

    @Transactional
    public SendCustomerPhoneVerificationResponse sendPublic(
            String businessId,
            String firstName,
            String lastName,
            String missingDigits,
            String lastThree
    ) {
        Resolved resolved = resolveForClaim(businessId, firstName, lastName, missingDigits, lastThree);
        return verificationService.sendForOwner(businessId, resolved.completedMsisdn(), resolved.customer().getId());
    }

    @Transactional
    public PublicPayerClaimVerifyResponse verifyPublic(
            String businessId,
            String firstName,
            String lastName,
            String missingDigits,
            String lastThree,
            String code
    ) {
        Resolved resolved = resolveForClaim(businessId, firstName, lastName, missingDigits, lastThree);
        verificationService.verify(businessId, resolved.completedMsisdn(), code);
        mpesaPayerIdentityService.markSelfVerified(resolved.customer(), resolved.completedMsisdn());
        String local = KenyanPhoneForms.toLocal07(resolved.completedMsisdn());
        String tabPath = local != null ? "/" + local : "/" + resolved.completedMsisdn();
        long no = resolved.customer().getCustomerNo() == null ? 0L : resolved.customer().getCustomerNo();
        return new PublicPayerClaimVerifyResponse(
                resolved.customer().getId(),
                no,
                resolved.customer().getName(),
                local,
                tabPath);
    }

    @Transactional
    public SendCustomerPhoneVerificationResponse sendStaffReveal(
            String businessId,
            String customerId,
            String missingDigits
    ) {
        Customer customer = load(businessId, customerId);
        String completed = completedForCustomer(customer, missingDigits);
        return verificationService.sendForOwner(businessId, completed, customer.getId());
    }

    @Transactional
    public void verifyStaffReveal(String businessId, String customerId, String missingDigits, String code) {
        Customer customer = load(businessId, customerId);
        String completed = completedForCustomer(customer, missingDigits);
        verificationService.verify(businessId, completed, code);
        mpesaPayerIdentityService.markSelfVerified(customer, completed);
    }

    private Resolved resolveForClaim(
            String businessId,
            String firstName,
            String lastName,
            String missingDigits,
            String lastThree
    ) {
        List<Match> matches = findMatches(businessId, firstName, lastName, lastThree);
        if (matches.size() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, MpesaPayerIdentityService.genericClaimFailure());
        }
        Match match = matches.getFirst();
        String completed = MaskedMsisdn.completeWithDigits(match.phone().getMaskedMsisdn(), missingDigits);
        if (completed == null || !MaskedMsisdn.fitsMask(match.phone().getMaskedMsisdn(), completed)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, MpesaPayerIdentityService.genericClaimFailure());
        }
        return new Resolved(match.customer(), completed);
    }

    private String completedForCustomer(Customer customer, String missingDigits) {
        CustomerPhone phone = primaryPhone(customer.getId());
        if (phone == null || phone.getMaskedMsisdn() == null || !MaskedMsisdn.isMasked(phone.getMaskedMsisdn())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This customer has no masked M-Pesa number to reveal");
        }
        if (phone.getVerifiedAt() != null && phone.getPhone() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This number is already verified");
        }
        String completed = MaskedMsisdn.completeWithDigits(phone.getMaskedMsisdn(), missingDigits);
        if (completed == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Enter the " + MaskedMsisdn.missingDigitCount(phone.getMaskedMsisdn()) + " hidden digits");
        }
        return completed;
    }

    private List<Match> findMatches(String businessId, String firstName, String lastName, String lastThree) {
        String first = PayerNameNormalizer.normalize(firstName);
        String last = PayerNameNormalizer.normalize(lastName);
        if (first.isEmpty() || last.isEmpty()) {
            return List.of();
        }
        String suffixFilter = lastThree == null ? "" : lastThree.replaceAll("\\D", "");
        List<Match> out = new ArrayList<>();
        for (Customer c : customerRepository
                .findByBusinessIdAndFirstNameNormAndLastNameNormAndDeletedAtIsNull(businessId, first, last)) {
            CustomerPhone phone = primaryPhone(c.getId());
            if (phone == null || phone.getMaskedMsisdn() == null || !MaskedMsisdn.isMasked(phone.getMaskedMsisdn())) {
                continue;
            }
            if (phone.getVerifiedAt() != null) {
                continue;
            }
            String suffix = suffixOf(phone);
            if (!suffixFilter.isEmpty() && !suffix.endsWith(suffixFilter) && !suffix.equals(suffixFilter)) {
                continue;
            }
            out.add(new Match(c, phone));
        }
        return out;
    }

    private CustomerPhone primaryPhone(String customerId) {
        List<CustomerPhone> phones = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        return phones.stream().filter(CustomerPhone::isPrimary).findFirst()
                .orElse(phones.isEmpty() ? null : phones.getFirst());
    }

    private static String suffixOf(CustomerPhone phone) {
        MaskedMsisdn.Parsed parsed = MaskedMsisdn.parse(phone.getMaskedMsisdn());
        return parsed == null ? "" : parsed.suffix();
    }

    private Customer load(String businessId, String customerId) {
        return customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
    }

    private record Match(Customer customer, CustomerPhone phone) {
    }

    private record Resolved(Customer customer, String completedMsisdn) {
    }
}
