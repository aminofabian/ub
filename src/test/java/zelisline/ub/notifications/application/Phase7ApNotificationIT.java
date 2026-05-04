package zelisline.ub.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import zelisline.ub.notifications.domain.Notification;
import zelisline.ub.notifications.repository.NotificationRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class Phase7ApNotificationIT {

    private static final String TENANT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbc2";

    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private SupplierInvoiceRepository supplierInvoiceRepository;
    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private Phase7ApNotificationService phase7ApNotificationService;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    private String supplierId;

    @BeforeEach
    void seed() {
        notificationRepository.deleteAll();
        supplierInvoiceRepository.deleteAll();
        supplierRepository.deleteAll();
        businessRepository.deleteAll();

        Business b = new Business();
        b.setId(TENANT);
        b.setName("Notify AP Co");
        b.setSlug("notify-ap-co");
        businessRepository.save(b);

        Supplier sup = new Supplier();
        sup.setBusinessId(TENANT);
        sup.setName("SupCo");
        supplierRepository.save(sup);
        supplierId = sup.getId();

        SupplierInvoice inv = new SupplierInvoice();
        inv.setBusinessId(TENANT);
        inv.setSupplierId(supplierId);
        inv.setInvoiceNumber("INV-OD");
        inv.setInvoiceDate(LocalDate.of(2026, 4, 1));
        inv.setDueDate(LocalDate.of(2026, 5, 1));
        inv.setGrandTotal(new BigDecimal("100.00"));
        inv.setSubtotal(new BigDecimal("100.00"));
        inv.setStatus(PurchasingConstants.INVOICE_POSTED);
        supplierInvoiceRepository.save(inv);
    }

    @Test
    void overdueOpenBalance_emitsOncePerUtcDay_deduped() {
        LocalDate asOf = LocalDate.of(2026, 6, 1);

        phase7ApNotificationService.emitOverdueApIfNeeded(TENANT, asOf);
        phase7ApNotificationService.emitOverdueApIfNeeded(TENANT, asOf);

        java.util.List<Notification> rows = notificationRepository.findByBusinessIdOrderByCreatedAtDesc(TENANT);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getType()).isEqualTo(Phase7ApNotificationService.TYPE_OVERDUE_AP);
        assertThat(rows.getFirst().getDedupeKey()).isEqualTo("overdue_ap:" + TENANT + ":" + asOf);

        LocalDate asOf2 = LocalDate.of(2026, 6, 2);
        phase7ApNotificationService.emitOverdueApIfNeeded(TENANT, asOf2);
        assertThat(notificationRepository.findByBusinessIdOrderByCreatedAtDesc(TENANT)).hasSize(2);
    }
}
