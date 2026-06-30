-- Password hash is for "password" using Argon2id
-- super_admin=true required: schema CHECK enforces (tenant_id IS NULL) → (super_admin = true)
INSERT INTO aperture_users (id, username, password_hash, tenant_id, status, super_admin) VALUES ('10000000-0000-0000-0000-000000000000', 'superadmin@framework.local', '$argon2id$v=19$m=16384,t=2,p=1$vAUAlhjPv3j6+FOJPXB7lg$5JcDsYY6SlCBWdcy8Id51Ew9XWgAtVlGh3jbqqLi5vM', NULL, 'ACTIVE', true) ON CONFLICT (id) DO NOTHING;

-- acme-corp
INSERT INTO aperture_users (id, username, password_hash, tenant_id) VALUES ('10000000-0000-0000-0000-000000000001', 'admin@acme.com', '$argon2id$v=19$m=16384,t=2,p=1$vAUAlhjPv3j6+FOJPXB7lg$5JcDsYY6SlCBWdcy8Id51Ew9XWgAtVlGh3jbqqLi5vM', '00000000-0000-0000-0000-000000000001') ON CONFLICT (id) DO NOTHING;
INSERT INTO aperture_users (id, username, password_hash, tenant_id) VALUES ('10000000-0000-0000-0000-000000000002', 'accountant@acme.com', '$argon2id$v=19$m=16384,t=2,p=1$vAUAlhjPv3j6+FOJPXB7lg$5JcDsYY6SlCBWdcy8Id51Ew9XWgAtVlGh3jbqqLi5vM', '00000000-0000-0000-0000-000000000001') ON CONFLICT (id) DO NOTHING;
INSERT INTO aperture_users (id, username, password_hash, tenant_id) VALUES ('10000000-0000-0000-0000-000000000003', 'viewer@acme.com', '$argon2id$v=19$m=16384,t=2,p=1$vAUAlhjPv3j6+FOJPXB7lg$5JcDsYY6SlCBWdcy8Id51Ew9XWgAtVlGh3jbqqLi5vM', '00000000-0000-0000-0000-000000000001') ON CONFLICT (id) DO NOTHING;

-- globex-ltd
INSERT INTO aperture_users (id, username, password_hash, tenant_id) VALUES ('20000000-0000-0000-0000-000000000001', 'admin@globex.com', '$argon2id$v=19$m=16384,t=2,p=1$vAUAlhjPv3j6+FOJPXB7lg$5JcDsYY6SlCBWdcy8Id51Ew9XWgAtVlGh3jbqqLi5vM', '00000000-0000-0000-0000-000000000002') ON CONFLICT (id) DO NOTHING;
INSERT INTO aperture_users (id, username, password_hash, tenant_id) VALUES ('20000000-0000-0000-0000-000000000002', 'accountant@globex.com', '$argon2id$v=19$m=16384,t=2,p=1$vAUAlhjPv3j6+FOJPXB7lg$5JcDsYY6SlCBWdcy8Id51Ew9XWgAtVlGh3jbqqLi5vM', '00000000-0000-0000-0000-000000000002') ON CONFLICT (id) DO NOTHING;
INSERT INTO aperture_users (id, username, password_hash, tenant_id) VALUES ('20000000-0000-0000-0000-000000000003', 'viewer@globex.com', '$argon2id$v=19$m=16384,t=2,p=1$vAUAlhjPv3j6+FOJPXB7lg$5JcDsYY6SlCBWdcy8Id51Ew9XWgAtVlGh3jbqqLi5vM', '00000000-0000-0000-0000-000000000002') ON CONFLICT (id) DO NOTHING;

-- acme-corp domain roles (TenantAdmin is a platform authority, not a domain role)
INSERT INTO aperture_roles (id, tenant_id, role_name) VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'Accountant') ON CONFLICT (id) DO NOTHING;
INSERT INTO aperture_roles (id, tenant_id, role_name) VALUES ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'Viewer') ON CONFLICT (id) DO NOTHING;

INSERT INTO aperture_user_roles (tenant_id, user_id, role_name) VALUES ('00000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 'Accountant') ON CONFLICT DO NOTHING;
INSERT INTO aperture_user_roles (tenant_id, user_id, role_name) VALUES ('00000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000003', 'Viewer') ON CONFLICT DO NOTHING;

-- Platform authority: TenantAdmin status is stored in aperture_tenant_admins
INSERT INTO aperture_tenant_admins (tenant_id, user_id, created_at) VALUES ('00000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', NOW()) ON CONFLICT DO NOTHING;

-- globex-ltd domain roles (TenantAdmin is a platform authority, not a domain role)
INSERT INTO aperture_roles (id, tenant_id, role_name) VALUES ('00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000002', 'Accountant') ON CONFLICT (id) DO NOTHING;
INSERT INTO aperture_roles (id, tenant_id, role_name) VALUES ('00000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000002', 'Viewer') ON CONFLICT (id) DO NOTHING;

INSERT INTO aperture_user_roles (tenant_id, user_id, role_name) VALUES ('00000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002', 'Accountant') ON CONFLICT DO NOTHING;
INSERT INTO aperture_user_roles (tenant_id, user_id, role_name) VALUES ('00000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000003', 'Viewer') ON CONFLICT DO NOTHING;

-- Platform authority: TenantAdmin status is stored in aperture_tenant_admins
INSERT INTO aperture_tenant_admins (tenant_id, user_id, created_at) VALUES ('00000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', NOW()) ON CONFLICT DO NOTHING;
