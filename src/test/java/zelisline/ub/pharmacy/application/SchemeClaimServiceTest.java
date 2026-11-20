package zelisline.ub.pharmacy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.ClaimRequest;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.ClaimStatusRequest;
import zelisline.ub.pharmacy.domain.DispensingRecord;
import zelisline.ub.pharmacy.domain.PharmacyScheme;
import zelisline.ub.pharmacy.domain.PharmacySchemeClaim;
import zelisline.ub.pharmacy.repository.DispensingRecordLineRepository;
import zelisline.ub.pharmacy.repository.DispensingRecordRepository;
import zelisline.ub.pharmacy.repository.PharmacySchemeClaimRepository;
import zelisline.ub.pharmacy.repository.PharmacySchemeRepository;
import zelisline.ub.sales.repository.SaleRepository;

class SchemeClaimServiceTest {

    private static final String BIZ = "biz-1";

    private final PharmacySchemeRepository schemeRepository = mock(PharmacySchemeRepository.class);
    private final PharmacySchemeClaimRepository claimRepository = mock(PharmacySchemeClaimRepository.class);
    private final DispensingRecordRepository recordRepository = mock(DispensingRecordRepository.class);
    private final SaleRepository saleRepository = mock(SaleRepository.class);
    private final zelisline.ub.credits.repository.CustomerRepository customerRepository =
            mock(zelisline.ub.credits.repository.CustomerRepository.class);
    private final zelisline.ub.credits.repository.CreditAccountRepository creditAccountRepository =
            mock(zelisline.ub.credits.repository.CreditAccountRepository.class);
    private final SchemeClaimService service = new SchemeClaimService(
            schemeRepository, claimRepository, recordRepository, mock(DispensingRecordLineRepository.class),
            saleRepository, customerRepository, creditAccountRepository,
            mock(AuditEventPublisher.class), mock(AuditEventBuilder.class));

    @Test
    void savingASchemeGivesItACreditAccountOnceAndNeverRemindsIt() {
        when(schemeRepository.findByIdAndBusinessId("scheme-1", BIZ)).thenReturn(Optional.empty());
        var created = service.saveScheme(BIZ, null,
                new zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.SchemeRequest("Scheme One", null, 30, null));
        verify(customerRepository).save(any(zelisline.ub.credits.domain.Customer.class));
        org.mockito.ArgumentCaptor<zelisline.ub.credits.domain.CreditAccount> account =
                org.mockito.ArgumentCaptor.forClass(zelisline.ub.credits.domain.CreditAccount.class);
        verify(creditAccountRepository).save(account.capture());
        assertTrue(account.getValue().isRemindersOptOut());
        assertEquals(BIZ, account.getValue().getBusinessId());
        assertEquals("Scheme One", created.name());
    }

    private PharmacySchemeClaim claim(String claimed) {
        PharmacySchemeClaim c = new PharmacySchemeClaim();
        c.setId("claim-1");
        c.setBusinessId(BIZ);
        c.setSchemeId("scheme-1");
        c.setRecordId("rec-1");
        c.setSaleId("sale-1");
        c.setBranchId("branch-a");
        c.setMemberNo("M-1");
        c.setAmountClaimed(new BigDecimal(claimed));
        c.setCreatedAt(Instant.now());
        when(claimRepository.findByIdAndBusinessId("claim-1", BIZ)).thenReturn(Optional.of(c));
        when(schemeRepository.findByBusinessIdOrderByNameAsc(BIZ)).thenReturn(List.of());
        when(recordRepository.findByBusinessIdAndIdIn(any(), any())).thenReturn(List.of());
        when(saleRepository.findAllById(any())).thenReturn(List.of());
        return c;
    }

    private int refused(Runnable action) {
        return assertThrows(ResponseStatusException.class, action::run).getStatusCode().value();
    }

