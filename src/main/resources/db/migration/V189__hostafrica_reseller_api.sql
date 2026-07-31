-- HostAfrica DomainsReseller API credentials + platform WHOIS for RegisterDomain.

ALTER TABLE platform_domain_settings
  ADD COLUMN hostafrica_reseller_email VARCHAR(255) NULL
    AFTER hostafrica_registrant_defaults_json,
  ADD COLUMN hostafrica_reseller_api_key_enc TEXT NULL
    AFTER hostafrica_reseller_email,
  ADD COLUMN hostafrica_reseller_api_base_url VARCHAR(512) NULL
    AFTER hostafrica_reseller_api_key_enc,
  ADD COLUMN hostafrica_reseller_whois_json TEXT NULL
    AFTER hostafrica_reseller_api_base_url;
