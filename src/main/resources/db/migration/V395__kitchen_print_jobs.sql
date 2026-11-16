-- A kitchen ticket waiting to be printed at a station. Addressed to a branch and a station, not a
-- person: any signed-in device at the branch may claim it (claimed_at compare-and-set makes a claim
-- exclusive) and mark it printed. A job still unclaimed after a while is shown on the counter as
-- "kitchen ticket not printed". reprint_of is set when a job reprints an earlier ticket.
CREATE TABLE kitchen_print_jobs (
    id            CHAR(36)     NOT NULL,
    business_id   CHAR(36)     NOT NULL,
    branch_id     CHAR(36)     NOT NULL,
    station_id    CHAR(36)     NULL,
    ticket_id     CHAR(36)     NOT NULL,
    order_number  BIGINT       NOT NULL,
    ticket_type   VARCHAR(8)   NOT NULL,
    payload_json  MEDIUMTEXT   NOT NULL,
    reprint_of    CHAR(36)     NULL,
    created_at    DATETIME(6)  NOT NULL,
    claimed_at    DATETIME(6)  NULL,
    claimed_by    CHAR(36)     NULL,
    printed_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    KEY idx_kitchen_print_pending (business_id, branch_id, claimed_at, created_at)
);
