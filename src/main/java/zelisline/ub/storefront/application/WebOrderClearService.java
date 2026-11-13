package zelisline.ub.storefront.application;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.storefront.api.dto.WebOrderClearResponse;
import zelisline.ub.storefront.repository.WebOrderRepository;

/**
 * Voids every open web order for one tenant, one order at a time, so each void
 * keeps the stock-release transaction it already has. A failure on one order
 * does not stop the rest.
 */
@Service
@RequiredArgsConstructor
public class WebOrderClearService {

    private static final Logger log = LoggerFactory.getLogger(WebOrderClearService.class);

    /** Each pass re-reads the first page; voided rows drop out of the query. */
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PASSES = 5;

    private final WebOrderRepository webOrderRepository;
    private final WebOrderFulfillmentService webOrderFulfillmentService;

    public WebOrderClearResponse clearOpenOrders(String businessId, String branchId) {
        String branch = branchId == null ? null : branchId.trim();
        if (branch != null && branch.isEmpty()) {
            branch = null;
        }
        int voided = 0;
        int failed = 0;
        Set<String> failedIds = new HashSet<>();
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            List<String> ids = load(businessId, branch).stream()
                    .filter(id -> !failedIds.contains(id))
                    .toList();
            if (ids.isEmpty()) {
                return new WebOrderClearResponse(voided, failed, false);
            }
            for (String id : ids) {
                try {
                    webOrderFulfillmentService.voidOrder(businessId, id);
                    voided++;
                } catch (RuntimeException ex) {
                    failed++;
                    failedIds.add(id);
                    log.warn("web order clear failed for {}: {}", id, ex.getMessage());
                }
            }
        }
        boolean moreRemaining = load(businessId, branch).stream()
                .anyMatch(id -> !failedIds.contains(id));
        return new WebOrderClearResponse(voided, failed, moreRemaining);
    }

    private List<String> load(String businessId, String branch) {
        Pageable page = PageRequest.of(0, PAGE_SIZE);
        List<String> ids = branch == null
                ? webOrderRepository.findVoidableIds(businessId, page)
                : webOrderRepository.findVoidableIdsByBranch(businessId, branch, page);
        return ids == null ? List.of() : ids;
    }
}
