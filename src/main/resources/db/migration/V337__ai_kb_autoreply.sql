-- M5 — AI knowledge base + auto-reply settings.
-- Documents are chunked for lexical retrieval (no embeddings); grounding is applied to the
-- AI reply/auto-reply prompt. crm_ai_settings holds the per-business auto-reply toggle.
-- Column names/types match the entities so spring.jpa.hibernate.ddl-auto=validate passes.

CREATE TABLE ai_knowledge_document (
  id           CHAR(36)     PRIMARY KEY,
  business_id  CHAR(36)     NOT NULL,
  title        VARCHAR(200) NOT NULL,
  content      MEDIUMTEXT   NOT NULL,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ai_kb_doc_business (business_id)
);

CREATE TABLE ai_knowledge_chunk (
  id           CHAR(36)   PRIMARY KEY,
  document_id  CHAR(36)   NOT NULL,
  business_id  CHAR(36)   NOT NULL,
  ordinal      INT        NOT NULL,
  content      MEDIUMTEXT NOT NULL,
  created_at   TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ai_kb_chunk_business (business_id),
  KEY idx_ai_kb_chunk_document (document_id)
);

CREATE TABLE crm_ai_settings (
  business_id                  CHAR(36)  NOT NULL,
  auto_reply_enabled           BOOLEAN   NOT NULL DEFAULT FALSE,
  max_replies_per_conversation INT       NOT NULL DEFAULT 3,
  handoff_user_id              CHAR(36)  NULL,
  updated_at                   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (business_id)
);

-- Per-conversation counter so the auto-reply cap can be enforced.
ALTER TABLE crm_conversation
  ADD COLUMN ai_reply_count INT NOT NULL DEFAULT 0;
