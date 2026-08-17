-- Fast till-payer lookup from sales (linked sale, then M-Pesa receipt).
ALTER TABLE inbound_till_payments
  ADD INDEX idx_itp_linked_sale (business_id, linked_sale_id);
