package zelisline.ub.purchasing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.CustomerMessageDispatcher.DeliveryResult;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.opsalerts.application.BusinessOpsAlertSettingsService;
import zelisline.ub.opsalerts.domain.BusinessOpsAlertSettings;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.NotifyPurchaseOrderRequest;
import zelisline.ub.purchasing.api.dto.NotifyPurchaseOrderResponse;
import zelisline.ub.purchasing.api.dto.NotifyPurchaseOrderResponse.Delivery;
import zelisline.ub.purchasing.application.PurchaseOrderNotifyService.OrderLine;
import zelisline.ub.purchasing.application.PurchaseOrderNotifyService.OrderSummary;
import zelisline.ub.purchasing.domain.PurchaseOrder;
import zelisline.ub.purchasing.domain.PurchaseOrderLine;
import zelisline.ub.purchasing.repository.PurchaseOrderLineRepository;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@ExtendWith(MockitoExtension.class)
class PurchaseOrderNotifyServiceTest {

    private static final String BIZ = "biz-1";
    private static final String PO_ID = "po-1";

    @Mock PurchaseOrderRepository purchaseOrderRepository;
    @Mock PurchaseOrderLineRepository purchaseOrderLineRepository;
    @Mock ItemRepository itemRepository;
    @Mock SupplierRepository supplierRepository;
    @Mock SupplierContactRepository supplierContactRepository;
    @Mock BranchRepository branchRepository;
    @Mock BusinessRepository businessRepository;
    @Mock BusinessOpsAlertSettingsService opsAlertSettingsService;
    @Mock BusinessCreditMessagingSettingsService messagingSettingsService;
    @Mock CustomerMessageDispatcher messageDispatcher;

    PurchaseOrderNotifyService service;

    @BeforeEach
    void setUp() {
        service = new PurchaseOrderNotifyService(
                purchaseOrderRepository, purchaseOrderLineRepository, itemRepository, supplierRepository,
                supplierContactRepository, branchRepository, businessRepository, opsAlertSettingsService,
                messagingSettingsService, messageDispatcher);
    }

    // ---- guards -------------------------------------------------------------------------------

