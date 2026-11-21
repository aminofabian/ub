package zelisline.ub.pharmacy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.PermissionRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.pharmacy.api.dto.DispensingApprovalResponse;
import zelisline.ub.pharmacy.domain.DispensingApproval;
import zelisline.ub.pharmacy.domain.DispensingApprovalFailure;
import zelisline.ub.pharmacy.repository.DispensingApprovalFailureRepository;
import zelisline.ub.pharmacy.repository.DispensingApprovalRepository;
import zelisline.ub.pharmacy.repository.PharmacyStaffCredentialRepository;
import zelisline.ub.pharmacy.repository.PharmacyStaffQueryRepository;

class DispensingApprovalServiceTest {

    private static final String BIZ = "biz-1";
    private static final String CASHIER = "user-cashier";
    private static final String PHARMACIST = "user-pharmacist";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PermissionRepository permissionRepository = mock(PermissionRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final DispensingApprovalRepository approvalRepository = mock(DispensingApprovalRepository.class);
    private final DispensingApprovalFailureRepository failureRepository =
            mock(DispensingApprovalFailureRepository.class);

    private final DispensingApprovalService service = new DispensingApprovalService(
            userRepository,
            permissionRepository,
            passwordEncoder,
            approvalRepository,
            failureRepository,
            mock(PharmacyStaffQueryRepository.class),
            mock(PharmacyStaffCredentialRepository.class),
            mock(AuditEventPublisher.class),
            mock(AuditEventBuilder.class));

    private void pharmacist(String roleKey) {
        User u = new User();
        u.setId(PHARMACIST);
        u.setBusinessId(BIZ);
        u.setName("Achieng");
        u.setRoleId("role-pharmacist");
        u.setPinHash("hash");
        when(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(PHARMACIST, BIZ)).thenReturn(Optional.of(u));
        when(permissionRepository.findPermissionKeysByRoleId("role-pharmacist")).thenReturn(List.of(roleKey));
        when(passwordEncoder.matches(BIZ + ":1234", "hash")).thenReturn(true);
    }

    private HttpStatus refused(String approver, String pin) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.approve(BIZ, CASHIER, approver, pin, Map.of("item-1", BigDecimal.ONE)));
        return HttpStatus.valueOf(ex.getStatusCode().value());
    }

    @Test
    void aPreparationByAPharmacistGrantsAShortApprovalForExactlyWhatWasPrepared() {
        pharmacist(DispensingGate.PERMISSION_DISPENSE);
        String id = service.grantFromPreparation(BIZ, PHARMACIST, CASHIER, Map.of("item-1", new BigDecimal("24")));
        org.mockito.ArgumentCaptor<DispensingApproval> saved = org.mockito.ArgumentCaptor.forClass(DispensingApproval.class);
        verify(approvalRepository).save(saved.capture());
        assertEquals(saved.getValue().getId(), id);
        assertEquals(PHARMACIST, saved.getValue().getApproverUserId());
        assertEquals(CASHIER, saved.getValue().getRequestedBy());
        assertEquals(BIZ, saved.getValue().getBusinessId());
        assertEquals("item-1=24", saved.getValue().getItemIds());
        org.junit.jupiter.api.Assertions.assertTrue(
                saved.getValue().getExpiresAt().isBefore(Instant.now().plusSeconds(180)));
    }

    @Test
    void aPreparerWhoCanNoLongerDispenseGrantsNothing() {
        pharmacist("sales.sell");
        org.junit.jupiter.api.Assertions.assertNull(
                service.grantFromPreparation(BIZ, PHARMACIST, CASHIER, Map.of("item-1", BigDecimal.ONE)));
        org.junit.jupiter.api.Assertions.assertNull(
                service.grantFromPreparation(BIZ, "someone-from-another-shop", CASHIER, Map.of("item-1", BigDecimal.ONE)));
        org.junit.jupiter.api.Assertions.assertNull(
                service.grantFromPreparation(BIZ, PHARMACIST, CASHIER, Map.of()));
        verify(approvalRepository, never()).save(any(DispensingApproval.class));
    }

    @Test
    void theRightPinCreatesAnApprovalBoundToTheCashierAndTheMedicines() {
        pharmacist(DispensingGate.PERMISSION_DISPENSE);
        DispensingApprovalResponse out = service.approve(BIZ, CASHIER, PHARMACIST, " 1234 ",
                Map.of("item-1", BigDecimal.ONE, "item-2", new BigDecimal("2")));
        assertEquals(PHARMACIST, out.approverUserId());
        assertEquals("Achieng", out.approverName());
        verify(approvalRepository).save(any(DispensingApproval.class));
        verify(approvalRepository).expireOpenForRequester(eq(BIZ), eq(CASHIER), any(Instant.class));
        verify(failureRepository, never()).save(any(DispensingApprovalFailure.class));
    }

    @Test
    void aWrongPinIsCountedAndRefused() {
        pharmacist(DispensingGate.PERMISSION_DISPENSE);
        assertEquals(HttpStatus.FORBIDDEN, refused(PHARMACIST, "0000"));
        verify(failureRepository).save(any(DispensingApprovalFailure.class));
        verify(approvalRepository, never()).save(any(DispensingApproval.class));
    }

    @Test
    void tooManyWrongPinsLockTheApproverOutForAWhile() {
        pharmacist(DispensingGate.PERMISSION_DISPENSE);
        when(failureRepository.countByBusinessIdAndApproverUserIdAndCreatedAtAfter(
                eq(BIZ), eq(PHARMACIST), any(Instant.class)))
                .thenReturn((long) DispensingApprovalService.MAX_FAILURES_PER_APPROVER);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, refused(PHARMACIST, "1234"));
        verify(approvalRepository, never()).save(any(DispensingApproval.class));
    }

    @Test
    void someoneWithoutTheDispensePermissionCannotApprove() {
        pharmacist("sales.sell");
        assertEquals(HttpStatus.BAD_REQUEST, refused(PHARMACIST, "1234"));
    }

    @Test
    void aUserOfAnotherBusinessCannotApprove() {
        when(userRepository.findByIdAndBusinessIdAndDeletedAtIsNull("user-elsewhere", BIZ))
                .thenReturn(Optional.empty());
        assertEquals(HttpStatus.BAD_REQUEST, refused("user-elsewhere", "1234"));
    }

    @Test
    void anApprovalMustNameTheMedicines() {
        pharmacist(DispensingGate.PERMISSION_DISPENSE);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.approve(BIZ, CASHIER, PHARMACIST, "1234", Map.of()));
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatusCode().value());
    }
}
