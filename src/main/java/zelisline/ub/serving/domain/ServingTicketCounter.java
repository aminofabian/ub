package zelisline.ub.serving.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "serving_ticket_counters")
public class ServingTicketCounter {

    public static final String DEFAULT_ID = "default";
    public static final int FIRST_NUMBER = 1001;

    @Id
    @Column(name = "id", nullable = false, length = 16)
    private String id = DEFAULT_ID;

    @Column(name = "next_number", nullable = false)
    private int nextNumber = FIRST_NUMBER;
}
