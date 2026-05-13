package zelisline.ub.sync.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.sync.domain.SyncConflict;
import zelisline.ub.sync.repository.SyncConflictRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class SyncConflictService {

    private static final Logger log = LoggerFactory.getLogger(SyncConflictService.class);

    private final SyncConflictRepository syncConflictRepository;
    private final ItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void recordConflict(
            String businessId,
            String entityType,
            String entityId,
            String userId,
            LocalDateTime localVersion,
            LocalDateTime serverVersion,
            String localSnapshot,
            String serverSnapshot
    ) {
        SyncConflict conflict = new SyncConflict();
        conflict.setBusinessId(businessId);
        conflict.setEntityType(entityType);
        conflict.setEntityId(entityId);
        conflict.setLocalVersion(localVersion.atOffset(ZoneOffset.UTC).toInstant());
        conflict.setServerVersion(serverVersion.atOffset(ZoneOffset.UTC).toInstant());
        conflict.setResolution("pending");
        conflict.setLocalSnapshot(localSnapshot);
        conflict.setServerSnapshot(serverSnapshot);
        conflict.setCreatedBy(userId);

        syncConflictRepository.save(conflict);
        log.info("Recorded sync conflict for entityType={} entityId={} businessId={}",
                entityType, entityId, businessId);
    }

    @Transactional(readOnly = true)
    public Page<SyncConflict> listPending(String businessId, Pageable pageable) {
        return syncConflictRepository.findByBusinessIdAndResolutionOrderByCreatedAtDesc(
                businessId, "pending", pageable);
    }

    @Transactional(readOnly = true)
    public long countPending(String businessId) {
        return syncConflictRepository.countByBusinessIdAndResolution(businessId, "pending");
    }

    @Transactional
    public void resolveServerWins(String businessId, String conflictId, String userId) {
        SyncConflict conflict = syncConflictRepository.findById(conflictId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conflict not found"));

        if (!conflict.getBusinessId().equals(businessId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Conflict does not belong to this business");
        }

        if (!"pending".equals(conflict.getResolution())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conflict is already resolved");
        }

        conflict.setResolution("server_wins");
        conflict.setResolvedAt(Instant.now());
        conflict.setResolvedBy(userId);
        conflict.setNotes("Resolved in favour of server version.");

        syncConflictRepository.save(conflict);
        log.info("Resolved sync conflict {} as server_wins", conflictId);
    }

    @Transactional
    public void resolveLocalWinsByConflictId(
            String businessId,
            String conflictId,
            String userId,
            String resolvedSnapshot
    ) {
        SyncConflict conflict = syncConflictRepository.findById(conflictId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conflict not found"));

        if (!conflict.getBusinessId().equals(businessId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Conflict does not belong to this business");
        }

        if (!"pending".equals(conflict.getResolution())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conflict is already resolved");
        }

        String entityType = conflict.getEntityType();
        String entityId = conflict.getEntityId();

        // Apply the resolved snapshot back to the entity
        applyResolvedSnapshot(businessId, entityType, entityId, resolvedSnapshot);

        conflict.setResolution("local_wins");
        conflict.setResolvedAt(Instant.now());
        conflict.setResolvedBy(userId);
        conflict.setNotes("Resolved in favour of client version. Snapshot applied.");

        syncConflictRepository.save(conflict);
        log.info("Resolved sync conflict {} as local_wins for entityType={} entityId={}",
                conflictId, entityType, entityId);
    }

    private void applyResolvedSnapshot(
            String businessId,
            String entityType,
            String entityId,
            String resolvedSnapshot
    ) {
        if ("item".equals(entityType)) {
            applyItemSnapshot(businessId, entityId, resolvedSnapshot);
        } else {
            throw new UnsupportedOperationException(
                    "Local-wins resolution not yet supported for entity type: " + entityType);
        }
    }

    private void applyItemSnapshot(String businessId, String itemId, String resolvedSnapshot) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));

        try {
            ItemSnapshot snapshot = objectMapper.readValue(resolvedSnapshot, ItemSnapshot.class);

            if (snapshot.name() != null && !snapshot.name().isBlank()) {
                item.setName(snapshot.name().trim());
            }
            if (snapshot.sku() != null) {
                String sku = snapshot.sku().trim();
                if (!sku.isEmpty() && !sku.equals(item.getSku())) {
                    if (itemRepository.existsByBusinessIdAndSkuAndDeletedAtIsNull(businessId, sku)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU already in use");
                    }
                    item.setSku(sku);
                }
            }
            if (snapshot.barcode() != null) {
                item.setBarcode(blankToNull(snapshot.barcode()));
            }
            if (snapshot.description() != null) {
                item.setDescription(blankToNull(snapshot.description()));
            }
            if (snapshot.brand() != null) {
                item.setBrand(blankToNull(snapshot.brand()));
            }
            if (snapshot.size() != null) {
                item.setSize(blankToNull(snapshot.size()));
            }

            itemRepository.save(item);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Failed to parse resolved snapshot: " + e.getMessage());
        }
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Lightweight snapshot of fields that can be resolved for an item entity.
     */
    private record ItemSnapshot(
            String name,
            String sku,
            String barcode,
            String description,
            String brand,
            String size
    ) {
    }
}
