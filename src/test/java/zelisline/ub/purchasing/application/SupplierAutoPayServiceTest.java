package zelisline.ub.purchasing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import zelisline.ub.payments.application.SupplierPayoutSettingsService;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.SupplyKopokopoPayResponse;
import zelisline.ub.purchasing.domain.SupplierDisbursement;
import zelisline.ub.purchasing.domain.SupplierDisbursementStatuses;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.repository.SupplierDisbursementRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentAllocationRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierPayoutTypes;
import zelisline.ub.suppliers.repository.SupplierRepository;

@ExtendWith(MockitoExtension.class)
class SupplierAutoPayServiceTest {

    @Mock
    private SupplierPayoutSettingsService supplierPayoutSettingsService;
    @Mock
    private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock
    private SupplierPaymentAllocationRepository allocationRepository;
    @Mock
    private SupplierDisbursementRepository disbursementRepository;
    @Mock
    private SupplierRepository supplierRepository;
    @Mock
    private PathBAssociatedCostService pathBAssociatedCostService;
    @Mock
    private SupplierDisbursementService supplierDisbursementService;

    private SupplierAutoPayService service;

    @BeforeEach
    void setUp() {
        service = new SupplierAutoPayService(
                supplierPayoutSettingsService,
                supplierInvoiceRepository,
                allocationRepository,
                disbursementRepository,
                supplierRepository,
                pathBAssociatedCostService,
                supplierDisbursementService);
        ReflectionTestUtils.setField(service, "maxPerBusiness", 50);
    }

