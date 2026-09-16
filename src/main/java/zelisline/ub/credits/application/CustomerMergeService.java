package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.credits.api.dto.CustomerResponse;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;

/**
 * Fuse duplicate credit customers into one keep record — balances, phones, and
 * sales move over; absorbed rows are soft-deleted.
 */
@Service
@RequiredArgsConstructor
public class CustomerMergeService {

    private static final int MAX_ABSORB = 10;

    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final CustomerDirectoryService customerDirectoryService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public CustomerResponse merge(
            String businessId,
            String keepIdRaw,
            List<String> absorbIdsRaw,
            String actorUserId
    ) {
        if (keepIdRaw == null || keepIdRaw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keepId is required");
        }
        if (absorbIdsRaw == null || absorbIdsRaw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one account to merge in");
        }

        String keepId = keepIdRaw.trim();
        Set<String> absorbSet = new LinkedHashSet<>();
        for (String raw : absorbIdsRaw) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String id = raw.trim();
            if (id.equals(keepId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot merge a customer into itself");
            }
            absorbSet.add(id);
        }
        if (absorbSet.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select at least one account to merge in");
        }
        if (absorbSet.size() > MAX_ABSORB) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Merge at most " + MAX_ABSORB + " accounts at once");
        }

        Customer keep = loadActive(businessId, keepId);
        List<Customer> absorbs = new ArrayList<>();
        for (String absorbId : absorbSet) {
            absorbs.add(loadActive(businessId, absorbId));
        }

        int salesMoved = 0;
        int phonesMoved = 0;
        BigDecimal owedAdded = BigDecimal.ZERO;
        BigDecimal walletAdded = BigDecimal.ZERO;
        int loyaltyAdded = 0;

        CreditAccount keepAcc = creditAccountRepository
                .findByCustomerIdAndBusinessIdForUpdate(keepId, businessId)
                .orElseGet(() -> ensureCreditAccount(businessId, keepId));

        for (Customer absorb : absorbs) {
            String absorbId = absorb.getId();
            phonesMoved += movePhones(keepId, absorbId);
            entityManager.flush();

            CreditAccount absorbAcc = creditAccountRepository
                    .findByCustomerIdAndBusinessIdForUpdate(absorbId, businessId)
                    .orElse(null);
            if (absorbAcc != null) {
                owedAdded = owedAdded.add(nz(absorbAcc.getBalanceOwed()));
                walletAdded = walletAdded.add(nz(absorbAcc.getWalletBalance()));
                loyaltyAdded += Math.max(0, absorbAcc.getLoyaltyPoints());
                foldCreditAccount(keepAcc, absorbAcc);
            }

            salesMoved += nativeUpdate(
                    "UPDATE sales SET customer_id = ?1 WHERE business_id = ?2 AND customer_id = ?3",
                    keepId, businessId, absorbId);

            mergeProfile(keep, absorb);
            absorb.setMpesaIdentityKey(null);
            absorb.setDeletedAt(Instant.now());
            customerRepository.save(absorb);
        }

        creditAccountRepository.save(keepAcc);
        customerRepository.save(keep);
        entityManager.flush();

        auditEventPublisher.publish(auditEventBuilder.builder(
                        AuditEventCategory.CUSTOMERS,
                        AuditEventTypes.CUSTOMER_MERGED,
                        AuditEventSeverity.INFO)
                .businessId(businessId)
                .actor(
                        actorUserId,
                        actorUserId != null && !actorUserId.isBlank()
                                ? AuditEventActorType.USER
                                : AuditEventActorType.SYSTEM)
                .target("customer", keep.getId())
                .targetLabel(keep.getName())
                .source("web_admin")
                .diff(Map.of(
                        "keepId", keepId,
                        "absorbIds", List.copyOf(absorbSet),
                        "salesMoved", salesMoved,
                        "phonesMoved", phonesMoved,
                        "balanceOwedAdded", owedAdded.toPlainString(),
                        "walletAdded", walletAdded.toPlainString(),
                        "loyaltyAdded", loyaltyAdded))
                .build());

