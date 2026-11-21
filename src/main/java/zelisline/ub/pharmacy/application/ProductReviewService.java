package zelisline.ub.pharmacy.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.pharmacy.domain.PharmacyProfile;
import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;
import zelisline.ub.pharmacy.repository.PharmacyProfileRepository;

/**
 * Products added since the pharmacist last checked (ADR 0033, snag list). The question asked at
 * the till when a product is added is only a prompt; this is the control behind it: someone who
 * manages the pharmacy sees every new product that is not marked as a medicine, marks the ones
 * that are, and confirms the rest.
 */
@Service
@RequiredArgsConstructor
public class ProductReviewService {

    /** With no earlier check, how far back the list reaches. */
    static final Duration FIRST_WINDOW = Duration.ofDays(30);
    static final int MAX_ROWS = 100;

    private final ItemMedicineDetailsRepository medicineDetailsRepository;
    private final PharmacyProfileRepository profileRepository;

    public record ProductToReview(String itemId, String name, String variantName, Instant addedAt) {
    }

    public record ToReview(Instant since, long total, List<ProductToReview> products) {
    }

    @Transactional(readOnly = true)
    public ToReview toReview(String businessId) {
        Instant since = since(businessId);
        long total = medicineDetailsRepository.countUnmarkedAddedSince(businessId, since);
        List<ProductToReview> products = total == 0 ? List.of()
                : medicineDetailsRepository.findUnmarkedAddedSince(businessId, since, PageRequest.of(0, MAX_ROWS)).stream()
                        .map(i -> new ProductToReview(i.getId(), i.getName(), i.getVariantName(), i.getCreatedAt()))
                        .toList();
        return new ToReview(since, total, products);
    }

    /** Everything added up to now has been looked at; what is left unmarked is not a medicine. */
    @Transactional
    public ToReview markReviewed(String businessId, String userId) {
        PharmacyProfile p = profileRepository.findById(businessId).orElseGet(() -> {
            PharmacyProfile created = new PharmacyProfile();
            created.setBusinessId(businessId);
            return created;
        });
        p.setProductsReviewedAt(Instant.now());
        p.setProductsReviewedBy(userId);
        profileRepository.save(p);
        return toReview(businessId);
    }

    private Instant since(String businessId) {
        Instant floor = Instant.now().minus(FIRST_WINDOW);
        return profileRepository.findById(businessId)
                .map(PharmacyProfile::getProductsReviewedAt)
                .filter(java.util.Objects::nonNull)
                .orElse(floor);
    }
}
