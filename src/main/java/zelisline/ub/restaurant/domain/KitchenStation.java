package zelisline.ub.restaurant.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Where food or drink is prepared ("Hot kitchen", "Bar"). A business with none has one implicit kitchen. */
@Getter
@Setter
@Entity
@Table(name = "kitchen_stations")
public class KitchenStation {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * The printer this station's tickets go to, so any device at the branch — not just the one
     * that rang the sale — can print them. A CUPS/printer name, or a network printer's host and
     * port (raw ESC/POS, usually 9100). All null means the station has no printer of its own and
     * a kitchen screen prints its tickets on whatever printer that device is set up with.
     */
    @Column(name = "printer_name", length = 191)
    private String printerName;

    @Column(name = "printer_host", length = 191)
    private String printerHost;

    @Column(name = "printer_port")
    private Integer printerPort;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
