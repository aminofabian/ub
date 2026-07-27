package zelisline.ub.marketplace.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "platform_supplier_number_seq")
public class PlatformSupplierNumberSeq {

    public static final String SINGLETON_ID = "00000000-0000-0000-0000-000000000001";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id = SINGLETON_ID;

    @Column(name = "next_value", nullable = false)
    private long nextValue = 1L;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
