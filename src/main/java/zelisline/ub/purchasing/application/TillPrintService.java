package zelisline.ub.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.notifications.application.NotificationService;
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.platform.realtime.SessionRegistry;
import zelisline.ub.purchasing.api.dto.TillPrintDtos.DispatchTillPrintRequest;
import zelisline.ub.purchasing.api.dto.TillPrintDtos.DispatchTillPrintResponse;
import zelisline.ub.purchasing.api.dto.TillPrintDtos.TillPrintCashierResponse;
import zelisline.ub.purchasing.api.dto.TillPrintDtos.TillPrintClaimResponse;
import zelisline.ub.purchasing.api.dto.TillPrintDtos.TillPrintPendingResponse;
import zelisline.ub.purchasing.api.dto.TillPrintDtos.TillPrintSlip;
import zelisline.ub.purchasing.api.dto.TillPrintDtos.TillPrintSlipLine;
import zelisline.ub.purchasing.api.dto.TillPrintDtos.TillPrintTargetStatus;
import zelisline.ub.purchasing.domain.TillPrintJob;
import zelisline.ub.purchasing.repository.TillPrintJobRepository;

@Service
@RequiredArgsConstructor
public class TillPrintService {

    private static final Logger log = LoggerFactory.getLogger(TillPrintService.class);

    public static final String KIND_ORDER = "order";
    public static final String KIND_RECEIPT = "receipt";

    private static final Duration JOB_TTL = Duration.ofHours(3);
    private static final int MAX_RECEIPT_TILLS = 8;
    private static final int MAX_LINES = 80;
    private static final Set<String> TILL_ROLE_KEYS = Set.of("cashier", "butcher_cashier");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final TillPrintJobRepository tillPrintJobRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationService notificationService;
    private final SessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Transactional(readOnly = true)
    public List<TillPrintCashierResponse> listCashiers(String businessId, String branchId) {
        Set<String> roleIds = tillRoleIds(businessId);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        String branch = blankToNull(branchId);
        return userRepository.findByBusinessIdAndDeletedAtIsNull(businessId).stream()
                .filter(user -> UserStatus.ACTIVE.wire().equalsIgnoreCase(user.getStatus()))
                .filter(user -> roleIds.contains(user.getRoleId()))
                .filter(user -> branch == null
                        || branch.equals(user.getBranchId())
                        || user.getBranchId() == null
                        || user.getBranchId().isBlank())
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(user -> new TillPrintCashierResponse(user.getId(), user.getName()))
                .toList();
    }