    @Test
    void otherTenantsPurchaseOrder_isNotFound_andNothingIsSent() {
        when(purchaseOrderRepository.findByIdAndBusinessId(PO_ID, "other-biz")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.notifyParties("other-biz", PO_ID, req(true, "sms", true, "sms")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        verifyNothingSent();
    }

    @Test
    void unknownChannel_isRejected_beforeAnythingIsLoaded() {
        assertThatThrownBy(() -> service.notifyParties(BIZ, PO_ID, req(true, "telegram", false, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        verifyNothingSent();
    }

    @Test
    void cancelledOrder_isRejected() {
        PurchaseOrder po = po();
        po.setStatus(PurchasingConstants.PO_CANCELLED);
        when(purchaseOrderRepository.findByIdAndBusinessId(PO_ID, BIZ)).thenReturn(Optional.of(po));

        assertThatThrownBy(() -> service.notifyParties(BIZ, PO_ID, req(true, "sms", false, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        verifyNothingSent();
    }

    @Test
    void nobodyRequested_sendsNothing() {
        givenOrderWithOneLine();

        NotifyPurchaseOrderResponse res = service.notifyParties(BIZ, PO_ID, req(false, null, false, null));

        assertThat(res.deliveries()).isEmpty();
        verifyNothingSent();
    }

    // ---- admin --------------------------------------------------------------------------------

    @Test
    void admin_withoutVerifiedPhone_isSkipped() {
        givenOrderWithOneLine();
        givenSupplier();
        BusinessOpsAlertSettings settings = new BusinessOpsAlertSettings();
        settings.setPhone("0712345678"); // present but never verified
        when(opsAlertSettingsService.resolveForBusiness(BIZ)).thenReturn(settings);

        Delivery d = only(service.notifyParties(BIZ, PO_ID, req(true, "whatsapp", false, null)));

        assertThat(d.recipient()).isEqualTo("admin");
        assertThat(d.outcome()).isEqualTo("skipped");
        verifyNothingSent();
    }

    @Test
    void admin_sms_sendsListToVerifiedNumber() {
        givenOrderWithOneLine();
        givenSupplier();
        givenVerifiedAdmin("0712345678");
        when(messagingSettingsService.resolveForTest(BIZ, SmsSendReason.OPS_ALERT))
                .thenReturn(messaging(true, true));
        when(messageDispatcher.deliverSmsOnly(any(), eq("254712345678"), anyString()))
                .thenReturn(result("sms", "sent"));

        Delivery d = only(service.notifyParties(BIZ, PO_ID, req(true, "sms", false, null)));

        assertThat(d.outcome()).isEqualTo("sent");
        assertThat(d.phoneMasked()).isEqualTo("25471****78").doesNotContain("2345");
        verify(messageDispatcher, never()).deliverWhatsAppOnly(any(), anyString(), anyString());
    }

    // ---- supplier -----------------------------------------------------------------------------

    @Test
    void supplier_withoutAnyPhone_isSkipped() {
        givenOrderWithOneLine();
        givenSupplier();
        when(supplierContactRepository.findBySupplierIdOrderByPrimaryContactDescNameAsc("sup-1"))
                .thenReturn(List.of());

        Delivery d = only(service.notifyParties(BIZ, PO_ID, req(false, null, true, "whatsapp")));

        assertThat(d.recipient()).isEqualTo("supplier");
        assertThat(d.outcome()).isEqualTo("skipped");
        assertThat(d.detail()).contains("No phone number");
        verifyNothingSent();
    }

    @Test
    void supplier_whatsApp_usesPrimaryContact_andNeverFallsBackToSms() {
        givenOrderWithOneLine();
        givenSupplier();
        SupplierContact primary = new SupplierContact();
        primary.setPhone("+254 722 000 111");
        primary.setPrimaryContact(true);
        when(supplierContactRepository.findBySupplierIdOrderByPrimaryContactDescNameAsc("sup-1"))
                .thenReturn(List.of(primary));
        when(messagingSettingsService.resolveForTest(BIZ, SmsSendReason.NOTIFICATION))
                .thenReturn(messaging(true, true));
        when(messageDispatcher.deliverWhatsAppOnly(any(), eq("254722000111"), anyString()))
                .thenReturn(result("whatsapp", "failed"));

        Delivery d = only(service.notifyParties(BIZ, PO_ID, req(false, null, true, "whatsapp")));

        assertThat(d.outcome()).isEqualTo("failed");
        verify(messageDispatcher, never()).deliverSmsOnly(any(), anyString(), anyString());
    }

    @Test
    void supplier_both_oneLegFails_isStillReportedSent() {
        givenOrderWithOneLine();
        givenSupplier();
        SupplierContact c = new SupplierContact();
        c.setPhone("0722000111");
        when(supplierContactRepository.findBySupplierIdOrderByPrimaryContactDescNameAsc("sup-1"))
                .thenReturn(List.of(c));
        when(messagingSettingsService.resolveForTest(BIZ, SmsSendReason.NOTIFICATION))
                .thenReturn(messaging(true, true));
        when(messageDispatcher.deliverWhatsAppOnly(any(), anyString(), anyString()))
                .thenReturn(result("whatsapp", "failed"));
        when(messageDispatcher.deliverSmsOnly(any(), anyString(), anyString()))
                .thenReturn(result("sms", "sent"));

        Delivery d = only(service.notifyParties(BIZ, PO_ID, req(false, null, true, "both")));

        assertThat(d.outcome()).isEqualTo("sent");
        assertThat(d.detail()).contains("WhatsApp failed").contains("SMS sent");
    }

    @Test
    void supplier_channelNotConfigured_isSkipped_notFailed() {
        givenOrderWithOneLine();
        givenSupplier();
        SupplierContact c = new SupplierContact();
        c.setPhone("0722000111");
        when(supplierContactRepository.findBySupplierIdOrderByPrimaryContactDescNameAsc("sup-1"))
                .thenReturn(List.of(c));
        when(messagingSettingsService.resolveForTest(BIZ, SmsSendReason.NOTIFICATION))
                .thenReturn(messaging(false, false));

        Delivery d = only(service.notifyParties(BIZ, PO_ID, req(false, null, true, "sms")));

        assertThat(d.outcome()).isEqualTo("skipped");
        verifyNothingSent();
    }

    @Test
    void providerException_isReportedPerRecipient_andDoesNotStopTheOtherOne() {
        givenOrderWithOneLine();
        givenSupplier();
        givenVerifiedAdmin("0712345678");
        when(messagingSettingsService.resolveForTest(BIZ, SmsSendReason.OPS_ALERT))
                .thenThrow(new IllegalStateException("boom"));
        SupplierContact c = new SupplierContact();
        c.setPhone("0722000111");
        when(supplierContactRepository.findBySupplierIdOrderByPrimaryContactDescNameAsc("sup-1"))
                .thenReturn(List.of(c));
        when(messagingSettingsService.resolveForTest(BIZ, SmsSendReason.NOTIFICATION))
                .thenReturn(messaging(true, false));
        when(messageDispatcher.deliverWhatsAppOnly(any(), anyString(), anyString()))
                .thenReturn(result("whatsapp", "sent"));

        NotifyPurchaseOrderResponse res = service.notifyParties(BIZ, PO_ID, req(true, "sms", true, "whatsapp"));

        assertThat(res.deliveries()).extracting(Delivery::recipient, Delivery::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("admin", "failed"),
                        org.assertj.core.groups.Tuple.tuple("supplier", "sent"));
        assertThat(res.deliveries().toString()).doesNotContain("boom");
    }

    // ---- message bodies -----------------------------------------------------------------------

    @Test
    void sms_listsFirstSixItems_thenCountsTheRest() {
        List<OrderLine> lines = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            lines.add(new OrderLine("Item" + i, new BigDecimal("2.0000"), new BigDecimal("10.00")));
        }
        OrderSummary o = summary(lines);

        String sms = PurchaseOrderNotifyService.buildSupplierSms(o);

        assertThat(sms).contains("Item1 x2").contains("Item6 x2").doesNotContain("Item7").contains("+3 more");
        assertThat(sms).contains("Total KES 180").contains("PO-1");
    }

    @Test
    void whatsApp_listsEveryLineWithMoney_andAlwaysKeepsTheTotal() {
        List<OrderLine> lines = new ArrayList<>();
        for (int i = 1; i <= 400; i++) {
            lines.add(new OrderLine("A fairly long product name number " + i, BigDecimal.TEN, new BigDecimal("125.50")));
        }
        OrderSummary o = summary(lines);

        String text = PurchaseOrderNotifyService.buildAdminWhatsApp(o);

        assertThat(text.length()).isLessThanOrEqualTo(PurchaseOrderNotifyService.MAX_WHATSAPP_CHARS);
        assertThat(text).contains("1. A fairly long product name number 1 — 10 × KES 125.50 = KES 1,255");
        assertThat(text).contains("…and ").contains("more").contains("Total: KES 502,000");
    }

    @Test
    void whatsApp_supplierMessage_isAddressedAndAsksForConfirmation() {
        OrderSummary o = summary(List.of(new OrderLine("Sugar 2kg", new BigDecimal("12"), new BigDecimal("300"))));

        String text = PurchaseOrderNotifyService.buildSupplierWhatsApp(o);

        assertThat(text).startsWith("Hello Acme Wholesalers,").contains("Palmart Mini Mart")
                .contains("1. Sugar 2kg — 12 × KES 300 = KES 3,600").contains("Please confirm");
    }

    @Test
    void msisdn_keepsForeignNumbers_andNormalisesKenyanOnes() {
        assertThat(PurchaseOrderNotifyService.toMsisdn("0712 345 678")).isEqualTo("254712345678");
        assertThat(PurchaseOrderNotifyService.toMsisdn("+254712345678")).isEqualTo("254712345678");
        assertThat(PurchaseOrderNotifyService.toMsisdn("+256700123456")).isEqualTo("256700123456");
        assertThat(PurchaseOrderNotifyService.toMsisdn("  ")).isNull();
        assertThat(PurchaseOrderNotifyService.toMsisdn(null)).isNull();
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private void givenOrderWithOneLine() {
        when(purchaseOrderRepository.findByIdAndBusinessId(PO_ID, BIZ)).thenReturn(Optional.of(po()));
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setItemId("item-1");
        line.setQtyOrdered(new BigDecimal("5.0000"));
        line.setUnitEstimatedCost(new BigDecimal("100.0000"));
        when(purchaseOrderLineRepository.findByPurchaseOrderIdOrderBySortOrderAscIdAsc(PO_ID))
                .thenReturn(List.of(line));
    }

    /** Stubs only what a request that gets as far as loading the supplier needs. */
    private void givenSupplier() {
        Supplier s = new Supplier();
        s.setId("sup-1");
        s.setName("Acme Wholesalers");
        when(supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull("sup-1", BIZ)).thenReturn(Optional.of(s));
        Business b = new Business();
        b.setName("Palmart Mini Mart");
        b.setCurrency("KES");
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(b));
        Branch branch = new Branch();
        branch.setName("Main");
        when(branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull("br-1", BIZ)).thenReturn(Optional.of(branch));
        Item item = new Item();
        item.setId("item-1");
        item.setBusinessId(BIZ);
        item.setName("Sugar 2kg");
        when(itemRepository.findAllById(any())).thenReturn(List.of(item));
    }

    private void givenVerifiedAdmin(String phone) {
        BusinessOpsAlertSettings settings = new BusinessOpsAlertSettings();
        settings.setPhone(phone);
        settings.setPhoneVerifiedAt(java.time.Instant.now());
        when(opsAlertSettingsService.resolveForBusiness(BIZ)).thenReturn(settings);
    }

    private static PurchaseOrder po() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(PO_ID);
        po.setBusinessId(BIZ);
        po.setSupplierId("sup-1");
        po.setBranchId("br-1");
        po.setPoNumber("PO-1");
        po.setStatus(PurchasingConstants.PO_SENT);
        return po;
    }

    private static OrderSummary summary(List<OrderLine> lines) {
        return new OrderSummary("Palmart Mini Mart", "Main", "Acme Wholesalers", "PO-1", null, "KES", lines);
    }

    private static NotifyPurchaseOrderRequest req(boolean admin, String adminCh, boolean sup, String supCh) {
        return new NotifyPurchaseOrderRequest(admin, adminCh, sup, supCh);
    }

    private static Delivery only(NotifyPurchaseOrderResponse res) {
        assertThat(res.deliveries()).hasSize(1);
        return res.deliveries().get(0);
    }

    private static DeliveryResult result(String channel, String outcome) {
        return new DeliveryResult(null, channel, outcome, "detail-with-secret-looking-token");
    }

    private void verifyNothingSent() {
        verify(messageDispatcher, never()).deliverWhatsAppOnly(any(), anyString(), anyString());
        verify(messageDispatcher, never()).deliverSmsOnly(any(), anyString(), anyString());
    }

    /** WhatsApp ready = token + phone id; SMS ready = a Ping Africa token. */
    private static TenantMessagingConfig messaging(boolean whatsApp, boolean sms) {
        return new TenantMessagingConfig(
                true, null, null, null, null, null, false,
                whatsApp ? "token" : null, whatsApp ? "phone-id" : null, null, "platform",
                sms ? "ping_africa" : "none", null, null, null, null, null, null, null,
                null, null, null, null,
                sms ? "key" : null, null, null,
                true, null, BIZ, SmsSendReason.NOTIFICATION);
    }
}
