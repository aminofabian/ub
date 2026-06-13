package zelisline.ub.grocery.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "branch_grocery_sequences")
public class BranchGrocerySequence {

    @Id
    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "next_counter", nullable = false)
    private long nextCounter = 1L;
}
