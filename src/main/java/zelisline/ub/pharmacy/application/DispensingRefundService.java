package zelisline.ub.pharmacy.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.pharmacy.domain.DispensingRecord;
import zelisline.ub.pharmacy.domain.DispensingRecordCorrection;
import zelisline.ub.pharmacy.domain.DispensingRecordLine;
import zelisline.ub.pharmacy.repository.DispensingRecordCorrectionRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordLineRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;

/**
 * Keeps a dispensing record true after a refund (ADR 0034, Phase 0).
 *
 * <p>The line keeps what was handed over; {@code quantity_returned} gains what came back. A line
 * with anything returned stops counting towards a refill reminder or a balance owed: the supply
 * those were worked out from no longer stands, and chasing a patient for a medicine they handed
 * back is wrong. A note on the record says what was returned, so the log explains itself.
 *
 * <p>Does nothing for a sale with no dispensing record.
 */
@Service
@RequiredArgsConstructor
public class DispensingRefundService {

    private static final int NOTE_MAX = 1000;

    private final DispensingRecordRepository recordRepository;
    private final DispensingRecordLineRepository lineRepository;
    private final DispensingRecordCorrectionRepository correctionRepository;

    /**
     * @param returnedBySaleItem quantity refunded in this refund, by sale item id
     * @return how many record lines were adjusted
     */
    @Transactional
    public int applyRefund(
            String businessId, String saleId, Map<String, BigDecimal> returnedBySaleItem, String userId) {
        if (returnedBySaleItem == null || returnedBySaleItem.isEmpty()) {
            return 0;
        }
        Map<String, DispensingRecord> records = new LinkedHashMap<>();
        for (DispensingRecord r : recordRepository.findByBusinessIdAndSaleId(businessId, saleId)) {
            if (r.getVoidedAt() == null) {
                records.put(r.getId(), r);
            }
        }
        if (records.isEmpty()) {
            return 0;
        }
        Map<String, List<String>> notes = new LinkedHashMap<>();
        int adjusted = 0;
        for (DispensingRecordLine line : lineRepository
                .findByBusinessIdAndRecordIdInOrderByCreatedAtAscIdAsc(businessId, records.keySet())) {
            BigDecimal returned = returnedOn(line, returnedBySaleItem);
            if (returned.signum() <= 0) {
                continue;
            }
            BigDecimal before = line.getQuantityReturned() == null ? BigDecimal.ZERO : line.getQuantityReturned();
            BigDecimal after = before.add(returned).min(line.getQuantity());
            line.setQuantityReturned(after);
            line.setRefillDueOn(null);
            lineRepository.save(line);
            adjusted++;
            notes.computeIfAbsent(line.getRecordId(), k -> new ArrayList<>())
                    .add(returned.stripTrailingZeros().toPlainString() + " of " + line.getItemName());
        }
        for (Map.Entry<String, List<String>> e : notes.entrySet()) {
            String text = "Refunded and returned: " + String.join("; ", e.getValue()) + ".";
            DispensingRecordCorrection c = new DispensingRecordCorrection();
            c.setBusinessId(businessId);
            c.setRecordId(e.getKey());
            c.setNote(text.length() > NOTE_MAX ? text.substring(0, NOTE_MAX) : text);
            c.setCreatedBy(userId);
            correctionRepository.save(c);
        }
        return adjusted;
    }

    private static BigDecimal returnedOn(DispensingRecordLine line, Map<String, BigDecimal> returnedBySaleItem) {
        String ids = line.getSaleItemIds();
        if (ids == null || ids.isBlank()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (String id : ids.split(",")) {
            BigDecimal q = returnedBySaleItem.get(id.strip());
            if (q != null && q.signum() > 0) {
                total = total.add(q);
            }
        }
        return total;
    }
}
