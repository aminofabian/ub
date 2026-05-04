package zelisline.ub.integrations.privacy.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.integrations.privacy.api.dto.PrivacyExportCreateRequest;
import zelisline.ub.integrations.privacy.api.dto.PrivacyExportDownload;
import zelisline.ub.integrations.privacy.api.dto.PrivacyExportJobResponse;
import zelisline.ub.integrations.privacy.domain.PrivacyExportJob;
import zelisline.ub.integrations.privacy.repository.PrivacyExportJobRepository;
import zelisline.ub.notifications.domain.Notification;
import zelisline.ub.notifications.repository.NotificationRepository;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.repository.SaleRepository;

/**
 * Builds a structured ZIP (manifest + profile + sales_refs + credits/messages folders) for GDPR/DPA access.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrivacyExportService {

    public static final String SUBJECT_CUSTOMER = "customer";
    public static final String SUBJECT_USER = "user";

    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";
    private static final Duration DOWNLOAD_TTL = Duration.ofHours(1);

    private final PrivacyExportJobRepository jobRepository;
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final SaleRepository saleRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public PrivacyExportJobResponse create(String businessId, String actorUserId, PrivacyExportCreateRequest req) {
        String type = req.subjectType().trim().toLowerCase(Locale.ROOT);
        String subjectId = req.subjectId().trim();
        if (!SUBJECT_CUSTOMER.equals(type) && !SUBJECT_USER.equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subjectType must be customer or user");
        }
        validateSubjectExists(businessId, type, subjectId);

        PrivacyExportJob job = new PrivacyExportJob();
        job.setBusinessId(businessId);
        job.setSubjectType(type);
        job.setSubjectId(subjectId);
        job.setStatus(STATUS_PROCESSING);
        job.setCreatedBy(actorUserId);
        jobRepository.save(job);

        try {
            byte[] zipBytes = buildZipBytes(businessId, type, subjectId);
            java.nio.file.Path dir =
                    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "ub-privacy-exports", businessId);
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path path = dir.resolve(job.getId() + ".zip");
            java.nio.file.Files.write(path, zipBytes);
            job.setStoragePath(path.toAbsolutePath().toString());
            job.setDownloadToken(UUID.randomUUID().toString());
            job.setExpiresAt(Instant.now().plus(DOWNLOAD_TTL));
            job.setStatus(STATUS_COMPLETED);
        } catch (Exception ex) {
            log.warn("Privacy export failed jobId={} businessId={}", job.getId(), businessId, ex);
            job.setStatus(STATUS_FAILED);
            job.setErrorMessage(truncate(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
        jobRepository.save(job);
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public PrivacyExportJobResponse getMetadata(String businessId, String jobId) {
        PrivacyExportJob job = jobRepository
                .findByIdAndBusinessId(jobId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Export not found"));
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public PrivacyExportDownload openDownload(String businessId, String jobId, String token) {
        PrivacyExportJob job = jobRepository
                .findByIdAndBusinessId(jobId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Export not found"));
        if (!STATUS_COMPLETED.equals(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Export is not ready");
        }
        if (job.getDownloadToken() == null || !job.getDownloadToken().equals(token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid download token");
        }
        if (job.getExpiresAt() == null || Instant.now().isAfter(job.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Download link expired");
        }
        java.nio.file.Path path = java.nio.file.Paths.get(job.getStoragePath());
        if (!java.nio.file.Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Export file missing");
        }
        String filename = "privacy-export-" + jobId + ".zip";
        return new PrivacyExportDownload(
                new FileSystemResource(path.toFile()), MediaType.parseMediaType("application/zip"), filename);
    }

    private void validateSubjectExists(String businessId, String type, String subjectId) {
        if (SUBJECT_CUSTOMER.equals(type)) {
            customerRepository
                    .findByIdAndBusinessIdAndDeletedAtIsNull(subjectId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
            return;
        }
        userRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(subjectId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private byte[] buildZipBytes(String businessId, String type, String subjectId) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ZipOutputStream zos = new ZipOutputStream(bos)) {

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schema", "ub-privacy-export-v1");
            manifest.put("businessId", businessId);
            manifest.put("subjectType", type);
            manifest.put("subjectId", subjectId);
            manifest.put("generatedAt", Instant.now().toString());
            zipEntry(zos, "manifest.json", objectMapper.writeValueAsBytes(manifest));

            if (SUBJECT_CUSTOMER.equals(type)) {
                writeCustomerPayload(zos, businessId, subjectId);
            } else {
                writeUserPayload(zos, businessId, subjectId);
            }
            zos.finish();
            return bos.toByteArray();
        }
    }

    private void writeCustomerPayload(ZipOutputStream zos, String businessId, String customerId) throws IOException {
        Customer c = customerRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(customerId, businessId)
                .orElseThrow();
        List<CustomerPhone> phones = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", c.getId());
        profile.put("name", c.getName());
        profile.put("email", c.getEmail());
        profile.put("notes", c.getNotes());
        profile.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
        profile.put("updatedAt", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null);
        profile.put("deletedAt", c.getDeletedAt() != null ? c.getDeletedAt().toString() : null);
        profile.put("anonymisedAt", c.getAnonymisedAt() != null ? c.getAnonymisedAt().toString() : null);
        profile.put("phones", phones.stream().map(this::phoneMap).toList());
        zipEntry(zos, "profile/customer.json", objectMapper.writeValueAsBytes(profile));

        List<Sale> sales = saleRepository.findByBusinessIdAndCustomerIdOrderBySoldAtDesc(businessId, customerId);
        List<Map<String, Object>> saleRows = new ArrayList<>();
        for (Sale s : sales) {
            saleRows.add(saleRefMap(s));
        }
        zipEntry(zos, "sales_refs/sales.json", objectMapper.writeValueAsBytes(saleRows));

        CreditAccount acc =
                creditAccountRepository.findByCustomerIdAndBusinessId(customerId, businessId).orElse(null);
        Map<String, Object> credit = new LinkedHashMap<>();
        if (acc != null) {
            credit.put("creditAccountId", acc.getId());
            credit.put("balanceOwed", acc.getBalanceOwed() != null ? acc.getBalanceOwed().toPlainString() : null);
            credit.put("walletBalance", acc.getWalletBalance() != null ? acc.getWalletBalance().toPlainString() : null);
            credit.put("loyaltyPoints", acc.getLoyaltyPoints());
            credit.put("creditLimit", acc.getCreditLimit() != null ? acc.getCreditLimit().toPlainString() : null);
            credit.put("remindersOptOut", acc.isRemindersOptOut());
        }
        zipEntry(zos, "credits/credit_account.json", objectMapper.writeValueAsBytes(credit));

        Map<String, Object> messages = new LinkedHashMap<>();
        messages.put(
                "note",
                "In-app notifications are user-scoped; retail customers do not have an inbox in this product version.");
        messages.put("notifications", List.of());
        zipEntry(zos, "messages/notifications.json", objectMapper.writeValueAsBytes(messages));
    }

    private void writeUserPayload(ZipOutputStream zos, String businessId, String userId) throws IOException {
        User u = userRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .orElseThrow();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", u.getId());
        profile.put("name", u.getName());
        profile.put("email", u.getEmail());
        profile.put("phone", u.getPhone());
        profile.put("status", u.getStatus());
        profile.put("roleId", u.getRoleId());
        profile.put("branchId", u.getBranchId());
        profile.put("hasPassword", u.getPasswordHash() != null);
        profile.put("hasPin", u.getPinHash() != null);
        profile.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : null);
        profile.put("updatedAt", u.getUpdatedAt() != null ? u.getUpdatedAt().toString() : null);
        profile.put("deletedAt", u.getDeletedAt() != null ? u.getDeletedAt().toString() : null);
        profile.put("anonymisedAt", u.getAnonymisedAt() != null ? u.getAnonymisedAt().toString() : null);
        zipEntry(zos, "profile/user.json", objectMapper.writeValueAsBytes(profile));

        List<Sale> sales = saleRepository.findByBusinessIdAndSoldByOrderBySoldAtDesc(businessId, userId);
        List<Map<String, Object>> saleRows = new ArrayList<>();
        for (Sale s : sales) {
            saleRows.add(saleRefMap(s));
        }
        zipEntry(zos, "sales_refs/sales.json", objectMapper.writeValueAsBytes(saleRows));

        List<Notification> notes =
                notificationRepository.findByBusinessIdAndUserIdOrderByCreatedAtDesc(businessId, userId);
        List<Map<String, Object>> inbox = new ArrayList<>();
        for (Notification n : notes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", n.getId());
            row.put("type", n.getType());
            row.put("readAt", n.getReadAt() != null ? n.getReadAt().toString() : null);
            row.put("createdAt", n.getCreatedAt() != null ? n.getCreatedAt().toString() : null);
            row.put("payloadJson", n.getPayloadJson());
            inbox.add(row);
        }
        zipEntry(zos, "messages/notifications.json", objectMapper.writeValueAsBytes(inbox));
    }

    private Map<String, Object> phoneMap(CustomerPhone p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("phone", p.getPhone());
        m.put("primary", p.isPrimary());
        return m;
    }

    private Map<String, Object> saleRefMap(Sale s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("saleId", s.getId());
        m.put("soldAt", s.getSoldAt() != null ? s.getSoldAt().toString() : null);
        m.put("grandTotal", s.getGrandTotal() != null ? s.getGrandTotal().toPlainString() : null);
        m.put("status", s.getStatus());
        m.put("branchId", s.getBranchId());
        m.put("customerId", s.getCustomerId());
        m.put("voidedAt", s.getVoidedAt() != null ? s.getVoidedAt().toString() : null);
        m.put("refundedTotal", s.getRefundedTotal() != null ? s.getRefundedTotal().toPlainString() : null);
        return m;
    }

    private static void zipEntry(ZipOutputStream zos, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(System.currentTimeMillis());
        zos.putNextEntry(entry);
        zos.write(bytes);
        zos.closeEntry();
    }

    private PrivacyExportJobResponse toResponse(PrivacyExportJob job) {
        String url = null;
        if (STATUS_COMPLETED.equals(job.getStatus()) && job.getDownloadToken() != null) {
            url = "/api/v1/integrations/privacy/exports/" + job.getId() + "/download?token=" + job.getDownloadToken();
        }
        return new PrivacyExportJobResponse(
                job.getId(),
                job.getStatus(),
                job.getSubjectType(),
                job.getSubjectId(),
                url,
                job.getExpiresAt(),
                job.getErrorMessage());
    }

    private static String truncate(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.length() <= 2000) {
            return raw;
        }
        return raw.substring(0, 1997) + "...";
    }
}
