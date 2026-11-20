package zelisline.ub.pharmacy.application;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;

/**
 * Whether a shop may run the desktop till (ADR 0033, decision B5).
 *
 * <p>The desktop till sells from its own database and uploads the sale afterwards, so none of the
 * medicine rules (dispensing gate, strict lot picking, quarantine) run before the product leaves
 * the counter. Until the desktop till carries those rules, a shop that has marked any product as
 * a medicine cannot download its catalogue onto one. Sales a till already made still upload: a
 * sale that happened is never thrown away.
 *
 * <p>The test is "has marked medicines", not the shop format, because a format only picks
 * defaults and wording (see {@code StoreTypeCodes}).
 */
@Service
@RequiredArgsConstructor
public class DesktopTillPolicy {

    /** Stable marker the desktop app looks for in the error text. */
    public static final String REFUSAL_CODE = "DESKTOP_TILL_NOT_FOR_MEDICINES";

    public static final String REFUSAL_MESSAGE =
            "This shop sells medicines, so it uses the online till, where prescription and "
                    + "pharmacist checks apply. The desktop till is not available for it yet.";

    private final ItemMedicineDetailsRepository medicineDetailsRepository;

    @Transactional(readOnly = true)
    public boolean desktopTillAllowed(String businessId) {
        return !medicineDetailsRepository.existsByBusinessId(businessId);
    }

    /** 409 with {@link #REFUSAL_CODE} when the shop may not run a desktop till. */
    @Transactional(readOnly = true)
    public void assertDesktopTillAllowed(String businessId) {
        if (!desktopTillAllowed(businessId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, REFUSAL_CODE + ": " + REFUSAL_MESSAGE);
        }
    }
}
