package zelisline.ub.restaurant.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.posdraft.domain.PosDraftAuditLog;
import zelisline.ub.posdraft.repository.PosDraftAuditLogRepository;
import zelisline.ub.restaurant.api.dto.OrderDtos.OrderActivityResponse;

/**
 * What happened to an order, in words a manager can read back: a dish cancelled after it went to
 * the kitchen, a bill comped, an order moved, merged or cancelled, a table handed to another waiter.
 *
 * <p>The restaurant already kept the <em>reason</em> on a cancelled line or a closed bill, but not
 * who moved what and when. Those are the actions a restaurant argues about at the end of the night,
 * so each one also writes a row here (the same table the held-cart audit uses).
 */
@Service
@RequiredArgsConstructor
public class OrderActivityService {

    static final String LINE_CANCELLED = "ORDER_LINE_CANCELLED";
    static final String ORDER_MOVED = "ORDER_MOVED";
    static final String ORDER_MERGED = "ORDER_MERGED";
    static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    static final String SERVED_BY_CHANGED = "ORDER_SERVED_BY_CHANGED";
    static final String BILL_COMP = "BILL_COMP";
    static final String BILL_UNPAID = "BILL_UNPAID";

    private static final int MAX_DETAIL = 500;

    private final PosDraftAuditLogRepository auditLogRepository;
    private final StaffProfileService staffProfileService;

    /** Notes who did what to an order. Never throws for a missing detail; the row is still worth having. */
    @Transactional
    public void record(String orderId, String userId, String action, String lineId, String detail) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        PosDraftAuditLog row = new PosDraftAuditLog();
        row.setDraftId(orderId);
        row.setUserId(userId == null || userId.isBlank() ? "system" : userId);
        row.setAction(action);
        row.setLineId(lineId);
        row.setDetail(trim(detail));
        auditLogRepository.save(row);
    }

    /**
     * The latest 50 entries for an order, newest first.
     *
     * @param businessId the caller's business; the order is already known to belong to it, because the
     *                   caller loads the order through its business-scoped service before asking
     */
    @Transactional(readOnly = true)
    public List<OrderActivityResponse> forOrder(String businessId, String orderId) {
        List<PosDraftAuditLog> rows = auditLogRepository.findTop50ByDraftIdOrderByCreatedAtDesc(orderId);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> userIds = rows.stream().map(PosDraftAuditLog::getUserId).filter(u -> u != null && !u.isBlank())
                .distinct().toList();
        // The rows carry the signed-in user's id; a display name lives on their staff profile.
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> profileIdByUser = new LinkedHashMap<>();
        for (String userId : userIds) {
            staffProfileService.findByUser(businessId, userId)
                    .ifPresent(profile -> profileIdByUser.put(userId, profile.getId()));
        }
        if (!profileIdByUser.isEmpty()) {
            Map<String, String> byProfile =
                    staffProfileService.displayNamesByIds(businessId, profileIdByUser.values());
            profileIdByUser.forEach((userId, profileId) -> names.put(userId, byProfile.get(profileId)));
        }
        List<OrderActivityResponse> out = new ArrayList<>();
        for (PosDraftAuditLog row : rows) {
            String name = names.get(row.getUserId());
            out.add(new OrderActivityResponse(row.getId(), row.getCreatedAt(), row.getAction(), row.getLineId(),
                    row.getUserId(), name == null || name.isBlank() ? null : name, row.getDetail()));
        }
        return out;
    }

    /** A dish's name and quantity, as it should read in a history line. */
    static String lineWords(String name, java.math.BigDecimal quantity) {
        if (name == null) {
            return "A dish";
        }
        String qty = quantity == null ? "" : quantity.stripTrailingZeros().toPlainString() + " × ";
        return qty + name;
    }

    private static String trim(String detail) {
        if (detail == null) {
            return null;
        }
        String oneLine = detail.replaceAll("[\\p{Cc}\\p{Cf}\\u2028\\u2029]+", " ").replaceAll(" {2,}", " ").trim();
        if (oneLine.isEmpty()) {
            return null;
        }
        return oneLine.length() > MAX_DETAIL ? oneLine.substring(0, MAX_DETAIL) : oneLine;
    }
}
