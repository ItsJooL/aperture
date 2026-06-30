-- Local demo-only credentials

-- clinic-a tenant
INSERT INTO aperture_tenants (id, name, status) 
VALUES ('00000000-0000-0000-0000-000000000101', 'Clinic A', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- password hash for "password" using Argon2id
INSERT INTO aperture_users (id, username, password_hash, tenant_id, status) 
VALUES ('10000000-0000-0000-0000-000000000001', 'doctor@example.test', '$argon2id$v=19$m=16384,t=2,p=1$vAUAlhjPv3j6+FOJPXB7lg$5JcDsYY6SlCBWdcy8Id51Ew9XWgAtVlGh3jbqqLi5vM', '00000000-0000-0000-0000-000000000101', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- Doctor role
INSERT INTO aperture_roles (id, tenant_id, role_name) 
VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000101', 'Doctor')
ON CONFLICT (id) DO NOTHING;

-- Role membership
INSERT INTO aperture_user_roles (tenant_id, user_id, role_name) 
VALUES ('00000000-0000-0000-0000-000000000101', '10000000-0000-0000-0000-000000000001', 'Doctor')
ON CONFLICT DO NOTHING;
