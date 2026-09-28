package zelisline.ub.desktop.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventPayload;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.desktop.api.dto.MasterDataSnapshot;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;

/**
 * Credential authority on the till (docs/scopes/DESKTOP_APP_AUDIT_SCOPE.md §4.5,
 * WP-12): the cloud owns passwords, but a PIN set on this install must survive a
 * master pull. The old behaviour clobbered any local PIN within 30 minutes.
 * Genuine conflicts are written to the audit log.
 */
class DesktopStaffSyncServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final AuditEventPublisher auditEventPublisher = mock(AuditEventPublisher.class);

    private DesktopStaffSyncService service;

    @BeforeEach
    void setUp() {
        Role cashier = mock(Role.class);
        when(cashier.getId()).thenReturn("role-cashier");
        when(roleRepository.findSystemRoleByKey("cashier")).thenReturn(Optional.of(cashier));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new DesktopStaffSyncService(
            userRepository,
            roleRepository,
            mock(PasswordEncoder.class),
            auditEventPublisher,
            new AuditEventBuilder(new ObjectMapper()));
    }

    private static MasterDataSnapshot.StaffData cloudStaff(String passwordHash, String pinHash) {
        return new MasterDataSnapshot.StaffData(
            "u1", null, "Asha", "asha@shop.test", null, "ACTIVE", "cashier",
            passwordHash, pinHash);
    }

    private static User localUser() {
        User u = new User();
        u.setId("u1");
        u.setBusinessId("biz-1");
        u.setEmail("asha@shop.test");
        u.setName("Asha");
        return u;
    }

    private AuditEventPayload captureSingleAudit() {
        ArgumentCaptor<AuditEventPayload> audit =
            ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(auditEventPublisher).publish(audit.capture());
        return audit.getValue();
    }

    @Test
    void tillSetPinSurvivesMasterPull() {
        User user = localUser();
        user.setPasswordHash("local-pass");
        user.setPinHash("local-pin");
        user.setPinEnc("local-cipher"); // marker: PIN was set on this install
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));

        service.upsertStaff("biz-1", List.of(cloudStaff("cloud-pass", "cloud-pin")), Set.of());

        assertThat(user.getPinHash()).isEqualTo("local-pin");
        assertThat(user.getPinEnc()).isEqualTo("local-cipher");
        // Password authority is the cloud.
        assertThat(user.getPasswordHash()).isEqualTo("cloud-pass");
    }

    @Test
    void cloudPinIsAdoptedWhenNoPinWasSetOnTheTill() {
        User user = localUser();
        user.setPinHash("mirrored-pin");
        user.setPinEnc(null); // mirrored (or never set locally)
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));

        service.upsertStaff("biz-1", List.of(cloudStaff("cloud-pass", "cloud-pin")), Set.of());

        assertThat(user.getPinHash()).isEqualTo("cloud-pin");
        assertThat(user.getPinEnc()).isNull();
        // No local credentials to conflict with (the row had no password/PIN set
        // on this till) — nothing is audited.
        verify(auditEventPublisher, never()).publish(any());
    }

    @Test
    void conflictingPinAndPasswordAreBothAudited() {
        User user = localUser();
        user.setPasswordHash("local-pass");
        user.setPinHash("local-pin");
        user.setPinEnc("local-cipher");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));

        service.upsertStaff("biz-1", List.of(cloudStaff("cloud-pass", "cloud-pin")), Set.of());

        ArgumentCaptor<AuditEventPayload> audit =
            ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(auditEventPublisher, times(2)).publish(audit.capture());
        assertThat(audit.getAllValues())
            .extracting(AuditEventPayload::eventType)
            .containsExactlyInAnyOrder(
                AuditEventTypes.STAFF_PASSWORD_OVERRIDDEN,
                AuditEventTypes.STAFF_PIN_KEPT_LOCAL);
        // Both conflicts are WARN so they surface in the audit failures view.
        assertThat(audit.getAllValues())
            .extracting(AuditEventPayload::severity)
            .containsOnly(AuditEventSeverity.WARN);
        // Both are attributed to the till's system sync, scoped to the staff row,
        // in the STAFF category, and carry no credential hashes.
        assertThat(audit.getAllValues())
            .allSatisfy(p -> {
                assertThat(p.category()).isEqualTo(AuditEventCategory.STAFF);
                assertThat(p.actorType()).isEqualTo(AuditEventActorType.SYSTEM);
                assertThat(p.businessId()).isEqualTo("biz-1");
                assertThat(p.targetType()).isEqualTo("user");
                assertThat(p.targetId()).isEqualTo("u1");
                assertThat(p.source()).isEqualTo("desktop_sync");
                assertThat(p.metadata()).doesNotContain("local-pass", "cloud-pass", "local-pin", "cloud-pin");
            });
    }

    @Test
    void passwordOverrideIsAuditedAsWarnAndPinKeptIsWarn() {
        User user = localUser();
        user.setPasswordHash("local-pass");
        user.setPinHash("shared-pin"); // cloud agrees → no PIN event
        user.setPinEnc("local-cipher");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));

        service.upsertStaff("biz-1", List.of(cloudStaff("cloud-pass", "shared-pin")), Set.of());

        AuditEventPayload audit = captureSingleAudit();
        assertThat(audit.eventType()).isEqualTo(AuditEventTypes.STAFF_PASSWORD_OVERRIDDEN);
        assertThat(audit.severity()).isEqualTo(AuditEventSeverity.WARN);
        assertThat(audit.metadata()).contains("\"authority\":\"cloud\"");
    }

    @Test
    void persistentPinDivergenceIsAuditedOnceNotEveryPull() {
        User user = localUser();
        user.setPasswordHash("cloud-pass"); // same as cloud → no password event
        user.setPinHash("local-pin");
        user.setPinEnc("local-cipher");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));

        List<MasterDataSnapshot.StaffData> staff = List.of(cloudStaff("cloud-pass", "cloud-pin"));
        service.upsertStaff("biz-1", staff, Set.of());
        service.upsertStaff("biz-1", staff, Set.of());

        AuditEventPayload audit = captureSingleAudit();
        assertThat(audit.eventType()).isEqualTo(AuditEventTypes.STAFF_PIN_KEPT_LOCAL);
        assertThat(audit.severity()).isEqualTo(AuditEventSeverity.WARN);
        assertThat(audit.metadata()).contains("\"authority\":\"till\"");
    }
}
