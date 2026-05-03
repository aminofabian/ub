-- Category merchandising: optional cover URL + gallery rows + supplier associations.

ALTER TABLE categories
  ADD COLUMN image_key VARCHAR(2048) NULL AFTER icon;

CREATE TABLE category_images (
  id CHAR(36) PRIMARY KEY,
  category_id CHAR(36) NOT NULL,
  s3_key VARCHAR(512) NULL COMMENT 'Legacy key; Cloudinary rows often mirror public_id',
  provider VARCHAR(32) NOT NULL DEFAULT 'legacy',
  cloudinary_public_id VARCHAR(512) NULL,
  secure_url VARCHAR(2048) NULL,
  width INT NULL,
  height INT NULL,
  content_type VARCHAR(128) NULL,
  alt_text VARCHAR(500) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  bytes BIGINT NULL,
  format VARCHAR(32) NULL,
  asset_signature VARCHAR(80) NULL,
  predominant_color_hex VARCHAR(16) NULL,
  phash VARCHAR(64) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_category_images_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE
);

CREATE INDEX idx_category_images_category ON category_images (category_id);

CREATE TABLE category_supplier_links (
  id CHAR(36) PRIMARY KEY,
  business_id CHAR(36) NOT NULL,
  category_id CHAR(36) NOT NULL,
  supplier_id CHAR(36) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_category_supplier (business_id, category_id, supplier_id),
  CONSTRAINT fk_csl_business FOREIGN KEY (business_id) REFERENCES businesses(id),
  CONSTRAINT fk_csl_category FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE CASCADE,
  CONSTRAINT fk_csl_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id) ON DELETE CASCADE
);

CREATE INDEX idx_csl_business ON category_supplier_links (business_id);
CREATE INDEX idx_csl_category ON category_supplier_links (category_id);
CREATE INDEX idx_csl_supplier ON category_supplier_links (supplier_id);