    @Test
    void initiatesForEligibleSupplyAndSkipsOthers() {
        when(supplierPayoutSettingsService.resolveActivePayoutConfig("biz-1"))
                .thenReturn(Optional.of(activeKopokopo()));

        SupplierInvoice eligible = supplyInvoice("inv-1", "sup-1", "PB-1");
        SupplierInvoice paidOff = supplyInvoice("inv-2", "sup-1", "PB-2");
        SupplierInvoice manualSupplier = supplyInvoice("inv-3", "sup-2", "PB-3");
        SupplierInvoice pendingPay = supplyInvoice("inv-4", "sup-1", "PB-4");
        SupplierInvoice nonSupply = nonSupplyInvoice("inv-5", "sup-1");

        when(supplierInvoiceRepository.findByBusinessIdAndStatusOrderByCreatedAtDescIdDesc(
                        "biz-1", PurchasingConstants.INVOICE_POSTED))
                .thenReturn(List.of(eligible, paidOff, manualSupplier, pendingPay, nonSupply));

        Supplier mobile = supplier("sup-1", SupplierPayoutTypes.MOBILE_WALLET, "254710514157");
        Supplier manual = supplier("sup-2", SupplierPayoutTypes.MANUAL, null);
        when(supplierRepository.findAllById(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(mobile, manual));

        when(pathBAssociatedCostService.payableGrandTotal(eq("biz-1"), eq(eligible)))
                .thenReturn(new BigDecimal("100.00"));
        when(pathBAssociatedCostService.payableGrandTotal(eq("biz-1"), eq(paidOff)))
                .thenReturn(new BigDecimal("50.00"));
        when(pathBAssociatedCostService.payableGrandTotal(eq("biz-1"), eq(manualSupplier)))
                .thenReturn(new BigDecimal("80.00"));
        when(pathBAssociatedCostService.payableGrandTotal(eq("biz-1"), eq(pendingPay)))
                .thenReturn(new BigDecimal("90.00"));

        when(allocationRepository.sumAmountBySupplierInvoiceId("inv-1")).thenReturn(BigDecimal.ZERO);
        when(allocationRepository.sumAmountBySupplierInvoiceId("inv-2")).thenReturn(new BigDecimal("50.00"));
        when(allocationRepository.sumAmountBySupplierInvoiceId("inv-3")).thenReturn(BigDecimal.ZERO);
        when(allocationRepository.sumAmountBySupplierInvoiceId("inv-4")).thenReturn(BigDecimal.ZERO);

        when(disbursementRepository.findFirstByBusinessIdAndSupplierInvoiceIdAndStatusOrderByCreatedAtDesc(
                        "biz-1", "inv-1", SupplierDisbursementStatuses.PENDING))
                .thenReturn(Optional.empty());
        when(disbursementRepository.findFirstByBusinessIdAndSupplierInvoiceIdAndStatusOrderByCreatedAtDesc(
                        "biz-1", "inv-4", SupplierDisbursementStatuses.PENDING))
                .thenReturn(Optional.of(new SupplierDisbursement()));

        when(supplierDisbursementService.initiateKopokopoPay("biz-1", "inv-1"))
                .thenReturn(new SupplyKopokopoPayResponse(
                        true, "d-1", "sm-1", SupplierDisbursementStatuses.PENDING, "ok"));

        var summary = service.autoPayBusiness("biz-1");

        assertThat(summary.initiated()).isEqualTo(1);
        assertThat(summary.skipped()).isGreaterThanOrEqualTo(3);
        assertThat(summary.failed()).isZero();
        verify(supplierDisbursementService).initiateKopokopoPay("biz-1", "inv-1");
        verify(supplierDisbursementService, never()).initiateKopokopoPay(eq("biz-1"), eq("inv-2"));
        verify(supplierDisbursementService, never()).initiateKopokopoPay(eq("biz-1"), eq("inv-3"));
        verify(supplierDisbursementService, never()).initiateKopokopoPay(eq("biz-1"), eq("inv-4"));
        verify(supplierDisbursementService, never()).initiateKopokopoPay(eq("biz-1"), eq("inv-5"));
    }

    @Test
    void skipsBusinessWithoutKopokopoGateway() {
        when(supplierPayoutSettingsService.resolveActivePayoutConfig("biz-1"))
                .thenReturn(Optional.empty());

        var summary = service.autoPayBusiness("biz-1");

        assertThat(summary.initiated()).isZero();
        verify(supplierInvoiceRepository, never())
                .findByBusinessIdAndStatusOrderByCreatedAtDescIdDesc(anyString(), anyString());
        verify(supplierDisbursementService, never()).initiateKopokopoPay(anyString(), anyString());
    }

    private static PaymentGatewayConfig activeKopokopo() {
        PaymentGatewayConfig cfg = new PaymentGatewayConfig();
        cfg.setId("cfg-1");
        cfg.setBusinessId("biz-1");
        cfg.setGatewayType(GatewayType.KOPOKOPO);
        cfg.setStatus(GatewayStatus.ACTIVE);
        cfg.setLabel("KopoKopo");
        return cfg;
    }

    private static SupplierInvoice supplyInvoice(String id, String supplierId, String number) {
        SupplierInvoice inv = new SupplierInvoice();
        inv.setId(id);
        inv.setBusinessId("biz-1");
        inv.setSupplierId(supplierId);
        inv.setInvoiceNumber(number);
        inv.setRawPurchaseSessionId("session-" + id);
        inv.setStatus(PurchasingConstants.INVOICE_POSTED);
        inv.setGrandTotal(new BigDecimal("100.00"));
        return inv;
    }

    private static SupplierInvoice nonSupplyInvoice(String id, String supplierId) {
        SupplierInvoice inv = new SupplierInvoice();
        inv.setId(id);
        inv.setBusinessId("biz-1");
        inv.setSupplierId(supplierId);
        inv.setInvoiceNumber("AP-1");
        inv.setStatus(PurchasingConstants.INVOICE_POSTED);
        inv.setGrandTotal(new BigDecimal("100.00"));
        return inv;
    }

    private static Supplier supplier(String id, String payoutType, String phone) {
        Supplier s = new Supplier();
        s.setId(id);
        s.setBusinessId("biz-1");
        s.setName("Supplier " + id);
        s.setPayoutType(payoutType);
        s.setPayoutPhone(phone);
        return s;
    }
}
