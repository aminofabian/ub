package zelisline.ub.inventory.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.RecallQuarantineRequest;
import zelisline.ub.inventory.repository.BatchSafetyRepository;
import zelisline.ub.pharmacy.application.MedicineReceivingGuard;
import zelisline.ub.purchasing.domain.InventoryBatch;

/** Pulls every on-sale batch of a manufacturer lot off sale in one action. */
@Service
@RequiredArgsConstructor
public class BatchRecallService {

    private static final int MAX_BATCHES = 500;

    private final BatchSafetyRepository batchSafetyRepository;
    private final BatchQuarantineService batchQuarantineService;

    /** @return ids of the batches that were quarantined by this call */
    @Transactional
    public List<String> quarantineLot(String businessId, RecallQuarantineRequest req, String userId) {
        String lot = MedicineReceivingGuard.normalizeLot(req.lotNumber());
        if (lot == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the lot number to recall");
        }
        String itemId = req.itemId() == null || req.itemId().isBlank() ? null : req.itemId().strip();
        List<Object[]> rows = batchSafetyRepository.findByLot(
                businessId, lot.toLowerCase(Locale.ROOT), itemId, PageRequest.of(0, MAX_BATCHES));
        List<String> done = new ArrayList<>();
        for (Object[] row : rows) {
            InventoryBatch b = (InventoryBatch) row[0];
            if (!InventoryConstants.BATCH_STATUS_ACTIVE.equals(b.getStatus())) {
                continue;
            }
            // Goes through the public method so each batch is row-locked and audited.
            batchQuarantineService.quarantine(businessId, b.getId(), "Recall of lot " + lot + ": " + req.reason(), userId);
            done.add(b.getId());
        }
        return done;
    }
}
