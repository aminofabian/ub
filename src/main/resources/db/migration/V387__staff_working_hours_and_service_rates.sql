-- Two things a service shop sets per person (ADR 0021).
--   working_hours: the days and hours this person works, "mon=08:00-17:00;tue=..."; a day that is
--                  not listed is a day they do not work. NULL means they work whenever the shop is open.
--   service_rates: what this person earns on particular services when it differs from their plan
--                  and from the service's own rate, "serviceId:PERCENT:60.00;serviceId:FIXED:150.00".
-- Short lists read whole with the profile and never queried by value, so they sit on the profile
-- like time_off (V380) rather than in tables of their own. Both nullable: no existing row changes.

ALTER TABLE staff_profiles
  ADD COLUMN working_hours VARCHAR(200) NULL,
  ADD COLUMN service_rates VARCHAR(4000) NULL;
