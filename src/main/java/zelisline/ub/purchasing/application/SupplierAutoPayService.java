package zelisline.ub.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.application.SupplierPayoutSettingsService;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.SupplierPayoutSettings;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.SupplierDisbursementStatuses;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.repository.SupplierDisbursementRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentAllocationRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierPayoutTypes;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Scheduled auto-pay for unpaid supply bills via KopoKopo Send Money.
 *
 * <p>Only businesses with supplier payouts + auto-pay enabled are scanned.
 * Only supply invoices (Path B session or Path A GRN) with mobile_wallet suppliers are paid.
 */
@Service
@RequiredArgsConstructor
public class SupplierAutoPayService {

    private static final Logger log = LoggerFactory.getLogger(SupplierAutoPayService.class);
    private static final BigDecimal MONEY = new BigDecimal("0.01");

    private final SupplierPayoutSettingsService supplierPayoutSettingsService;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierPaymentAllocationRepository allocationRepository;
    private final SupplierDisbursementRepository disbursementRepository;
    private final SupplierRepository supplierRepository;
    private final PathBAssociatedCostService pathBAssociatedCostService;
    private final SupplierDisbursementService supplierDisbursementService;

    @Value("${app.purchasing.supplier-auto-pay.max-per-business:50}")
    private int maxPerBusiness;

    public record AutoPayRunSummary(int businesses, int initiated, int skipped, int failed) {
    }

    /**
     * Scans opted-in tenants whose custom auto-pay clock time matches the current minute
     * and initiates Send Money for eligible unpaid supplies.
     */
    public AutoPayRunSummary runScheduledAutoPay() {
        List<SupplierPayoutSettings> optedIn = supplierPayoutSettingsService.listAutoPayEnabledSettings();
        int businessesDue = 0;
        int initiated = 0;
        int skipped = 0;
        int failed = 0;

        for (SupplierPayoutSettings settings : optedIn) {
            String businessId = settings.getBusinessId();
            try {
                Optional<String> slot = supplierPayoutSettingsService.claimAutoPaySlotIfDue(businessId);
                if (slot.isEmpty()) {
                    continue;
                }
                businessesDue++;
                log.info("Supplier auto-pay slot claimed: business={} slot={}", businessId, slot.get());
                AutoPayRunSummary one = autoPayBusiness(businessId);
                initiated += one.initiated();
                skipped += one.skipped();
                failed += one.failed();
            } catch (Exception e) {
                failed++;
                log.error("Supplier auto-pay failed for business {}", businessId, e);
            }
        }

        AutoPayRunSummary summary = new AutoPayRunSummary(businessesDue, initiated, skipped, failed);
        if (businessesDue > 0) {
            log.info(
                    "Supplier auto-pay finished: businessesDue={} initiated={} skipped={} failed={}",
                    summary.businesses(),
                    summary.initiated(),
                    summary.skipped(),
                    summary.failed());
        }
        return summary;
    }

    /**
     * Auto-pays eligible unpaid supplies for one business (used by scheduler and tests).
     */
    public AutoPayRunSummary autoPayBusiness(String businessId) {
        var gateway = supplierPayoutSettingsService.resolveActivePayoutConfig(businessId);
        if (gateway.isEmpty() || gateway.get().getGatewayType() != GatewayType.KOPOKOPO) {
            log.debug("Supplier auto-pay skip business {}: no active KopoKopo payout gateway", businessId);
            return new AutoPayRunSummary(1, 0, 0, 0);
        }

        List<SupplierInvoice> posted = supplierInvoiceRepository
                .findByBusinessIdAndStatusOrderByCreatedAtDescIdDesc(
                        businessId, PurchasingConstants.INVOICE_POSTED);

        List<SupplierInvoice> supplies = posted.stream()
                .filter(this::isSupplyInvoice)
                .toList();
        if (supplies.isEmpty()) {
            return new AutoPayRunSummary(1, 0, 0, 0);
        }

        Set<String> supplierIds = new HashSet<>();
        for (SupplierInvoice inv : supplies) {
            if (inv.getSupplierId() != null) {
                supplierIds.add(inv.getSupplierId());
            }
        }
        Map<String, Supplier> suppliersById = new HashMap<>();
        if (!supplierIds.isEmpty()) {
            for (Supplier s : supplierRepository.findAllById(supplierIds)) {
                if (businessId.equals(s.getBusinessId())) {
                    suppliersById.put(s.getId(), s);
                }
            }
        }

        int initiated = 0;
        int skipped = 0;
        int failed = 0;
        int cap = Math.max(1, maxPerBusiness);
        boolean capped = false;

        for (SupplierInvoice inv : supplies) {
            BigDecimal open = openBalance(inv);
            if (open.compareTo(MONEY) <= 0) {
                skipped++;
                continue;
            }

            Supplier supplier = suppliersById.get(inv.getSupplierId());
            if (supplier == null
                    || supplier.getDeletedAt() != null
                    || !SupplierPayoutTypes.MOBILE_WALLET.equals(supplier.getPayoutType())
                    || supplier.getPayoutPhone() == null
                    || supplier.getPayoutPhone().isBlank()) {
                skipped++;
                continue;
            }

            boolean pending = disbursementRepository
                    .findFirstByBusinessIdAndSupplierInvoiceIdAndStatusOrderByCreatedAtDesc(
                            businessId, inv.getId(), SupplierDisbursementStatuses.PENDING)
                    .isPresent();
            if (pending) {
                skipped++;
                continue;
            }

            if (initiated >= cap) {
                skipped++;
                capped = true;
                continue;
            }

            try {
                supplierDisbursementService.initiateKopokopoPay(businessId, inv.getId());
                initiated++;
                log.info(
                        "Supplier auto-pay initiated: business={} invoice={} amount={}",
                        businessId,
                        inv.getInvoiceNumber(),
                        open);
            } catch (ResponseStatusException e) {
                failed++;
                log.warn(
                        "Supplier auto-pay declined for business={} invoice={}: {}",
                        businessId,
                        inv.getId(),
                        e.getReason() != null ? e.getReason() : e.getMessage());
            } catch (Exception e) {
                failed++;
                log.warn(
                        "Supplier auto-pay error for business={} invoice={}: {}",
                        businessId,
                        inv.getId(),
                        e.getMessage());
            }
        }

        if (capped) {
            log.info(
                    "Supplier auto-pay reached cap {} for business {} this run",
                    cap,
                    businessId);
        }

        return new AutoPayRunSummary(1, initiated, skipped, failed);
    }

    private boolean isSupplyInvoice(SupplierInvoice inv) {
        return hasText(inv.getRawPurchaseSessionId()) || hasText(inv.getGoodsReceiptId());
    }

    private BigDecimal openBalance(SupplierInvoice inv) {
        BigDecimal paid = allocationRepository.sumAmountBySupplierInvoiceId(inv.getId());
        BigDecimal payable = pathBAssociatedCostService.payableGrandTotal(inv.getBusinessId(), inv);
        return payable.subtract(paid != null ? paid : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
