package zelisline.ub.catalog.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEvent;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.audit.repository.AuditEventRepository;
import zelisline.ub.catalog.api.dto.ItemTimelineEntryResponse;
import zelisline.ub.catalog.api.dto.ItemTimelineResponse;
import zelisline.ub.catalog.api.dto.RecordItemScanRequest;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.StockMovementRepository;

@Service
@RequiredArgsConstructor
public class ItemTimelineService {

    private static final Set<String> ALLOWED_SCAN_SOURCES = Set.of(
            "catalog",
            "stock_take",
            "missing_barcodes",
            "camera",
            "daily_audit"
    );

    private final ItemRepository itemRepository;
    private final AuditEventRepository auditEventRepository;
    private final StockMovementRepository stockMovementRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public ItemTimelineResponse timeline(String businessId, String itemId, int limit) {
        Item item = requireItem(businessId, itemId);
        int capped = Math.min(Math.max(limit, 1), 100);
        int fetch = Math.min(capped * 2, 120);

        Page<AuditEvent> auditPage = auditEventRepository.search(
                businessId,
                null,
                null,
                null,
                null,
                null,
                "item",
                item.getId(),
                null,
                null,
                null,
                PageRequest.of(0, fetch)
        );

        List<StockMovement> movements = stockMovementRepository.findByBusinessIdAndItemIdOrderByCreatedAtDesc(
                businessId,
                item.getId(),
                PageRequest.of(0, fetch)
        );

        List<ItemTimelineEntryResponse> merged = new ArrayList<>(auditPage.getNumberOfElements() + movements.size());
        for (AuditEvent e : auditPage.getContent()) {
            merged.add(fromAudit(e));
        }
        for (StockMovement m : movements) {
            merged.add(fromMovement(m));
        }
        merged.sort(Comparator.comparing(ItemTimelineEntryResponse::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));

        if (merged.size() > capped) {
            merged = new ArrayList<>(merged.subList(0, capped));
        }
        return new ItemTimelineResponse(item.getId(), merged);
    }

    @Transactional
    public void recordScan(String businessId, String itemId, RecordItemScanRequest body, String actorUserId) {
        Item item = requireItem(businessId, itemId);
        String source = normalizeSource(body.source());
        String barcode = blankToNull(body.barcode());
        if (barcode == null) {
            barcode = blankToNull(item.getBarcode());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", source);
        if (barcode != null) {
            metadata.put("barcode", barcode);
        }
        if (blankToNull(body.sessionId()) != null) {
            metadata.put("sessionId", body.sessionId().trim());
        }
        metadata.put("sku", item.getSku());
        metadata.put("name", item.getName());

        AuditEventActorType actorType = actorUserId != null && !actorUserId.isBlank()
                ? AuditEventActorType.USER
                : AuditEventActorType.SYSTEM;

        auditEventPublisher.publishSynchronous(auditEventBuilder
                .builder(AuditEventCategory.PRODUCTS, AuditEventTypes.ITEM_SCANNED, AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(blankToNull(body.branchId()))
                .actor(actorUserId, actorType)
                .target("item", item.getId())
                .targetLabel(item.getSku() + " — " + item.getName())
                .source(source)
                .sessionId(blankToNull(body.sessionId()))
                .metadata(metadata)
                .build());
    }

    private Item requireItem(String businessId, String itemId) {
        return itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
    }

    private static String normalizeSource(String raw) {
        String source = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCAN_SOURCES.contains(source)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "source must be one of: catalog, stock_take, missing_barcodes, camera, daily_audit"
            );
        }
        return source;
    }

    private static ItemTimelineEntryResponse fromAudit(AuditEvent e) {
        return new ItemTimelineEntryResponse(
                e.getId(),
                "audit",
                e.getEventType(),
                titleForEventType(e.getEventType()),
                summaryForAudit(e),
                e.getActorName(),
                e.getBranchId(),
                e.getSource(),
                null,
                null,
                null,
                e.getMetadata(),
                e.getCreatedAt()
        );
    }

    private static ItemTimelineEntryResponse fromMovement(StockMovement m) {
        String eventType = "stock." + (m.getMovementType() == null ? "movement" : m.getMovementType().toLowerCase(Locale.ROOT));
        return new ItemTimelineEntryResponse(
                m.getId(),
                "stock",
                eventType,
                titleForMovement(m),
                summaryForMovement(m),
                null,
                m.getBranchId(),
                null,
                m.getQuantityDelta(),
                m.getReferenceType(),
                m.getReferenceId(),
                null,
                m.getCreatedAt()
        );
    }

    private static String titleForEventType(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return "Activity";
        }
        return switch (eventType) {
            case AuditEventTypes.ITEM_CREATED -> "Product created";
            case AuditEventTypes.ITEM_UPDATED -> "Product updated";
            case AuditEventTypes.ITEM_DELETED -> "Product deleted";
            case AuditEventTypes.ITEM_SCANNED -> "Barcode scanned";
            case AuditEventTypes.ITEM_COST_ADJUSTED -> "Cost adjusted";
            case AuditEventTypes.SELLING_PRICE_CHANGED -> "Shelf price changed";
            case AuditEventTypes.BUYING_PRICE_CHANGED -> "Buying price changed";
            default -> humanize(eventType);
        };
    }

    private static String titleForMovement(StockMovement m) {
        String type = m.getMovementType() == null ? "" : m.getMovementType().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "RECEIVE", "IN" -> "Stock received";
            case "SALE", "OUT" -> "Stock sold";
            case "ADJUST", "ADJUSTMENT" -> "Stock adjusted";
            case "TRANSFER_OUT" -> "Transferred out";
            case "TRANSFER_IN" -> "Transferred in";
            case "WASTE", "WASTAGE" -> "Stock wasted";
            case "RETURN" -> "Stock returned";
            default -> type.isBlank() ? "Stock movement" : humanize(type.toLowerCase(Locale.ROOT));
        };
    }

    private static String summaryForAudit(AuditEvent e) {
        if (e.getReason() != null && !e.getReason().isBlank()) {
            return e.getReason();
        }
        if (Objects.equals(e.getEventType(), AuditEventTypes.ITEM_SCANNED) && e.getSource() != null) {
            return "Via " + humanize(e.getSource());
        }
        if (e.getDiff() != null && !e.getDiff().isBlank() && e.getDiff().length() < 180) {
            return e.getDiff();
        }
        return null;
    }

    private static String summaryForMovement(StockMovement m) {
        List<String> parts = new ArrayList<>(3);
        BigDecimal delta = m.getQuantityDelta();
        if (delta != null) {
            String sign = delta.signum() > 0 ? "+" : "";
            parts.add(sign + delta.stripTrailingZeros().toPlainString());
        }
        if (m.getReason() != null && !m.getReason().isBlank()) {
            parts.add(m.getReason());
        } else if (m.getReferenceType() != null && !m.getReferenceType().isBlank()) {
            parts.add(humanize(m.getReferenceType().toLowerCase(Locale.ROOT)));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private static String humanize(String dotted) {
        String s = dotted.replace('_', ' ').replace('.', ' ').trim();
        if (s.isEmpty()) {
            return "Activity";
        }
        String[] words = s.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) {
                sb.append(w.substring(1));
            }
        }
        return sb.toString();
    }

    private static String blankToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
