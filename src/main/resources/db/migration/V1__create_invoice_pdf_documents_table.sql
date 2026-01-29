-- Create invoice_pdf_documents table
CREATE TABLE invoice_pdf_documents (
    id UUID PRIMARY KEY,
    invoice_id VARCHAR(100) NOT NULL UNIQUE,
    invoice_number VARCHAR(50) NOT NULL,
    document_path VARCHAR(500),
    document_url VARCHAR(1000),
    file_size BIGINT,
    mime_type VARCHAR(100) NOT NULL DEFAULT 'application/pdf',
    xml_embedded BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

-- Create indexes
CREATE INDEX idx_invoice_pdf_invoice_id ON invoice_pdf_documents(invoice_id);
CREATE INDEX idx_invoice_pdf_invoice_number ON invoice_pdf_documents(invoice_number);
CREATE INDEX idx_invoice_pdf_status ON invoice_pdf_documents(status);
