INSERT INTO aperture_tenants (id, name, status) VALUES ('00000000-0000-0000-0000-000000000001', 'acme-corp', 'ACTIVE') ON CONFLICT (id) DO NOTHING;
INSERT INTO aperture_tenants (id, name, status) VALUES ('00000000-0000-0000-0000-000000000002', 'globex-ltd', 'ACTIVE') ON CONFLICT (id) DO NOTHING;
