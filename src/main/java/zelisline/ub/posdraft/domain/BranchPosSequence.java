package zelisline.ub.posdraft.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "branch_pos_sequences")
public class BranchPosSequence {

    @Id
    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "next_ticket", nullable = false)
    private long nextTicket = 1L;
}