    @Transactional
    public DispatchTillPrintResponse dispatch(
            String businessId,
            DispatchTillPrintRequest request
    ) {
        String kind = normalizeKind(request.kind());
        List<String> targets = distinctIds(request.targetUserIds());
        if (targets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a cashier till");
        }
        if (KIND_ORDER.equals(kind) && targets.size() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An order prints to one cashier");
        }
        if (KIND_RECEIPT.equals(kind) && targets.size() > MAX_RECEIPT_TILLS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Pick at most " + MAX_RECEIPT_TILLS + " tills");
        }

        TillPrintSlip slip = sanitizeSlip(request.slip());
        String payload = writeSlip(slip);
        String branchId = blankToNull(request.branchId());
        Map<String, String> names = new LinkedHashMap<>();
        for (TillPrintCashierResponse cashier : listCashiers(businessId, branchId)) {
            names.put(cashier.id(), cashier.name());
        }

        List<String> jobIds = new ArrayList<>();
        List<TillPrintTargetStatus> tills = new ArrayList<>();
        for (String userId : targets) {
            if (!names.containsKey(userId)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "That person is not a cashier on this branch");
            }
            TillPrintJob job = new TillPrintJob();
            job.setBusinessId(businessId);
            job.setBranchId(branchId);
            job.setTargetUserId(userId);
            job.setKind(kind);
            job.setReferenceNo(slip.reference());
            job.setPayloadJson(payload);
            tillPrintJobRepository.save(job);
            jobIds.add(job.getId());
            boolean online = !sessionRegistry.findSessionsByUser(businessId, userId).isEmpty();
            tills.add(new TillPrintTargetStatus(userId, names.get(userId), online));
            notifyTill(businessId, userId, job.getId(), kind, slip);
            eventPublisher.publishEvent(new RealtimeBridge.TillPrintRequestedEvent(
                    businessId, userId, job.getId(), kind, slip.reference(), payload));
        }
        return new DispatchTillPrintResponse(jobIds, tills);
    }

    private void notifyTill(
            String businessId,
            String userId,
            String jobId,
            String kind,
            TillPrintSlip slip
    ) {
        String title = KIND_RECEIPT.equals(kind) ? "Print this receipt" : "Print this order";
        String supplier = slip.supplierName() == null || slip.supplierName().isBlank()
                ? ""
                : " · " + slip.supplierName();
        String body = slip.reference() + supplier;
        var payload = new LinkedHashMap<String, String>();
        payload.put("title", title);
        payload.put("body", body);
        payload.put("jobId", jobId);
        payload.put("kind", kind);
        payload.put("reference", slip.reference());
        try {
            notificationService.tryInsertDedupeForUser(
                    businessId,
                    userId,
                    NotificationTypes.TILL_SLIP,
                    "till.slip:" + jobId,
                    "operational",
                    "HIGH",
                    objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("Could not notify till user {} for job {}: {}", userId, jobId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<TillPrintPendingResponse> listPending(String businessId, String userId) {
        return tillPrintJobRepository.findPending(businessId, userId, cutoff()).stream()
                .map(job -> new TillPrintPendingResponse(
                        job.getId(),
                        job.getKind(),
                        job.getReferenceNo(),
                        job.getCreatedAt(),
                        readSlip(job.getPayloadJson())))
                .toList();
    }

    @Transactional
    public TillPrintClaimResponse claim(String businessId, String userId, String jobId) {
        String id = jobId == null ? "" : jobId.trim();
        if (id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Print job not found");
        }
        int updated = tillPrintJobRepository.claimIfOpen(id, businessId, userId, Instant.now(), cutoff());
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Print job not found");
        }
        TillPrintJob job = tillPrintJobRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Print job not found"));
        return new TillPrintClaimResponse(job.getId(), job.getKind(), readSlip(job.getPayloadJson()));
    }

    private Set<String> tillRoleIds(String businessId) {
        Set<String> ids = new LinkedHashSet<>();
        for (Role role : roleRepository.findVisibleForTenant(businessId)) {
            if (role.getRoleKey() != null && TILL_ROLE_KEYS.contains(role.getRoleKey().trim().toLowerCase())) {
                ids.add(role.getId());
            }
        }
        return ids;
    }

    private static String normalizeKind(String kind) {
        String value = kind == null ? "" : kind.trim().toLowerCase();
        if (KIND_ORDER.equals(value) || KIND_RECEIPT.equals(value)) {
            return value;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Print kind must be order or receipt");
    }

    private static List<String> distinctIds(List<String> raw) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (raw == null) {
            return List.of();
        }
        for (String id : raw) {
            if (id == null) {
                continue;
            }
            String trimmed = id.trim();
            if (!trimmed.isBlank()) {
                ids.add(trimmed);
            }
        }
        return List.copyOf(ids);
    }

    private static TillPrintSlip sanitizeSlip(TillPrintSlip slip) {
        String reference = clip(slip.reference(), 80);
        if (reference.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Print slip needs a reference");
        }
        List<TillPrintSlipLine> lines = new ArrayList<>();
        for (TillPrintSlipLine line : slip.lines()) {
            if (line == null || lines.size() >= MAX_LINES) {
                continue;
            }
            String name = clip(line.name(), 160);
            BigDecimal qty = money(line.qty());
            if (name.isBlank() || qty.signum() <= 0) {
                continue;
            }
            BigDecimal unit = money(line.unitCost());
            BigDecimal total = line.lineTotal() == null ? unit.multiply(qty) : money(line.lineTotal());
            lines.add(new TillPrintSlipLine(name, qty, unit, total));
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Print slip has no lines");
        }
        String currency = clip(slip.currency(), 8);
        if (currency.isBlank()) {
            currency = "KES";
        }
        return new TillPrintSlip(
                reference,
                clip(slip.supplierName(), 120),
                clip(slip.businessName(), 120),
                clip(slip.branchName(), 120),
                clip(slip.placedByName(), 120),
                currency.toUpperCase(),
                lines);
    }

    private String writeSlip(TillPrintSlip slip) {
        try {
            return objectMapper.writeValueAsString(slip);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not store the print slip");
        }
    }

    private TillPrintSlip readSlip(String json) {
        try {
            return objectMapper.readValue(json, TillPrintSlip.class);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Stored print slip is unreadable");
        }
    }

    private static Instant cutoff() {
        return Instant.now().minus(JOB_TTL);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max);
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }
}
