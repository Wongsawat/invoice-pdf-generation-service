-- Make file_size and retry_count NOT NULL with explicit DEFAULT 0.
-- Previously both columns were nullable; existing NULL values are backfilled here.

UPDATE invoice_pdf_documents SET file_size   = 0 WHERE file_size   IS NULL;
UPDATE invoice_pdf_documents SET retry_count = 0 WHERE retry_count IS NULL;

ALTER TABLE invoice_pdf_documents ALTER COLUMN file_size   SET NOT NULL;
ALTER TABLE invoice_pdf_documents ALTER COLUMN file_size   SET DEFAULT 0;

ALTER TABLE invoice_pdf_documents ALTER COLUMN retry_count SET NOT NULL;
