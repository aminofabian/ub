-- A kitchen station can name its own printer, so a new ticket for that station can be printed by
-- any device at the branch, not only the one that rang the sale. A CUPS/printer name, or a network
-- printer's host and port (raw ESC/POS, usually 9100). All null means the station has no printer of
-- its own and a kitchen screen prints on whatever printer that device is set up with, as before.
ALTER TABLE kitchen_stations
  ADD COLUMN printer_name VARCHAR(191) NULL,
  ADD COLUMN printer_host VARCHAR(191) NULL,
  ADD COLUMN printer_port INT          NULL;
