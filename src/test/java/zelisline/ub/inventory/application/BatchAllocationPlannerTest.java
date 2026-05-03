package zelisline.ub.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.inventory.CostMethod;
import zelisline.ub.inventory.api.dto.BatchAllocationLine;
import zelisline.ub.purchasing.domain.InventoryBatch;

class BatchAllocationPlannerTest {

    @Test
    void fefo_takesEarlierExpiryEvenWhenReceivedLater() {
        Item item = new Item();
        item.setHasExpiry(true);
        Instant jan = Instant.parse("2026-01-01T12:00:00Z");
        List<InventoryBatch> batches = new ArrayList<>();
        batches.add(batch("later-received", jan.plusSeconds(60), LocalDate.of(2026, 8, 1), "5"));
        batches.add(batch("earlier-expiry", jan, LocalDate.of(2026, 4, 1), "5"));
        BatchAllocationPlanner.sortBatchesForPick(batches, item, CostMethod.FIFO);
        List<BatchAllocationLine> lines = BatchAllocationPlanner.allocateInOrder(batches, new BigDecimal("2"));
        assertThat(lines).hasSize(1);
        assertThat(firstBatchId(lines)).isEqualTo("earlier-expiry");
    }

    @Test
    void fifo_whenNoBatchHasExpiry() {
        Item item = new Item();
        item.setHasExpiry(true);
        Instant t0 = Instant.parse("2026-01-01T12:00:00Z");
        List<InventoryBatch> batches = new ArrayList<>();
        batches.add(batch("second", t0.plusSeconds(120), null, "5"));
        batches.add(batch("first", t0, null, "5"));
        BatchAllocationPlanner.sortBatchesForPick(batches, item, CostMethod.FIFO);
        List<BatchAllocationLine> lines = BatchAllocationPlanner.allocateInOrder(batches, new BigDecimal("1"));
        assertThat(firstBatchId(lines)).isEqualTo("first");
    }

    @Test
    void lifo_prefersNewestReceived() {
        Item item = new Item();
        item.setHasExpiry(false);
        Instant t0 = Instant.parse("2026-03-01T12:00:00Z");
        List<InventoryBatch> batches = new ArrayList<>();
        batches.add(batch("older", t0, null, "5"));
        batches.add(batch("newer", t0.plusSeconds(3600), null, "5"));
        BatchAllocationPlanner.sortBatchesForPick(batches, item, CostMethod.LIFO);
        List<BatchAllocationLine> lines = BatchAllocationPlanner.allocateInOrder(batches, new BigDecimal("2"));
        assertThat(firstBatchId(lines)).isEqualTo("newer");
    }

    @Test
    void splitAcrossTwoBatches() {
        Item item = new Item();
        item.setHasExpiry(false);
        Instant t0 = Instant.parse("2026-01-01T12:00:00Z");
        List<InventoryBatch> batches = new ArrayList<>();
        batches.add(batch("a", t0, null, "4"));
        batches.add(batch("b", t0.plusSeconds(60), null, "6"));
        BatchAllocationPlanner.sortBatchesForPick(batches, item, CostMethod.FIFO);
        List<BatchAllocationLine> lines = BatchAllocationPlanner.allocateInOrder(batches, new BigDecimal("5"));
        assertThat(lines).hasSize(2);
        assertThat(firstBatchId(lines)).isEqualTo("a");
        assertThat(lines.getFirst().quantity()).isEqualByComparingTo("4");
        assertThat(lines.get(1).quantity()).isEqualByComparingTo("1");
    }

    @Test
    void insufficientStock_throws() {
        Item item = new Item();
        item.setHasExpiry(false);
        Instant t0 = Instant.parse("2026-01-01T12:00:00Z");
        List<InventoryBatch> batches = new ArrayList<>(List.of(batch("a", t0, null, "2")));
        BatchAllocationPlanner.sortBatchesForPick(batches, item, CostMethod.FIFO);
        assertThatThrownBy(() -> BatchAllocationPlanner.allocateInOrder(batches, new BigDecimal("5")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isSameAs(HttpStatus.BAD_REQUEST));
    }

    private static String firstBatchId(List<BatchAllocationLine> lines) {
        return lines.getFirst().batchId();
    }

    private static InventoryBatch batch(String id, Instant received, LocalDate expiry, String qty) {
        InventoryBatch b = new InventoryBatch();
        b.setId(id);
        b.setBusinessId("b");
        b.setBranchId("br");
        b.setItemId("i");
        b.setSupplierId(null);
        b.setBatchNumber("bn-" + id);
        b.setSourceType("test");
        b.setSourceId("s");
        b.setInitialQuantity(new BigDecimal(qty));
        b.setQuantityRemaining(new BigDecimal(qty));
        b.setUnitCost(BigDecimal.ONE);
        b.setReceivedAt(received);
        b.setExpiryDate(expiry);
        b.setStatus("active");
        return b;
    }
}
