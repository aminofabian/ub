-- Platform defaults for HostAfrica list-domains-requiring-data / save-domain-required-data.

ALTER TABLE platform_domain_settings
  ADD COLUMN hostafrica_registrant_defaults_json TEXT NULL AFTER hostafrica_billing_stub_enabled;