        return customerDirectoryService.get(businessId, keepId);
    }

    private Customer loadActive(String businessId, String customerId) {
        return customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Customer not found: " + customerId));
    }

    private CreditAccount ensureCreditAccount(String businessId, String customerId) {
        CreditAccount acc = new CreditAccount();
        acc.setBusinessId(businessId);
        acc.setCustomerId(customerId);
        return creditAccountRepository.saveAndFlush(acc);
    }

    private int movePhones(String keepId, String absorbId) {
        List<CustomerPhone> keepPhones = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(keepId);
        Set<String> keepNumbers = new LinkedHashSet<>();
        boolean keepHasPrimary = false;
        for (CustomerPhone p : keepPhones) {
            keepNumbers.add(p.getPhone());
            if (p.isPrimary()) {
                keepHasPrimary = true;
            }
        }

        int moved = 0;
        List<CustomerPhone> absorbPhones =
                customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(absorbId);
        for (CustomerPhone phone : absorbPhones) {
            if (keepNumbers.contains(phone.getPhone())) {
                customerPhoneRepository.delete(phone);
                continue;
            }
            phone.setCustomerId(keepId);
            if (keepHasPrimary) {
                phone.setPrimary(false);
            } else if (phone.isPrimary()) {
                keepHasPrimary = true;
            }
            customerPhoneRepository.save(phone);
            keepNumbers.add(phone.getPhone());
            moved++;
        }
        return moved;
    }

    private void foldCreditAccount(CreditAccount keep, CreditAccount absorb) {
        String absorbAccountId = absorb.getId();
        String keepAccountId = keep.getId();

        // Drop absorb reminder markers — unique on (account, week); keep's stay.
        nativeUpdate(
                "DELETE FROM credit_reminders WHERE credit_account_id = ?1",
                absorbAccountId);

        nativeUpdate(
                "UPDATE credit_transactions SET credit_account_id = ?1 WHERE credit_account_id = ?2",
                keepAccountId, absorbAccountId);
        nativeUpdate(
                "UPDATE wallet_transactions SET credit_account_id = ?1 WHERE credit_account_id = ?2",
                keepAccountId, absorbAccountId);
        nativeUpdate(
                "UPDATE loyalty_transactions SET credit_account_id = ?1 WHERE credit_account_id = ?2",
                keepAccountId, absorbAccountId);
        nativeUpdate(
                "UPDATE public_payment_claims SET credit_account_id = ?1 WHERE credit_account_id = ?2",
                keepAccountId, absorbAccountId);
        nativeUpdate(
                "UPDATE mpesa_stk_intents SET credit_account_id = ?1 WHERE credit_account_id = ?2",
                keepAccountId, absorbAccountId);

        keep.setBalanceOwed(nz(keep.getBalanceOwed()).add(nz(absorb.getBalanceOwed())));
        keep.setWalletBalance(nz(keep.getWalletBalance()).add(nz(absorb.getWalletBalance())));
        keep.setLoyaltyPoints(Math.max(0, keep.getLoyaltyPoints()) + Math.max(0, absorb.getLoyaltyPoints()));

        if (absorb.getCreditLimit() != null) {
            if (keep.getCreditLimit() == null
                    || absorb.getCreditLimit().compareTo(keep.getCreditLimit()) > 0) {
                keep.setCreditLimit(absorb.getCreditLimit());
            }
        }
        if (absorb.isCreditSuspended()) {
            keep.setCreditSuspended(true);
        }
        if (absorb.isRemindersOptOut()) {
            keep.setRemindersOptOut(true);
        }
        Instant absorbActivity = absorb.getLastActivityAt();
        if (absorbActivity != null
                && (keep.getLastActivityAt() == null || absorbActivity.isAfter(keep.getLastActivityAt()))) {
            keep.setLastActivityAt(absorbActivity);
        }

        if (!keep.isPageSealed() && absorb.isPageSealed()) {
            keep.setPageSealed(true);
            keep.setPagePinHash(absorb.getPagePinHash());
            keep.setPageSealVerifiedAt(absorb.getPageSealVerifiedAt());
            keep.setPageSealUpdatedAt(absorb.getPageSealUpdatedAt());
        }

        entityManager.flush();
        creditAccountRepository.delete(absorb);
        entityManager.flush();
    }

    private int nativeUpdate(String sql, Object... params) {
        var q = entityManager.createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) {
            q.setParameter(i + 1, params[i]);
        }
        return q.executeUpdate();
    }

    private void mergeProfile(Customer keep, Customer absorb) {
        if (isBlank(keep.getEmail()) && !isBlank(absorb.getEmail())) {
            keep.setEmail(absorb.getEmail());
        }
        if (isBlank(keep.getNotes()) && !isBlank(absorb.getNotes())) {
            keep.setNotes(absorb.getNotes());
        } else if (!isBlank(absorb.getNotes()) && !Objects.equals(keep.getNotes(), absorb.getNotes())) {
            keep.setNotes((keep.getNotes() == null ? "" : keep.getNotes().trim())
                    + (keep.getNotes() == null || keep.getNotes().isBlank() ? "" : "\n\n")
                    + "[Merged from #" + (absorb.getCustomerNo() != null ? absorb.getCustomerNo() : absorb.getId())
                    + "] " + absorb.getNotes().trim());
        }
        if (isBlank(keep.getFirstName()) && !isBlank(absorb.getFirstName())) {
            keep.setFirstName(absorb.getFirstName());
            keep.setFirstNameNorm(absorb.getFirstNameNorm());
        }
        if (isBlank(keep.getLastName()) && !isBlank(absorb.getLastName())) {
            keep.setLastName(absorb.getLastName());
            keep.setLastNameNorm(absorb.getLastNameNorm());
        }
        if (isBlank(keep.getMpesaIdentityKey()) && !isBlank(absorb.getMpesaIdentityKey())) {
            keep.setMpesaIdentityKey(absorb.getMpesaIdentityKey());
            keep.setMpesaNameUpdatedAt(absorb.getMpesaNameUpdatedAt());
        }

        List<String> tags = new ArrayList<>(CustomerTags.parse(keep.getTags()));
        tags.addAll(CustomerTags.parse(absorb.getTags()));
        keep.setTags(CustomerTags.serialize(tags));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
