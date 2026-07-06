import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Aperture',
  description: 'Build the Business Model. Ship the API.',
  base: process.env.DOCS_BASE_URL ?? '/',

  vite: {
    build: {
      chunkSizeWarningLimit: 1000,
    },
  },

  themeConfig: {
    siteTitle: false,

    nav: [
      { text: 'Guide',     link: '/guide/'                    },
      { text: 'Reference', link: '/reference/manifest-schema' },
      { text: 'Examples',  link: '/examples/billing-demo'     },
    ],

    sidebar: {
      '/guide/': [
        {
          text: 'Guide',
          items: [
            { text: 'Introduction',  link: '/guide/' },
            { text: 'Quick Start',   link: '/guide/quick-start' },
            {
              text: 'Core Concepts',
              link: '/guide/core-concepts',
              items: [
                { text: 'Manifest kinds',     link: '/guide/core-concepts#manifest-kinds' },
                { text: 'Field types',        link: '/guide/core-concepts#field-types' },
                { text: 'Build pipeline',     link: '/guide/core-concepts#the-build-pipeline' },
                { text: 'What gets generated',link: '/guide/core-concepts#what-gets-generated' },
                { text: 'Lock files',         link: '/guide/core-concepts#the-lock-files' },
              ],
            },
            {
              text: 'Manifest Authoring',
              link: '/guide/manifests',
              items: [
                { text: 'Manifest kinds',      link: '/guide/manifests#manifest-kinds' },
                { text: 'Fields & relationships', link: '/guide/manifests#fields-types-and-constraints' },
                { text: 'Tenant scoping',       link: '/guide/manifests#tenant-scoping-tenantscoped' },
                { text: 'scopedBy',             link: '/guide/manifests#scoping-by-relationship-scopedby' },
                { text: 'Permissions & ABAC',   link: '/guide/manifests#permissions-roles-and-abac-policies' },
                { text: 'Lifecycle hooks',      link: '/guide/manifests#lifecycle-hooks' },
                { text: 'MCP & CLI config',     link: '/guide/manifests#mcp-exposure' },
              ],
            },
            {
              text: 'Auth & Identity',
              link: '/guide/auth',
              items: [
                { text: 'Auth endpoints',       link: '/guide/auth#built-in-auth-endpoints' },
                { text: 'JWT login',            link: '/guide/auth#jwt-login' },
                { text: 'Token refresh',        link: '/guide/auth#token-refresh' },
                { text: 'Service accounts',     link: '/guide/auth#service-accounts' },
                { text: 'API keys',             link: '/guide/auth#api-keys' },
                { text: 'Invite flow',          link: '/guide/auth#the-invite-flow' },
                { text: 'Custom auth provider', link: '/guide/auth#the-credentialvalidator-spi-swapping-auth-providers' },
              ],
            },
            {
              text: 'Multi-Tenancy',
              link: '/guide/multi-tenancy',
              items: [
                { text: 'POOL mode',     link: '/guide/multi-tenancy#pool-mode-in-depth' },
                { text: 'NONE mode',     link: '/guide/multi-tenancy#none-mode-in-depth' },
                { text: 'Choosing a mode', link: '/guide/multi-tenancy#choosing-a-mode' },
              ],
            },
            {
              text: 'Hooks & Lifecycle',
              link: '/guide/hooks',
              items: [
                { text: 'Guard hooks',        link: '/guide/hooks#guard-hooks' },
                { text: 'Validation hooks',   link: '/guide/hooks#validation-hooks' },
                { text: 'Mutation hooks',     link: '/guide/hooks#mutation-hooks' },
                { text: 'Trigger hooks',      link: '/guide/hooks#trigger-hooks' },
                { text: 'Hook signing',       link: '/guide/hooks#hook-signing-x-hook-secret' },
                { text: 'Retries & timeouts', link: '/guide/hooks#retries-and-timeouts' },
              ],
            },
            {
              text: 'Security & Audit',
              link: '/guide/security-audit',
              items: [
                { text: 'RBAC',                link: '/guide/security-audit#role-based-access-control-rbac' },
                { text: 'ABAC',                link: '/guide/security-audit#attribute-based-access-control-abac' },
                { text: 'Field encryption',    link: '/guide/security-audit#field-level-encryption' },
                { text: 'Optimistic locking',  link: '/guide/security-audit#optimistic-locking' },
                { text: 'Rate limiting',       link: '/guide/security-audit#rate-limiting' },
                { text: 'Audit trail',         link: '/guide/security-audit#the-audit-trail' },
              ],
            },
            {
              text: 'Build & Deploy',
              link: '/guide/build-deploy',
              items: [
                { text: 'Maven plugin',       link: '/guide/build-deploy#the-maven-plugin' },
                { text: 'Schema automation',  link: '/guide/build-deploy#schema-automation' },
                { text: 'Manual migrations',  link: '/guide/build-deploy#manual-migration-manifests' },
                { text: 'API versioning',     link: '/guide/build-deploy#api-versioning' },
                { text: 'Docker deployment',  link: '/guide/build-deploy#docker-deployment' },
              ],
            },
            {
              text: 'Generated CLI',
              link: '/guide/cli',
              items: [
                { text: 'Enabling the CLI',      link: '/guide/cli#enabling-the-cli' },
                { text: 'Naming the binary',     link: '/guide/cli#naming-the-binary' },
                { text: 'Custom auth',           link: '/guide/cli#custom-auth-extensions' },
                { text: 'Custom commands',       link: '/guide/cli#custom-commands' },
                { text: 'Fat JAR vs native',     link: '/guide/cli#fat-jar-vs-native-binary' },
                { text: 'Getting GraalVM',       link: '/guide/cli#getting-graalvm-for-native-builds' },
                { text: 'System dependencies',   link: '/guide/cli#system-dependencies' },
                { text: 'Platform targeting',    link: '/guide/cli#platform-targeting' },
                { text: 'Declarative apply',     link: '/guide/cli#declarative-apply' },
                { text: 'Config profiles',       link: '/guide/cli#configuration-profiles' },
              ],
            },
          ],
        },
      ],

      '/reference/': [
        {
          text: 'Reference',
          items: [
            {
              text: 'Manifest Schema',
              link: '/reference/manifest-schema',
              items: [
                { text: 'Entity',          link: '/reference/manifest-schema#entity' },
                { text: 'FrameworkConfig', link: '/reference/manifest-schema#frameworkconfig' },
                { text: 'AbacPolicy',      link: '/reference/manifest-schema#abacpolicy' },
                { text: 'RoleDefinition',  link: '/reference/manifest-schema#roledefinition' },
                { text: 'Migration',       link: '/reference/manifest-schema#migration' },
              ],
            },
            {
              text: 'REST API',
              link: '/reference/rest-api',
              items: [
                { text: 'Entity endpoints',       link: '/reference/rest-api#entity-endpoints' },
                { text: 'Filtering (RSQL)',        link: '/reference/rest-api#filtering-rsql' },
                { text: 'Sorting & pagination',   link: '/reference/rest-api#sorting' },
                { text: 'Compound documents',     link: '/reference/rest-api#compound-documents-include' },
                { text: 'Atomic operations',      link: '/reference/rest-api#atomic-operations' },
                { text: 'Optimistic locking',     link: '/reference/rest-api#optimistic-locking-headers' },
                { text: 'Auth & management endpoints',    link: '/reference/rest-api#management-endpoints' },
              ],
            },
            {
              text: 'Configuration',
              link: '/reference/configuration',
              items: [
                { text: 'JWT authentication', link: '/reference/configuration#jwt-authentication-aperture-auth-jwt' },
                { text: 'CORS',               link: '/reference/configuration#cors-aperture-cors' },
                { text: 'Rate limiting',      link: '/reference/configuration#rate-limiting-aperture-rate-limit' },
                { text: 'Field encryption',   link: '/reference/configuration#field-encryption-aperture-encryption-local' },
                { text: 'Hooks',              link: '/reference/configuration#hooks-aperture-hooks' },
                { text: 'GraphQL',            link: '/reference/configuration#graphql-elide-graphql' },
                { text: 'MCP',                link: '/reference/configuration#mcp-aperture-mcp-spring-ai-mcp' },
                { text: 'Bootstrap admin',    link: '/reference/configuration#bootstrap-admin' },
              ],
            },
          ],
        },
      ],

      '/examples/': [
        {
          text: 'Examples',
          items: [
            {
              text: 'Billing Demo',
              link: '/examples/billing-demo',
              items: [
                { text: 'Domain model',        link: '/examples/billing-demo#domain-model' },
                { text: 'Seeded data',         link: '/examples/billing-demo#seeded-data' },
                { text: 'Feature walkthroughs',link: '/examples/billing-demo#feature-walkthroughs' },
              ],
            },
            {
              text: 'Single Tenant',
              link: '/examples/single-tenant',
              items: [
                { text: 'NONE vs POOL',        link: '/examples/single-tenant#what-changes-between-none-and-pool' },
                { text: 'Migrating to POOL',   link: '/examples/single-tenant#migrating-from-none-to-pool' },
              ],
            },
            {
              text: 'MCP Demo',
              link: '/examples/mcp-demo',
              items: [
                { text: 'Running the demo',    link: '/examples/mcp-demo#running-the-demo' },
                { text: 'List MCP tools',      link: '/examples/mcp-demo#list-mcp-tools' },
                { text: 'Call an MCP tool',    link: '/examples/mcp-demo#call-an-mcp-tool' },
              ],
            },
            {
              text: 'Keycloak Integration',
              link: '/examples/keycloak',
              items: [
                { text: 'CredentialValidator SPI',    link: '/examples/keycloak#the-credentialvalidator-spi' },
                { text: 'Disabling simple-auth',      link: '/examples/keycloak#how-spring-wiring-disables-simple-auth' },
                { text: 'Other OIDC providers',       link: '/examples/keycloak#generalising-to-other-providers' },
              ],
            },
          ],
        },
      ],
    },

    search: { provider: 'local' },

    editLink: {
      pattern: 'https://github.com/ItsJooL/aperture/edit/main/docs/:path',
      text: 'Edit this page on GitHub',
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/ItsJooL/aperture' },
    ],
  },
})
