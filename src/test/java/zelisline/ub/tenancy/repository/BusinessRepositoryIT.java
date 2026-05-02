package zelisline.ub.tenancy.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import zelisline.ub.tenancy.domain.Business;

/**
 * Slice 1 invariant pinned by §1.5 of {@code docs/PHASE_1_PLAN.md}:
 * "{@code businesses.slug} is unique across the platform".
 */
@DataJpaTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DirtiesContext
class BusinessRepositoryIT {

    @Autowired
    private BusinessRepository businessRepository;

    @Test
    void persistsBusinessAndNormalizesSlug() {
        Business business = newBusiness("Acme Coffee", "Acme-Coffee");

        Business saved = businessRepository.saveAndFlush(business);

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getSlug()).isEqualTo("acme-coffee");
        assertThat(saved.getCurrency()).isEqualTo("KES");
        assertThat(saved.getCountryCode()).isEqualTo("KE");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void rejectsDuplicateSlug() {
        businessRepository.saveAndFlush(newBusiness("First", "duplicate-slug"));

        Business clash = newBusiness("Second", "duplicate-slug");

        assertThatThrownBy(() -> businessRepository.saveAndFlush(clash))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existsBySlugTrueAfterSave() {
        businessRepository.saveAndFlush(newBusiness("Lookup", "lookup-co"));

        assertThat(businessRepository.existsBySlug("lookup-co")).isTrue();
        assertThat(businessRepository.existsBySlug("missing-co")).isFalse();
    }

    private Business newBusiness(String name, String slug) {
        Business business = new Business();
        business.setName(name);
        business.setSlug(slug);
        return business;
    }
}
