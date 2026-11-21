package zelisline.ub.pharmacy.application;

import java.time.LocalDate;
import java.util.Collection;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * A medicine may only enter stock with the manufacturer's lot number and an expiry date, on every
 * receive path a person uses (PO goods receipt, supplies drawer, opening balance, stock increase).
 * Without them a recall cannot be matched and earliest-expiry-first cannot work.
 */
@Service
@RequiredArgsConstructor
public class MedicineReceivingGuard {

    public static final int LOT_MAX_LENGTH = 64;

    /** Where a person can enter both today; some stock screens have no field for them yet. */
    private static final String WHERE =
            "Use Add stock under Pharmacy, Medicines, or receive it under Supplies using Lot & expiry.";

    private final MedicineRegistry medicineRegistry;

    /**
     * @param candidateItemIds every id that stands for the product (received item, parent, stock holder)
     * @param label            product name for the message; may be null
     */
    public void requireLotAndExpiry(
            String businessId,
            Collection<String> candidateItemIds,
            String lotNumber,
            LocalDate expiryDate,
            String label
    ) {
        if (!medicineRegistry.isMedicine(businessId, candidateItemIds)) {
            return;
        }
        String name = label == null || label.isBlank() ? "This medicine" : label.strip();
        boolean noLot = normalizeLot(lotNumber) == null;
        boolean noExpiry = expiryDate == null;
        if (noLot && noExpiry) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    name + " is a medicine: enter the lot number and the expiry date printed on the pack. " + WHERE);
        }
        if (noLot) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    name + " is a medicine: enter the lot number printed on the pack. " + WHERE);
        }
        if (noExpiry) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    name + " is a medicine: enter the expiry date printed on the pack. " + WHERE);
        }
    }

    /** Trimmed lot number, or null when blank. Rejects values longer than the column. */
    public static String normalizeLot(String raw) {
        if (raw == null) {
            return null;
        }
        String lot = raw.strip();
        if (lot.isEmpty()) {
            return null;
        }
        if (lot.length() > LOT_MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Lot number is too long (max " + LOT_MAX_LENGTH + " characters)");
        }
        return lot;
    }
}
