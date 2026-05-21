package zelisline.ub.notifications.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.NotificationEventTypes;
import zelisline.ub.notifications.domain.NotificationBatch;
import zelisline.ub.notifications.domain.NotificationEvent;
import zelisline.ub.notifications.repository.NotificationBatchRepository;
import zelisline.ub.notifications.repository.NotificationEventRepository;

@Service
@RequiredArgsConstructor
public class StockLowBatchService {

    private final NotificationBatchRepository batchRepository;
    private final NotificationEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.notifications.stock-low.batch-window-minutes:15}")
    private int batchWindowMinutes;

    @Transactional
    public void accumulate(
            String businessId,
            String branchId,
            String itemId,
            String itemName,
            String currentStock,
            String reorderLevel
    ) {
        if (branchId == null || branchId.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        String batchKey = businessId + ":" + branchId + ":stock_low";
        Instant windowEnd = now.plus(batchWindowMinutes, ChronoUnit.MINUTES);

        NotificationBatch batch = batchRepository
                .findOpenBatch(businessId, batchKey, now)
                .orElseGet(() -> createBatch(businessId, batchKey, now, windowEnd));

        List<Map<String, String>> items = readItems(batch.getPayloadJson());
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("itemId", itemId);
        entry.put("itemName", itemName != null ? itemName : "");
        entry.put("currentStock", currentStock != null ? currentStock : "");
        entry.put("reorderLevel", reorderLevel != null ? reorderLevel : "");
        items.add(entry);
        batch.setPayloadJson(writeItems(items));
        batchRepository.save(batch);
    }

    @Transactional
    public void flushDueBatches() {
        Instant now = Instant.now();
        var due = batchRepository.findDueForFlush(now, org.springframework.data.domain.PageRequest.of(0, 20));
        for (NotificationBatch batch : due) {
            flushOne(batch);
        }
    }

    private void flushOne(NotificationBatch batch) {
        if (batch.getFlushedAt() != null) {
            return;
        }
        List<Map<String, String>> items = readItems(batch.getPayloadJson());
        if (items.isEmpty()) {
            batch.setFlushedAt(Instant.now());
            batchRepository.save(batch);
            return;
        }
        String branchId = extractBranchId(batch.getBatchKey());
        var payload = new LinkedHashMap<String, String>();
        payload.put("batchId", batch.getId());
        payload.put("branchId", branchId);
        payload.put("itemCount", String.valueOf(items.size()));
        try {
            payload.put("itemsJson", objectMapper.writeValueAsString(items));
        } catch (Exception e) {
            payload.put("itemsJson", "[]");
        }

        String dedupe = "stock_low_batch:" + batch.getBatchKey() + ":" + batch.getWindowEnd().getEpochSecond();
        if (!eventRepository.existsByBusinessIdAndDedupeKey(batch.getBusinessId(), dedupe)) {
            var event = new NotificationEvent();
            event.setBusinessId(batch.getBusinessId());
            event.setEventType(NotificationEventTypes.STOCK_LOW_BATCH_FLUSH);
            event.setAggregateType("batch");
            event.setAggregateId(batch.getId());
            event.setDedupeKey(dedupe);
            try {
                event.setPayloadJson(objectMapper.writeValueAsString(payload));
            } catch (Exception e) {
                event.setPayloadJson("{}");
            }
            eventRepository.save(event);
        }

        batch.setFlushedAt(Instant.now());
        batchRepository.save(batch);
    }

    private NotificationBatch createBatch(String businessId, String batchKey, Instant windowStart, Instant windowEnd) {
        NotificationBatch batch = new NotificationBatch();
        batch.setBusinessId(businessId);
        batch.setBatchKey(batchKey);
        batch.setWindowStart(windowStart);
        batch.setWindowEnd(windowEnd);
        batch.setPayloadJson("[]");
        return batchRepository.save(batch);
    }

    private List<Map<String, String>> readItems(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, String>> items = objectMapper.readValue(json, new TypeReference<>() {});
            return items != null ? new ArrayList<>(items) : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String writeItems(List<Map<String, String>> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String extractBranchId(String batchKey) {
        String[] parts = batchKey.split(":");
        return parts.length >= 2 ? parts[1] : "";
    }
}
