INSERT INTO aperture_countries (id, code, name) VALUES ('30000000-0000-0000-0000-000000000001', 'US', 'United States') ON CONFLICT (id) DO NOTHING;
INSERT INTO aperture_currencies (id, code) VALUES ('40000000-0000-0000-0000-000000000001', 'USD') ON CONFLICT (id) DO NOTHING;
INSERT INTO aperture_suppliers (id, company_name, aperture_tenant_id) VALUES ('50000000-0000-0000-0000-000000000001', 'Acme Supplies', '00000000-0000-0000-0000-000000000001') ON CONFLICT (id) DO NOTHING;
INSERT INTO aperture_suppliers (id, company_name, aperture_tenant_id) VALUES ('50000000-0000-0000-0000-000000000003', 'Northwind Office Supplies', '00000000-0000-0000-0000-000000000001') ON CONFLICT (id) DO NOTHING;
INSERT INTO aperture_suppliers (id, company_name, aperture_tenant_id) VALUES ('50000000-0000-0000-0000-000000000004', 'EU Logistics Partners', '00000000-0000-0000-0000-000000000001') ON CONFLICT (id) DO NOTHING;
INSERT INTO aperture_suppliers (id, company_name, aperture_tenant_id) VALUES ('50000000-0000-0000-0000-000000000005', 'Cloud Components Ltd', '00000000-0000-0000-0000-000000000002') ON CONFLICT (id) DO NOTHING;
