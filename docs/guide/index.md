---
title: Introduction
description: Why Aperture exists, what it builds, and who it's for.
---

# Introduction

Every team building an API faces the same argument: how do you structure the endpoints? What filtering syntax do you use? How does pagination work? Which columns go in which tables, and who enforces the schema migration plan?

These debates are expensive. They happen before a line of product logic is written, they happen again when the second developer joins, and they never fully resolve. The bikeshedding is structural because there is no single answer everyone has already agreed to.

Aperture eliminates the debate by eliminating the choice. You write a YAML manifest describing your domain model. Aperture generates a fully-operational API server from it. The API speaks [JSON:API](https://jsonapi.org), an open standard that already specifies filtering, sorting, pagination, sparse fieldsets, and atomic operations. There is nothing left to argue about.

## What is Aperture?

Aperture is a framework for building multi-tenant JSON:API servers from YAML manifests. A domain author writes entity manifests, runs a Maven build, and gets a fully operational REST API with:

- Authentication (JWT, API keys, service accounts)
- Multi-tenancy with database-level isolation
- Role-based and attribute-based access control
- A four-phase lifecycle hook system
- Automated Liquibase schema migrations
- Field encryption and optimistic locking
- Transactional audit trail
- MCP tool stubs for AI assistant integration

No Java is written by hand. No SQL is written by hand. The Maven plugin generates all of it from the manifests on every build.

## Why JSON:API?

The generated API is not just "REST". It implements [JSON:API 1.1](https://jsonapi.org), an open standard with a complete specification for:

- **Filtering:** RSQL query expressions over any attribute or relationship
- **Sorting:** multi-field, directional
- **Pagination:** page-number based with total counts in `meta`
- **Sparse fieldsets:** `fields[type]=attr1,attr2` to reduce payload size
- **Compound documents:** `include=relationship` to fetch related resources in one request
- **Atomic operations:** multiple mutations in a single all-or-nothing request
- **Standardized errors:** `errors` array with `status`, `title`, `detail`

Using a standard means your API clients, SDK authors, and integration partners can use existing tooling. It also means the specification has already settled every API decision.

## What you write vs. what Aperture generates

| You write | Aperture generates |
|---|---|
| Entity YAML manifests | Spring `@Entity` classes |
| Permission maps | Elide `@ReadPermission`, `@CreatePermission`, etc. |
| ABAC policy manifests | SpEL policy check classes |
| Hook URLs in manifests | Hook invocation infrastructure |
| Field declarations | Liquibase `createTable` and `addColumn` changesets |
| Relationship declarations | Tenant-aware foreign key constraints |
| `renamedFrom:` on a field | Liquibase `renameColumn` changeset |
| Nothing | API controllers, auth filters, tenant filters, audit bridge |

Generated code lives in `target/generated-sources/aperture/` and is regenerated on every build. It is never edited by hand.

## The pluggable architecture

Aperture is designed in layers so you can own exactly as much as you need:

**Full reference implementation:** use `aperture-simple-starter` to get JWT auth, in-memory rate limiting, AES-256-GCM field encryption, and JDBC audit out of the box. The billing demo uses this.

**Swap individual pieces:** implement the `CredentialValidator` SPI to replace the JWT auth provider with Keycloak, Okta, or any identity system. Tenancy, hooks, and audit all stay unchanged. The Keycloak demo shows this pattern.

**Bring your own stack:** use only `aperture-core-engine` and `aperture-core-runtime` and wire the rest yourself. The SPI interfaces (`CredentialValidator`, `PrincipalMapper`, `AuditWriter`, `RateLimitProvider`) define all the extension points.

## When to use Aperture

**Good fit:**
- Multi-tenant SaaS APIs where domain modelling is the core work
- Teams who want JSON:API compliance without implementing it from scratch
- Projects that need auth, tenancy, and audit but not a framework that locks all three in together

**Not a fit:**
- APIs that need heavily custom HTTP semantics that deviate from JSON:API
- Non-JVM stacks (Aperture targets Spring Boot / Java 21+)
- Extremely simple single-resource APIs where code generation adds more ceremony than value

## Next steps

- **[Quick Start](/guide/quick-start):** run the billing demo in five minutes
- **[Core Concepts](/guide/core-concepts):** understand manifests, the build pipeline, and lock files
- **[Examples](/examples/billing-demo):** see a full multi-tenant billing API with every feature enabled
