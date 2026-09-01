package zelisline.ub.desktop.application;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zelisline.ub.desktop.api.dto.ShiftSyncAck;
import zelisline.ub.desktop.api.dto.ShiftSyncRequest;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

/**
 * Cloud-side ingest for till-uploaded shifts (the "up" direction of
 * store-and-forward sync — see {@code ShiftSyncRequest}).
 *
 * <p>Idempotent: shifts are upserted by id, and each sale is inserted only if
 * neither its id nor its {@code idempotencyKey} already exists for the
 * business. Because the whole batch runs in one transaction, a failed push
 * rolls back entirely and the till simply retries later.
 *
 * <p>v1 scope: sales are recorded directly (visible in cloud reports, and
 * announced in realtime to connected POS/dashboard sessions) but the
 * heavy pipelines are intentionally not re-run — no receipt-number allocation,
 * no ledger journal postings, no stock deduction, no customer resolution.
 * Those are follow-ups, and the till's local copy is the source of truth for
 * its own operation.
 */
@Service
@RequiredArgsConstructor
public class DesktopSyncIngestService {

    private static final Logger log = LoggerFactory.getLogger(DesktopSyncIngestService.class);

    private final ShiftRepository shiftRepository;
    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public ShiftSyncAck ingest(String businessId, ShiftSyncRequest request) {
        int customersIngested = 0;
        if (request.customers() != null) {
            for (ShiftSyncRequest.CustomerData data : request.customers()) {
                upsertCustomer(businessId, data);
                customersIngested++;
            }
        }

        int suppliersIngested = 0;
        if (request.suppliers() != null) {
            for (ShiftSyncRequest.SupplierData data : request.suppliers()) {
                upsertSupplier(businessId, data);
                suppliersIngested++;
            }
        }

        int shifts = 0;
        int salesIngested = 0;
        int salesSkipped = 0;

        for (ShiftSyncRequest.ShiftData data : request.shifts()) {
            ingestShift(businessId, data);
            shifts++;

            if (data.sales() == null) {
                continue;
            }
            for (ShiftSyncRequest.SaleData saleData : data.sales()) {
                if (saleRepository
                        .findByBusinessIdAndIdempotencyKey(businessId, saleData.idempotencyKey())
                        .isPresent()
                        || saleRepository
                            .findByIdAndBusinessId(saleData.id(), businessId)
                            .isPresent()) {
                    salesSkipped++;
                    continue;
                }
                ingestSale(businessId, data.id(), saleData);
                salesIngested++;
                // Realtime fan-out: tell connected cloud POS sessions / dashboards
                // that a till sale just landed (same event a web POS sale fires).
                eventPublisher.publishEvent(new RealtimeBridge.SaleCompletedEvent(
                    businessId,
                    saleData.branchId(),
                    saleData.id(),
                    saleData.grandTotal()));
            }
        }

        log.info(
            "[DesktopSync] ingested {} customer(s), {} supplier(s), {} shift(s): {} new sale(s), {} already seen",
            customersIngested,
            suppliersIngested,
            shifts,
            salesIngested,
            salesSkipped
        );
        return new ShiftSyncAck(shifts, salesIngested, salesSkipped, suppliersIngested);
    }

    /**
     * Upsert a till-created customer so the sales below can reference it (FK
     * {@code sales.customer_id}). Phones are replaced wholesale from the till's
     * copy (it is the source of truth for customers it created); the credit
     * account carries the till's authoritative balance. A new cloud customer
     * gets the next sequential number so the cloud's numbering stays intact.
     */
    private void upsertCustomer(String businessId, ShiftSyncRequest.CustomerData data) {
        Customer customer = customerRepository
            .findByIdAndBusinessIdAndDeletedAtIsNull(data.id(), businessId)
            .orElseGet(() -> {
                Customer created = new Customer();
                created.setId(data.id());
                created.setBusinessId(businessId);
                created.setCustomerNo(customerRepository
                    .nextCustomerNo(businessId)
                    .map(n -> n + 1)
                    .orElse(1L));
                return created;
            });
        customer.setName(data.name());
        customer.setEmail(data.email());
        customer.setNotes(data.notes());
        // Flush BEFORE phones / credit account are queued — Hibernate does not
        // order unassociated inserts, so credit_accounts (alphabetically first)
        // would otherwise hit the customers FK before the row exists.
        customerRepository.saveAndFlush(customer);

        if (data.phones() != null) {
            // Replace the till's phone list wholesale (idempotent re-push safe).
            customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customer.getId())
                .forEach(p -> customerPhoneRepository.delete(p));
            for (ShiftSyncRequest.CustomerPhoneData phoneData : data.phones()) {
                // A phone may already belong to a cloud-created customer — skip
                // rather than trip uq_customer_phones_business_phone.
                if (customerPhoneRepository.existsByBusinessIdAndPhone(businessId, phoneData.phone())) {
                    continue;
                }
                CustomerPhone phone = new CustomerPhone();
                phone.setId(phoneData.id());
                phone.setBusinessId(businessId);
                phone.setCustomerId(customer.getId());
                phone.setPhone(phoneData.phone());
                phone.setPrimary(phoneData.primary());
                customerPhoneRepository.save(phone);
            }
        }

