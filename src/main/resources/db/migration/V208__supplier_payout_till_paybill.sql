-- KopoKopo Send Money destinations: external till + paybill (in addition to mobile_wallet)

ALTER TABLE suppliers
  ADD COLUMN payout_till_number VARCHAR(32) NULL AFTER payout_phone,
  ADD COLUMN payout_paybill_number VARCHAR(32) NULL AFTER payout_till_number,
  ADD COLUMN payout_paybill_account VARCHAR(64) NULL AFTER payout_paybill_number;