    @Test
    void payingInFullSettlesTheClaimAndPayingLessLeavesItPartPaid() {
        PharmacySchemeClaim c = claim("1000.00");
        service.setStatus(BIZ, "claim-1", new ClaimStatusRequest("PAID", new BigDecimal("400"), null), "user-1");
        assertEquals(PharmacySchemeClaim.PART_PAID, c.getStatus());
        assertTrue(SchemeClaimService.isOutstanding(c.getStatus()));
        assertEquals(null, c.getSettledAt());

        service.setStatus(BIZ, "claim-1", new ClaimStatusRequest("PAID", new BigDecimal("1000"), null), "user-1");
        assertEquals(PharmacySchemeClaim.PAID, c.getStatus());
        assertFalse(SchemeClaimService.isOutstanding(c.getStatus()));
        assertTrue(c.getSettledAt() != null);
    }

    @Test
    void aSchemeCannotBeRecordedAsPayingMoreThanWasClaimedOrNothing() {
        claim("1000.00");
        assertEquals(HttpStatus.BAD_REQUEST.value(), refused(() -> service.setStatus(
                BIZ, "claim-1", new ClaimStatusRequest("PAID", new BigDecimal("1000.01"), null), "user-1")));
        assertEquals(HttpStatus.BAD_REQUEST.value(), refused(() -> service.setStatus(
                BIZ, "claim-1", new ClaimStatusRequest("PAID", null, null), "user-1")));
    }

    @Test
    void aRejectionNeedsAReason() {
        PharmacySchemeClaim c = claim("1000.00");
        assertEquals(HttpStatus.BAD_REQUEST.value(), refused(() -> service.setStatus(
                BIZ, "claim-1", new ClaimStatusRequest("REJECTED", null, " "), "user-1")));
        service.setStatus(BIZ, "claim-1", new ClaimStatusRequest("REJECTED", null, "Member not covered"), "user-1");
        assertEquals(PharmacySchemeClaim.REJECTED, c.getStatus());
    }

    @Test
    void anotherBusinesssClaimSchemeOrRecordIsOutOfReach() {
        when(claimRepository.findByIdAndBusinessId("claim-x", BIZ)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND.value(), refused(() -> service.setStatus(
                BIZ, "claim-x", new ClaimStatusRequest("SUBMITTED", null, null), "user-1")));

        ClaimRequest req = new ClaimRequest("scheme-x", "rec-1", "M-1", null, new BigDecimal("10"));
        when(schemeRepository.findByIdAndBusinessId("scheme-x", BIZ)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.BAD_REQUEST.value(), refused(() -> service.createClaim(BIZ, req, "user-1", null)));

        PharmacyScheme scheme = new PharmacyScheme();
        scheme.setId("scheme-1");
        scheme.setBusinessId(BIZ);
        scheme.setName("Scheme");
        when(schemeRepository.findByIdAndBusinessId("scheme-1", BIZ)).thenReturn(Optional.of(scheme));
        when(recordRepository.findByIdAndBusinessId("rec-1", BIZ)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND.value(), refused(() -> service.createClaim(
                BIZ, new ClaimRequest("scheme-1", "rec-1", "M-1", null, new BigDecimal("10")), "user-1", null)));
        verify(claimRepository, never()).save(any(PharmacySchemeClaim.class));
    }

    @Test
    void aVoidedSaleCannotBeClaimed() {
        PharmacyScheme scheme = new PharmacyScheme();
        scheme.setId("scheme-1");
        scheme.setBusinessId(BIZ);
        scheme.setName("Scheme");
        when(schemeRepository.findByIdAndBusinessId("scheme-1", BIZ)).thenReturn(Optional.of(scheme));
        DispensingRecord r = new DispensingRecord();
        r.setId("rec-1");
        r.setBusinessId(BIZ);
        r.setBranchId("branch-a");
        r.setSaleId("sale-1");
        r.setVoidedAt(Instant.now());
        when(recordRepository.findByIdAndBusinessId("rec-1", BIZ)).thenReturn(Optional.of(r));
        assertEquals(HttpStatus.CONFLICT.value(), refused(() -> service.createClaim(
                BIZ, new ClaimRequest("scheme-1", "rec-1", "M-1", null, new BigDecimal("10")), "user-1", null)));
    }
}
