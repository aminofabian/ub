-- Cloudinary-backed gallery: store delivery URL + public_id + creative metadata; legacy S3-style keys remain supported.

ALTER TABLE items
  MODIFY COLUMN image_key VARCHAR(2048) NULL;

ALTER TABLE item_images
  MODIFY COLUMN s3_key VARCHAR(512) NULL COMMENT 'Legacy storage key; for Cloudinary often mirrors public_id',
  ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'legacy',
  ADD COLUMN cloudinary_public_id VARCHAR(512) NULL,
  ADD COLUMN secure_url VARCHAR(2048) NULL,
  ADD COLUMN bytes BIGINT NULL,
  ADD COLUMN format VARCHAR(32) NULL,
  ADD COLUMN asset_signature VARCHAR(80) NULL COMMENT 'etag/version from provider for provenance',
  ADD COLUMN predominant_color_hex VARCHAR(16) NULL COMMENT 'dominant sRGB #RRGGBB from Cloudinary colors',
  ADD COLUMN phash VARCHAR(64) NULL COMMENT 'perceptual hash for duplicate / similarity hints';
