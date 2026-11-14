-- The fixed product cost of a service (ADR 0019): what the gel, dye or relaxer for one service
-- costs the shop, in shillings, as the owner says it. When the shop pays commission "after product
-- cost" this is taken off the price before anyone's commission is worked out; otherwise it is not
-- used. Which of the two the shop does is kept in businesses.settings (servicePay.model), so this
-- migration adds one nullable column and nothing else. NULL means the service uses no shop product.

ALTER TABLE services
  ADD COLUMN product_cost DECIMAL(14,2) NULL;