        if (data.creditAccount() != null) {
            CreditAccount acc = creditAccountRepository
                .findByCustomerIdAndBusinessId(customer.getId(), businessId)
                .orElseGet(() -> {
                    CreditAccount created = new CreditAccount();
                    created.setId(java.util.UUID.randomUUID().toString());
                    created.setBusinessId(businessId);
                    created.setCustomerId(customer.getId());
                    return created;
                });
            acc.setBalanceOwed(data.creditAccount().balanceOwed());
            if (data.creditAccount().walletBalance() != null) {
                acc.setWalletBalance(data.creditAccount().walletBalance());
            }
            acc.setLoyaltyPoints(data.creditAccount().loyaltyPoints());
            acc.setCreditLimit(data.creditAccount().creditLimit());
            acc.setLastActivityAt(java.time.Instant.now());
            creditAccountRepository.save(acc);
        }
    }

    /**
     * Upsert a till-created/edited supplier id-preservingly (the cloud adopts
     * the till's id, as it does for till customers). Contacts are replaced
     * wholesale from the till's copy — the till is the source of truth for the
     * suppliers it edited (last-writer-wins per the scope's conflict rule).
     */
    private void upsertSupplier(String businessId, ShiftSyncRequest.SupplierData data) {
        Supplier supplier = supplierRepository
            .findByIdAndBusinessId(data.id(), businessId)
            .orElseGet(() -> {
                Supplier created = new Supplier();
                created.setId(data.id());
                created.setBusinessId(businessId);
                return created;
            });
        supplier.setName(data.name());
        supplier.setCode(data.code());
        supplier.setSupplierType(data.supplierType() == null ? "distributor" : data.supplierType());
        supplier.setVatPin(data.vatPin());
        supplier.setTaxExempt(data.taxExempt());
        supplier.setCreditTermsDays(data.creditTermsDays());
        supplier.setCreditLimit(data.creditLimit());
        supplier.setStatus(data.status() == null || data.status().isBlank() ? "active" : data.status());
        supplier.setNotes(data.notes());
        supplier.setPaymentMethodPreferred(data.paymentMethodPreferred());
        supplier.setPaymentDetails(data.paymentDetails());
        supplier.setPayoutType(data.payoutType() == null ? "manual" : data.payoutType());
        supplier.setPayoutPhone(data.payoutPhone());
        supplier.setPayoutTillNumber(data.payoutTillNumber());
        supplier.setPayoutPaybillNumber(data.payoutPaybillNumber());
        supplier.setPayoutPaybillAccount(data.payoutPaybillAccount());
        // Flush BEFORE contacts are queued — Hibernate does not order
        // unassociated inserts, so supplier_contacts (alphabetically first)
        // would hit the suppliers FK before the row exists.
        supplierRepository.saveAndFlush(supplier);

        if (data.contacts() != null) {
            supplierContactRepository.findBySupplierIdOrderByPrimaryContactDescNameAsc(supplier.getId())
                .forEach(supplierContactRepository::delete);
            for (ShiftSyncRequest.SupplierContactData c : data.contacts()) {
                SupplierContact contact = new SupplierContact();
                contact.setId(c.id());
                contact.setSupplierId(supplier.getId());
                contact.setName(c.name());
                contact.setRoleLabel(c.roleLabel());
                contact.setPhone(c.phone());
                contact.setEmail(c.email());
                contact.setPrimaryContact(c.primary());
                supplierContactRepository.save(contact);
            }
        }
    }

    private void ingestShift(String businessId, ShiftSyncRequest.ShiftData data) {
        Shift shift = shiftRepository
            .findByIdAndBusinessId(data.id(), businessId)
            .orElseGet(() -> {
                Shift created = new Shift();
                created.setId(data.id());
                created.setBusinessId(businessId);
                return created;
            });

        shift.setBranchId(data.branchId());
        // Blank → null: a legacy shift (no X-Till-Device-Id) is the shared
        // branch drawer, never an empty-string key.
        shift.setTillDeviceKey(blankToNull(data.tillDeviceKey()));
        shift.setStatus(data.status());
        // shifts.opened_by is NOT NULL — without this every new shift upload
        // failed the whole ingest (sale included) on the cloud.
        shift.setOpenedBy(data.openedBy());
        shift.setOpeningCash(data.openingCash());
        shift.setExpectedClosingCash(data.expectedClosingCash());
        shift.setCountedClosingCash(data.countedClosingCash());
        shift.setClosingVariance(data.closingVariance());
        shift.setOpeningNotes(data.openingNotes());
        shift.setClosingNotes(data.closingNotes());
        shift.setVarianceReason(data.varianceReason());
        shift.setBlindClosing(data.blindClosing());
        shift.setOpenedAt(data.openedAt());
        shift.setClosedAt(data.closedAt());
        shiftRepository.save(shift);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void ingestSale(String businessId, String shiftId, ShiftSyncRequest.SaleData data) {
        Sale sale = new Sale();
        sale.setId(data.id());
        sale.setBusinessId(businessId);
        sale.setBranchId(data.branchId());
        sale.setShiftId(shiftId);
        sale.setStatus(data.status());
        sale.setIdempotencyKey(data.idempotencyKey());
        sale.setGrandTotal(data.grandTotal());
        sale.setCashReceived(data.cashReceived());
        sale.setSoldBy(data.soldBy());
        // Customers are upserted earlier in the same batch, so the FK resolves.
        sale.setCustomerId(data.customerId());
        sale.setSoldAt(data.soldAt() == null ? Instant.now() : data.soldAt());
        sale.setVoidedAt(data.voidedAt());
        sale.setVoidNotes(data.voidNotes());
        sale.setRefundedTotal(data.refundedTotal() == null
            ? java.math.BigDecimal.ZERO
            : data.refundedTotal());
        saleRepository.save(sale);

        if (data.items() != null) {
            for (ShiftSyncRequest.SaleItemData itemData : data.items()) {
                SaleItem item = new SaleItem();
                item.setId(itemData.id());
                item.setSaleId(sale.getId());
                item.setLineIndex(itemData.lineIndex());
                item.setLineKind(itemData.lineKind() == null
                    ? zelisline.ub.sales.domain.SaleLineKinds.ITEM
                    : itemData.lineKind());
                item.setLineLabel(itemData.lineLabel());
                item.setItemId(itemData.itemId());
                // Till batches don't exist on the cloud — referencing one would
                // trip fk_si_batch and roll back the whole ingest. The till
                // already omits batch ids on upload; null defensively here too.
                item.setBatchId(null);
                item.setQuantity(itemData.quantity());
                item.setUnitPrice(itemData.unitPrice());
                item.setLineTotal(itemData.lineTotal());
                item.setUnitCost(itemData.unitCost());
                item.setCostTotal(itemData.costTotal());
                item.setProfit(itemData.profit());
                item.setRegularUnitPrice(itemData.regularUnitPrice());
                item.setDiscountAmount(itemData.discountAmount());
                item.setDiscountId(itemData.discountId());
                item.setDiscountName(itemData.discountName());
                saleItemRepository.save(item);
            }
        }

        if (data.payments() != null) {
            for (ShiftSyncRequest.SalePaymentData paymentData : data.payments()) {
                SalePayment payment = new SalePayment();
                payment.setId(paymentData.id());
                payment.setSaleId(sale.getId());
                payment.setMethod(paymentData.method());
                payment.setAmount(paymentData.amount());
                payment.setReference(paymentData.reference());
                payment.setSortOrder(paymentData.sortOrder());
                salePaymentRepository.save(payment);
            }
        }
    }
}
