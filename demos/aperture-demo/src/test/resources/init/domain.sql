INSERT INTO aperture_countries (id, code, name) VALUES ('30000000-0000-0000-0000-000000000001', 'US', 'United States') ON CONFLICT (id) DO NOTHING;
INSERT INTO aperture_currencies (id, code) VALUES ('40000000-0000-0000-0000-000000000001', 'USD') ON CONFLICT (id) DO NOTHING;
INSERT INTO aperture_suppliers (id, company_name, aperture_tenant_id) VALUES ('50000000-0000-0000-0000-000000000001', 'Acme Supplies', '00000000-0000-0000-0000-000000000001') ON CONFLICT (id) DO UPDATE SET company_name = EXCLUDED.company_name;
INSERT INTO aperture_suppliers (id, company_name, aperture_tenant_id) VALUES ('50000000-0000-0000-0000-000000000002', 'Acme Logistics', '00000000-0000-0000-0000-000000000001') ON CONFLICT (id) DO UPDATE SET company_name = EXCLUDED.company_name;
INSERT INTO aperture_suppliers (id, company_name, aperture_tenant_id) VALUES ('50000000-0000-0000-0000-000000000003', 'Northwind Office Supplies', '00000000-0000-0000-0000-000000000001') ON CONFLICT (id) DO UPDATE SET company_name = EXCLUDED.company_name;
INSERT INTO aperture_suppliers (id, company_name, aperture_tenant_id) VALUES ('50000000-0000-0000-0000-000000000004', 'EU Logistics Partners', '00000000-0000-0000-0000-000000000001') ON CONFLICT (id) DO UPDATE SET company_name = EXCLUDED.company_name;
INSERT INTO aperture_suppliers (id, company_name, aperture_tenant_id) VALUES ('50000000-0000-0000-0000-000000000005', 'Cloud Components Ltd', '00000000-0000-0000-0000-000000000002') ON CONFLICT (id) DO UPDATE SET company_name = EXCLUDED.company_name;
-- Seed seed domain data for demo purposes

-- Customers: delete any rows not in the fixed fixture set first to keep counts stable across test ordering
DELETE FROM aperture_customers WHERE id NOT IN (
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000003'
);
-- Restore any fixture rows that may have been soft-deleted by a previous test
UPDATE aperture_customers SET deleted_at = NULL, version = 0 WHERE id IN (
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000003'
);
-- Email values are AES-256-GCM encrypted using the test key (aperture.encryption.local.key default).
-- Generated with: java EncryptEmails.java (see scratchpad). Re-generate if the default key changes.
-- Uses DO UPDATE to reset the email to the encrypted fixture value even when the row already exists.
INSERT INTO aperture_customers (id, name, email, phone_number, aperture_tenant_id) VALUES ('00000000-0000-0000-0000-000000000001', 'Acme Corp', '6S+jHKEPmkq5QXIHo5D3+wIauYmKzm3jsgjS2X+i4haZFtc5SsTGhNk=', '1234567890', '00000000-0000-0000-0000-000000000001') ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, email = EXCLUDED.email, phone_number = EXCLUDED.phone_number;
INSERT INTO aperture_customers (id, name, email, phone_number, aperture_tenant_id) VALUES ('00000000-0000-0000-0000-000000000003', 'Acme Subsidiary', '6+TSyNIHhcxJNxt7HBLQ7neThKDs+t0HifsWimcuME5xIBosJtcJLWMGXOKBDUY=', '5551234567', '00000000-0000-0000-0000-000000000001') ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, email = EXCLUDED.email, phone_number = EXCLUDED.phone_number;
INSERT INTO aperture_customers (id, name, email, phone_number, aperture_tenant_id) VALUES ('00000000-0000-0000-0000-000000000002', 'Globex Inc', 'Dft52+LmKbfqhoRqN5yUkzbiV9b4Yo3b4v1AiKwd1hHWQ/ZZpgLD0vtR', '0987654321', '00000000-0000-0000-0000-000000000002') ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, email = EXCLUDED.email, phone_number = EXCLUDED.phone_number;

-- Invoices used to prove ABAC independently from RBAC. Both test actors have Viewer.
INSERT INTO aperture_invoices (id, aperture_tenant_id, amount, customer_id, status) VALUES ('60000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 100.00, '00000000-0000-0000-0000-000000000001', 'DRAFT') ON CONFLICT (id) DO NOTHING;
INSERT INTO aperture_invoices (id, aperture_tenant_id, amount, customer_id, status) VALUES ('60000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 200.00, '00000000-0000-0000-0000-000000000001', 'ISSUED') ON CONFLICT (id) DO NOTHING;
