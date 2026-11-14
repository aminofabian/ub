package zelisline.ub.serviceops.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.serviceops.api.dto.ConsumableDtos.ConsumableInput;
import zelisline.ub.serviceops.api.dto.ConsumableDtos.ConsumableResponse;
import zelisline.ub.serviceops.api.dto.ConsumableDtos.ServiceConsumablesResponse;
import zelisline.ub.serviceops.domain.ServiceOffering;
import zelisline.ub.serviceops.repository.ServiceOfferingRepository;

/**
 * What a service uses up (dye, gloves, a blade): a list of products and quantities kept on the
 * service itself (scope §15 — no recipe table). It is used to work out what a visit costs in
 * products. It does <em>not</em> move stock: products used are still taken out of the store room
 * by hand, so a sale can never fail because a shelf count is wrong.
 */
@Service
@RequiredArgsConstructor
public class ServiceConsumablesService {

    private static final TypeReference<List<Map<String, Object>>> SHAPE = new TypeReference<>() { };

    @PersistenceContext
    private EntityManager entityManager;

    private final ServiceOfferingRepository serviceRepository;
    private final ItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ServiceConsumablesResponse get(String businessId, String serviceId) {
        return toResponse(businessId, requireService(businessId, serviceId));
    }

    /** Replaces the list. An empty list clears it. Each product must belong to this business. */
    @Transactional
    public ServiceConsumablesResponse set(String businessId, String serviceId, List<ConsumableInput> items) {
        ServiceOffering service = requireService(businessId, serviceId);
        Map<String, BigDecimal> merged = new LinkedHashMap<>();
        for (ConsumableInput input : items) {
            merged.merge(input.itemId().trim(), input.quantity(), BigDecimal::add);
        }
        if (!merged.isEmpty()) {
            List<Item> found = itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(merged.keySet(), businessId);
            if (found.size() != merged.size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One of those products was not found");
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : merged.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", entry.getKey());
            row.put("quantity", entry.getValue().setScale(4, RoundingMode.HALF_UP).toPlainString());
            rows.add(row);
        }
        try {
            service.setConsumablesJson(rows.isEmpty() ? null : objectMapper.writeValueAsString(rows));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Could not store the products used", ex);
        }
        serviceRepository.save(service);
        return toResponse(businessId, service);
    }

    /** Product cost of one visit, by service id, for every service of the business that lists any. */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> costPerVisitByService(String businessId, java.util.Collection<String> serviceIds) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (serviceIds == null || serviceIds.isEmpty()) {
            return out;
        }
        Map<String, BigDecimal> costCache = new HashMap<>();
        for (ServiceOffering service : serviceRepository.findAllById(serviceIds)) {
            if (!businessId.equals(service.getBusinessId()) || service.getConsumablesJson() == null) {
                continue;
            }
            BigDecimal total = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal> line : parse(service).entrySet()) {
                BigDecimal unit = costCache.computeIfAbsent(line.getKey(), id -> latestUnitCost(businessId, id));
                if (unit != null) {
                    total = total.add(unit.multiply(line.getValue()));
                }
            }
            out.put(service.getId(), total.setScale(2, RoundingMode.HALF_UP));
        }
        return out;
    }

    private ServiceConsumablesResponse toResponse(String businessId, ServiceOffering service) {
        Map<String, BigDecimal> lines = parse(service);
        Map<String, String> names = new HashMap<>();
        if (!lines.isEmpty()) {
            for (Item item : itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(lines.keySet(), businessId)) {
                names.put(item.getId(), item.getName());
            }
        }
        List<ConsumableResponse> out = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> line : lines.entrySet()) {
            BigDecimal unit = latestUnitCost(businessId, line.getKey());
            BigDecimal cost = unit == null ? null : unit.multiply(line.getValue()).setScale(2, RoundingMode.HALF_UP);
            if (cost != null) {
                total = total.add(cost);
            }
            out.add(new ConsumableResponse(line.getKey(), names.getOrDefault(line.getKey(), "Removed product"),
                    line.getValue(), unit, cost));
        }
        return new ServiceConsumablesResponse(service.getId(), out, total.setScale(2, RoundingMode.HALF_UP));
    }

    private Map<String, BigDecimal> parse(ServiceOffering service) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (service.getConsumablesJson() == null || service.getConsumablesJson().isBlank()) {
            return out;
        }
        try {
            for (Map<String, Object> row : objectMapper.readValue(service.getConsumablesJson(), SHAPE)) {
                Object itemId = row.get("itemId");
                Object quantity = row.get("quantity");
                if (itemId != null && quantity != null) {
                    out.put(String.valueOf(itemId), new BigDecimal(String.valueOf(quantity)));
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException | NumberFormatException ex) {
            // A list that cannot be read costs nothing rather than breaking the report.
            return new LinkedHashMap<>();
        }
        return out;
    }

    /** Unit cost at the most recent delivery of a product, in this business; null if never received. */
    private BigDecimal latestUnitCost(String businessId, String itemId) {
        List<BigDecimal> rows = entityManager.createQuery("""
                select b.unitCost from InventoryBatch b
                 where b.businessId = :businessId and b.itemId = :itemId
                 order by b.receivedAt desc, b.id desc
                """, BigDecimal.class)
                .setParameter("businessId", businessId)
                .setParameter("itemId", itemId)
                .setMaxResults(1)
                .getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ServiceOffering requireService(String businessId, String serviceId) {
        return serviceRepository.findByIdAndBusinessIdAndDeletedAtIsNull(serviceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found"));
    }
}
