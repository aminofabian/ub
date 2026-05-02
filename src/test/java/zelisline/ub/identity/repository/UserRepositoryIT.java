package zelisline.ub.identity.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import zelisline.ub.identity.domain.User;

/**
 * Slice 2 invariant from {@code PHASE_1_PLAN.md} §2.4:
 * "{@code users.email} uniqueness is per-tenant, not global. (Two tenants can
 * both have {@code owner@example.com}.)"
 */
@DataJpaTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DirtiesContext
class UserRepositoryIT {

    private static final String ROLE_ID = "22222222-0000-0000-0000-000000000001";
    private static final String TENANT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TENANT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Autowired
    private UserRepository userRepository;

    @Test
    void persistsUserWithNormalisedEmail() {
        User saved = userRepository.saveAndFlush(newUser(TENANT_A, "Owner@Example.com"));

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getEmail()).isEqualTo("owner@example.com");
        assertThat(saved.getStatus()).isEqualTo("active");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void rejectsDuplicateEmailWithinSameTenant() {
        userRepository.saveAndFlush(newUser(TENANT_A, "owner@example.com"));

        User clash = newUser(TENANT_A, "owner@example.com");

        assertThatThrownBy(() -> userRepository.saveAndFlush(clash))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameEmailAcrossDifferentTenants() {
        userRepository.saveAndFlush(newUser(TENANT_A, "owner@example.com"));
        userRepository.saveAndFlush(newUser(TENANT_B, "owner@example.com"));

        assertThat(userRepository.existsByBusinessIdAndEmailAndDeletedAtIsNull(
                TENANT_A, "owner@example.com")).isTrue();
        assertThat(userRepository.existsByBusinessIdAndEmailAndDeletedAtIsNull(
                TENANT_B, "owner@example.com")).isTrue();
    }

    @Test
    void cannotFindUserAcrossTenants() {
        User saved = userRepository.saveAndFlush(newUser(TENANT_A, "owner@example.com"));

        assertThat(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(saved.getId(), TENANT_A))
                .isPresent();
        assertThat(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(saved.getId(), TENANT_B))
                .isEmpty();
    }

    private User newUser(String businessId, String email) {
        User user = new User();
        user.setBusinessId(businessId);
        user.setRoleId(ROLE_ID);
        user.setEmail(email);
        user.setName("Test User");
        user.setPasswordHash("$2a$10$placeholder");
        return user;
    }
}
