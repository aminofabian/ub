package zelisline.ub.suppliers.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.application.SupplierIdentityNormalizer;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Same-business uniqueness for supplier phone (last 9 digits) and email.
 * {@code +254714282874}, {@code 0714282874}, and {@code 714282874} all collide.
 */
@Service
@RequiredArgsConstructor
public class SupplierContactUniquenessService {

    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;

    public void assertPhoneAvailable(String businessId, String rawPhone, String ignoreSupplierId) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return;
        }
        var forms = SupplierIdentityNormalizer.phoneLookupForms(rawPhone);
        if (forms == null) {
            // Let STK / format validators handle invalid numbers elsewhere.
            String stk = StkPhoneNormalizer.normalize(rawPhone);
            if (stk == null) {
                return;
            }
            forms = SupplierIdentityNormalizer.phoneLookupForms(stk);
            if (forms == null) {
                return;
            }
        }

        List<Supplier> payoutHits = supplierRepository.findOwnBusinessByPayoutPhoneVariants(
                businessId, forms.phone(), forms.altPhone(), forms.phoneTail(), ignoreSupplierId);
        if (!payoutHits.isEmpty()) {
            throw phoneConflict(payoutHits.get(0), forms.phoneTail());
        }

        List<SupplierContact> contactHits = supplierContactRepository.findOwnBusinessByPhoneVariants(
                businessId, forms.phone(), forms.altPhone(), forms.phoneTail(), ignoreSupplierId);
        if (!contactHits.isEmpty()) {
            Supplier owner = supplierRepository.findByIdAndDeletedAtIsNull(contactHits.get(0).getSupplierId())
                    .orElse(null);
            if (owner != null) {
                throw phoneConflict(owner, forms.phoneTail());
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Phone ending in " + forms.phoneTail() + " is already used by another supplier");
        }
    }

    public void assertEmailAvailable(String businessId, String rawEmail, String ignoreSupplierId) {
        String email = SupplierIdentityNormalizer.normalizeEmail(rawEmail);
        if (email == null) {
            return;
        }
        List<SupplierContact> hits = supplierContactRepository.findOwnBusinessByEmail(
                businessId, email, ignoreSupplierId);
        if (hits.isEmpty()) {
            return;
        }
        Supplier owner = supplierRepository.findByIdAndDeletedAtIsNull(hits.get(0).getSupplierId())
                .orElse(null);
        if (owner != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Email " + email + " is already on \"" + owner.getName()
                            + "\". Open that supplier instead of creating another.");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Email " + email + " is already used by another supplier");
    }

    private static ResponseStatusException phoneConflict(Supplier owner, String tail) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Phone ending in " + tail + " is already on \"" + owner.getName()
                        + "\". Open that supplier instead of creating another.");
    }
}
