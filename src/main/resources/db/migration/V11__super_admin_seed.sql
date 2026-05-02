-- Bootstrap super-admin for local/dev. Password is set out-of-band / team docs; rotate before production.

INSERT INTO super_admins (id, email, name, password_hash, active, failed_attempts, created_at, updated_at)
VALUES (
  '33333333-0000-0000-0000-000000000001',
  'aminofabian@gmail.com',
  'Fabian Amino',
  '$2a$10$0oX3ki2DAs7MqC4QFkVI5u0f6s4sPdYN3T2u2tc5iV5DxsSBjjfr.',
  TRUE,
  0,
  CURRENT_TIMESTAMP,
  CURRENT_TIMESTAMP
);
