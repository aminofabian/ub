-- Multi-image galleries for global catalog products.
-- global_products.image_url remains the denormalized cover (sort_order = 0).

CREATE TABLE IF NOT EXISTS global_product_images (
  id                CHAR(36) PRIMARY KEY,
  global_product_id CHAR(36) NOT NULL,
  image_url         VARCHAR(2048) NOT NULL,
  image_public_id   VARCHAR(512) NULL,
  sort_order        INT NOT NULL DEFAULT 0,
  alt_text          VARCHAR(500) NULL,
  width             INT NULL,
  height            INT NULL,
  bytes             BIGINT NULL,
  format            VARCHAR(32) NULL,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_global_product_images_product_sort (global_product_id, sort_order),
  CONSTRAINT fk_global_product_images_product
    FOREIGN KEY (global_product_id) REFERENCES global_products(id) ON DELETE CASCADE
);

-- Backfill cover into gallery so existing single-image products keep working on adopt.
INSERT INTO global_product_images (
  id, global_product_id, image_url, image_public_id, sort_order, created_at
)
SELECT
  UUID(),
  gp.id,
  gp.image_url,
  gp.image_public_id,
  0,
  CURRENT_TIMESTAMP
FROM global_products gp
WHERE gp.image_url IS NOT NULL
  AND TRIM(gp.image_url) <> ''
  AND NOT EXISTS (
    SELECT 1 FROM global_product_images gpi WHERE gpi.global_product_id = gp.id
  );
