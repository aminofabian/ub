package zelisline.ub.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@ExtendWith(MockitoExtension.class)
class AbandonedBusinessGcServiceTest {

    @Mock
    private BusinessRepository businessRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private BusinessDeletionService businessDeletionService;

    private AbandonedBusinessGcService service;

    @BeforeEach
    void setUp() {
        service = new AbandonedBusinessGcService(
                businessRepository, userRepository, businessDeletionService);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "ageDays", 7);
        ReflectionTestUtils.setField(service, "batchSize", 50);
    }

    @Test
    void sweepSkipsWhenDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.sweep()).isZero();
        verify(businessRepository, never()).findLiveCreatedOnOrBefore(any(), any());
    }

    @Test
    void sweepPurgesOnlyZeroUserTenants() {
        Business orphan = business("biz-orphan", "ghost-shop");
        Business staffed = business("biz-staffed", "real-shop");
        when(businessRepository.findLiveCreatedOnOrBefore(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(orphan, staffed));
        when(userRepository.countByBusinessIdAndDeletedAtIsNull("biz-orphan")).thenReturn(0L);
        when(userRepository.countByBusinessIdAndDeletedAtIsNull("biz-staffed")).thenReturn(1L);

        assertThat(service.sweep()).isEqualTo(1);

        verify(businessDeletionService).deleteBusinessAndUsers("biz-orphan");
        verify(businessDeletionService, never()).deleteBusinessAndUsers(eq("biz-staffed"));
    }

    @Test
    void isOwnerlessOrphanRequiresZeroUsers() {
        when(userRepository.countByBusinessIdAndDeletedAtIsNull("a")).thenReturn(0L);
        when(userRepository.countByBusinessIdAndDeletedAtIsNull("b")).thenReturn(2L);
        assertThat(service.isOwnerlessOrphan("a")).isTrue();
        assertThat(service.isOwnerlessOrphan("b")).isFalse();
    }

    private static Business business(String id, String slug) {
        Business b = new Business();
        b.setId(id);
        b.setSlug(slug);
        b.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return b;
    }
}
