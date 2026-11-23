package zelisline.ub.marketplace.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "marketplace_suppliers")
public class MarketplaceSupplier {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    /** Global sequential number, e.g. S-000001. Assigned on create; never reused. */
    @Column(name = "supplier_number", nullable = false, length = 32, unique = true)
    private String supplierNumber;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Unique public vanity handle for kiosk.ke/s/{username}. */
    @Column(name = "username", length = 64, unique = true)
    private String username;

    @Column(name = "page_sealed", nullable = false)
    private boolean pageSealed;

    @Column(name = "page_pin_hash", length = 255)
    private String pagePinHash;

    @Column(name = "page_seal_verified_at")
    private Instant pageSealVerifiedAt;

    @Column(name = "page_seal_updated_at")
    private Instant pageSealUpdatedAt;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    /** Second WhatsApp / call line collected at portal sign-up. */
    @Column(name = "alt_contact_phone", length = 32)
    private String altContactPhone;

    /** Area or market the supplier named at sign-up (e.g. Githurai). */
    @Column(name = "contact_location", length = 255)
    private String contactLocation;

    @Column(name = "business_legal_name", length = 255)
    private String businessLegalName;

    @Column(name = "paybill", length = 64)
    private String paybill;

    @Column(name = "till_number", length = 64)
    private String tillNumber;

    @Column(name = "bank_name", length = 128)
    private String bankName;

    @Column(name = "bank_branch", length = 128)
    private String bankBranch;

    @Column(name = "bank_account_number", length = 64)
    private String bankAccountNumber;

    @Column(name = "bank_account_name", length = 255)
    private String bankAccountName;

    @Column(name = "mobile_money", length = 64)
    private String mobileMoney;

    @Column(name = "preferred_payment_method", length = 64)
    private String preferredPaymentMethod;

    @Column(name = "tax_pin", length = 64)
    private String taxPin;

    @Column(name = "vat_number", length = 64)
    private String vatNumber;

    @Column(name = "contact_person", length = 255)
    private String contactPerson;

    /** The supplier's own shop, once it has opened one (ADR 0038, P1). Null = portal only. */
    @Column(name = "business_id", length = 36, unique = true)
    private String businessId;

    /** Shown in the public supplier directory. Set when the supplier claims the passport (ADR 0038). */
    @Column(name = "listed", nullable = false)
    private boolean listed;

    /** Decision D2: switches the readiness checklist on in the portal for a supplier with no shop. Off by default. */
    @Column(name = "engagement_enabled", nullable = false)
    private boolean engagementEnabled;

    /** The supplier keeps its prices off public pages. */
    @Column(name = "hide_prices", nullable = false)
    private boolean hidePrices;

    /** The supplier keeps its phone and email off public pages. */
    @Column(name = "hide_contacts", nullable = false)
    private boolean hideContacts;

    /** draft | active | suspended */
    /** The supplier's own dispatch lead time in working days (SUPPLIER_ENGAGEMENT_DESIGN.md D5). */
    @Column(name = "dispatch_lead_working_days", nullable = false)
    private int dispatchLeadWorkingDays = 2;

    /** The dates the supplier marks as closed, as a JSON array of ISO dates. Null when none. */
    @Column(name = "closed_dates_json", columnDefinition = "TEXT")
    private String closedDatesJson;

    @Column(name = "status", nullable = false, length = 16)
    private String status = MarketplaceSupplierStatuses.DRAFT;

    @Column(name = "delivery_regions_json", columnDefinition = "TEXT")
    private String deliveryRegionsJson;

    @Column(name = "category_tags_json", columnDefinition = "TEXT")
    private String categoryTagsJson;

    @Column(name = "trust_score", precision = 5, scale = 2)
    private BigDecimal trustScore;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
