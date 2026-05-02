package zelisline.ub.tenancy.application;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.repository.IdempotencyKeyRepository;
import zelisline.ub.identity.repository.ApiKeyRepository;
import zelisline.ub.identity.repository.EmailVerificationTokenRepository;
import zelisline.ub.identity.repository.PasswordResetTokenRepository;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.identity.repository.UserSessionRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Super-admin purge: soft-deletes a business and all tenant users, revokes
 * sessions, and removes ancillary rows tied to the tenant or its users.
 */
@Service
@RequiredArgsConstructor
public class BusinessDeletionService {

    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final BranchRepository branchRepository;
    private final DomainMappingRepository domainMappingRepository;
    private final RoleRepository roleRepository;

    @Transactional
    public void deleteBusinessAndUsers(String businessId) {
        Business business = businessRepository
                .findById(businessId)
                .filter(b -> b.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));

        Instant now = Instant.now();
        userSessionRepository.revokeAllActiveForBusiness(businessId, now);

        List<String> userIds = userRepository.findIdsByBusinessIdAndDeletedAtIsNull(businessId);
        if (!userIds.isEmpty()) {
            emailVerificationTokenRepository.deleteAllByUserIdIn(userIds);
            passwordResetTokenRepository.deleteAllByUserIdIn(userIds);
        }

        apiKeyRepository.deleteAllByBusinessId(businessId);
        idempotencyKeyRepository.deleteAllByBusinessId(businessId);
        branchRepository.softDeleteAllByBusinessId(businessId, now);
        releaseAndSoftDeleteDomains(businessId, now);
        userRepository.softDeleteAllByBusinessId(businessId, now);
        roleRepository.softDeleteTenantRolesForBusiness(businessId, now);

        business.setSlug(archivedSlug(business.getSlug(), business.getId()));
        business.setDeletedAt(now);
        businessRepository.save(business);
    }

    /**
     * Appends {@code -{businessId}} so the plain slug satisfies {@code UNIQUE(slug)} and can be
     * reused by a new tenant (see {@link TenancyService#createBusiness}).
     */
    private void releaseAndSoftDeleteDomains(String businessId, Instant now) {
        List<DomainMapping> rows = domainMappingRepository.findByBusinessIdAndDeletedAtIsNull(businessId);
        if (rows.isEmpty()) {
            return;
        }
        for (DomainMapping row : rows) {
            row.setDomain(archivedDomainHostname(row.getDomain(), row.getId()));
            row.setDeletedAt(now);
            row.setActive(false);
        }
        domainMappingRepository.saveAll(rows);
    }

    private static String archivedDomainHostname(String domain, String domainRowId) {
        final int max = 255;
        String suffix = "-" + domainRowId;
        if (domain.length() + suffix.length() <= max) {
            return domain + suffix;
        }
        return domain.substring(0, max - suffix.length()) + suffix;
    }

    private static String archivedSlug(String slug, String businessId) {
        final int slugMax = 191;
        String suffix = "-" + businessId;
        if (slug.length() + suffix.length() <= slugMax) {
            return slug + suffix;
        }
        return slug.substring(0, slugMax - suffix.length()) + suffix;
    }
}
