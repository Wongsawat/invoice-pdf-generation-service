-- Add optimistic locking version column to invoice_pdf_documents.
-- Existing rows default to 0 (no concurrent modification history to preserve).
ALTER TABLE invoice_pdf_documents
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
