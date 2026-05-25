-- V112: Per-user item-type (department) restrictions for grocery clerks.
--
-- A row in this table grants user `user_id` access to items of type `item_type_id`.
-- The catalog API (and grocery counter) AND-s this set into queries when the
-- caller's role is `grocery_clerk` so the clerk only sees / can invoice items
-- from their assigned departments. Users with other roles ignore this table.
--
-- Empty assignment list for a grocery_clerk = sees nothing — admins must
-- assign at least one department on the Users page before they can work.

CREATE TABLE user_item_types (
    user_id       CHAR(36)  NOT NULL,
    item_type_id  CHAR(36)  NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id, item_type_id),

    CONSTRAINT fk_uit_user      FOREIGN KEY (user_id)      REFERENCES users(id)      ON DELETE CASCADE,
    CONSTRAINT fk_uit_item_type FOREIGN KEY (item_type_id) REFERENCES item_types(id) ON DELETE CASCADE,

    KEY idx_uit_user (user_id),
    KEY idx_uit_item_type (item_type_id)
);
