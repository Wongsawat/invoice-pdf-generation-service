-- Complete schema for invoice-pdf-generation-service.
-- All tables, columns, constraints, and defaults are defined here.
-- Fresh installs apply this single migration; no incremental scripts are needed.

-- ── invoice_pdf_documents ────────────────────────────────────────────────────
CREATE TABLE invoice_pdf_documents (
    id              UUID         PRIMARY KEY,
    invoice_id      VARCHAR(100) NOT NULL,
    invoice_number  VARCHAR(50)  NOT NULL,
    document_path   VARCHAR(500),
    document_url    VARCHAR(1000),
    file_size       BIGINT       NOT NULL DEFAULT 0,
    mime_type       VARCHAR(100) NOT NULL DEFAULT 'application/pdf',
    xml_embedded    BOOLEAN      NOT NULL DEFAULT false,
    status          VARCHAR(20)  NOT NULL,
    error_message   TEXT,
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP,
    CONSTRAINT uq_invoice_pdf_invoice_id UNIQUE (invoice_id)
);

CREATE INDEX idx_invoice_pdf_invoice_id     ON invoice_pdf_documents (invoice_id);
CREATE INDEX idx_invoice_pdf_invoice_number ON invoice_pdf_documents (invoice_number);
CREATE INDEX idx_invoice_pdf_status         ON invoice_pdf_documents (status);

-- ── outbox_events ─────────────────────────────────────────────────────────────
CREATE TABLE outbox_events (
    id             UUID          PRIMARY KEY,
    aggregate_type VARCHAR(100)  NOT NULL,
    aggregate_id   VARCHAR(100)  NOT NULL,
    event_type     VARCHAR(100)  NOT NULL,
    payload        TEXT          NOT NULL,
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at   TIMESTAMP,
    status         VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    retry_count    INTEGER       NOT NULL DEFAULT 0,
    error_message  VARCHAR(1000),
    topic          VARCHAR(255),
    partition_key  VARCHAR(255),
    headers        TEXT
);

CREATE INDEX idx_outbox_status    ON outbox_events (status);
CREATE INDEX idx_outbox_created   ON outbox_events (created_at);
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_id, aggregate_type);
