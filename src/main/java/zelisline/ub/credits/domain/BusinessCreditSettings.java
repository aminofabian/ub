package zelisline.ub.credits.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "business_credit_settings")
public class BusinessCreditSettings {

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "loyalty_points_per_kes", nullable = false, precision = 14, scale = 8)
    private BigDecimal loyaltyPointsPerKes = BigDecimal.ZERO;

    @Column(name = "loyalty_kes_per_point", nullable = false, precision = 14, scale = 8)
    private BigDecimal loyaltyKesPerPoint = new BigDecimal("0.01");

    @Column(name = "loyalty_max_redeem_bps", nullable = false)
    private int loyaltyMaxRedeemBps = 5000;
}
