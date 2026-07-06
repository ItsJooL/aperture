---
title: Generated CLI
description: Aperture can generate a fully-featured command-line client for your API from your manifests.
---

# Generated CLI

Aperture can generate a standalone CLI for your API directly from your manifests. The generated CLI is a self-contained [Picocli](https://picocli.info/) application that knows about every entity, relationship, and auth endpoint in your model — no hand-writing required.

## Enabling the CLI

The CLI generator is **off by default**. Enable it by adding `<cli><enabled>true</enabled></cli>` to the aperture plugin configuration in your `pom.xml`, then bind `exec-maven-plugin` to compile it:

```xml
<plugin>
  <groupId>com.itsjool</groupId>
  <artifactId>aperture-maven-plugin</artifactId>
  <executions>
    <execution><goals><goal>generate</goal></goals></execution>
  </executions>
  <configuration>
    <cli>
      <enabled>true</enabled>
    </cli>
  </configuration>
</plugin>

<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <version>3.5.0</version>
  <executions>
    <execution>
      <id>build-generated-cli</id>
      <phase>test-compile</phase>
      <goals><goal>exec</goal></goals>
      <configuration>
        <executable>/bin/sh</executable>
        <workingDirectory>${project.build.directory}/generated-cli/aperture-cli</workingDirectory>
        <arguments>
          <argument>-c</argument>
          <argument>if command -v native-image &gt;/dev/null 2&gt;&amp;1; then mvn package -Pnative -DskipTests --no-transfer-progress; else echo "[aperture-cli] native-image not found — building fat JAR (install oracle-graalvm for a native binary)" &amp;&amp; mvn package -DskipTests --no-transfer-progress; fi</argument>
        </arguments>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Running `mvn test-compile` (or `mvn package`) generates the CLI project and compiles it. The generated source lands at `target/generated-cli/aperture-cli/`.

## Naming the binary

By default the CLI is named `aperture`. Override it in `manifests/framework/config.yaml`:

```yaml
apiVersion: aperture.itsjool.com/v1
kind: FrameworkConfig
metadata:
  name: config
spec:
  cli:
    binaryName: acme   # your binary name here
```

This name is used for the picocli `@Command(name=...)`, the `version` output, and the native binary filename.

## Custom auth extensions

The generated CLI writes auth commands from `CliAuthExtension` metadata at build time. The extension class must be available on the Maven plugin classpath, then listed under `<cli><extensions>`. Only one auth extension may be configured for a generated CLI.

Auth extensions have two tiers:

| Tier | Use when | Contract |
|---|---|---|
| Paths-only | Your server uses the generated username/password auth shape, but with different endpoint paths | Return `AuthPaths`; the built-in `SimpleAuthCommand` is generated with those paths |
| Full source override | Your login flow is structurally different, such as OIDC device-code login | Return complete Java source from `authCommandSource(binaryName)`; the generator writes it instead of `SimpleAuthCommand` |

Token consumption is intentionally uniform. Every auth flow must persist credentials in the generated profile shape the API client already reads:

```yaml
profiles:
  default:
    auth:
      kind: bearer
      accessToken: eyJ...
      refreshToken: opaque-refresh-token
```

That keeps entity commands, `ApiClient`, env token overrides, and profile handling unchanged across auth providers.

### Paths-only auth

```xml
<plugin>
  <groupId>com.itsjool</groupId>
  <artifactId>aperture-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <dependencies>
    <dependency>
      <groupId>com.itsjool</groupId>
      <artifactId>aperture-simple-cli</artifactId>
      <version>${project.version}</version>
    </dependency>
  </dependencies>
  <configuration>
    <cli>
      <enabled>true</enabled>
      <extensions>
        <extension>com.itsjool.aperture.cli.simple.SimpleAuthCliExtension</extension>
      </extensions>
    </cli>
  </configuration>
</plugin>
```

If no extension is configured, the generator defaults to the simple-auth endpoint paths under `/auth`.

### Full auth command override

For flows that are not username/password POSTs to Aperture, implement the source-emitting tier:

```java
public interface CliAuthExtension {
    String id();
    AuthPaths authPaths();

    default String authCommandSource(String binaryName) { return null; }
    default String authCommandClassName() { return "AuthCommand"; }
}
```

When `authCommandSource()` returns non-null source, the generator writes that source into
`com.itsjool.aperture.cli.cmd`, registers `authCommandClassName()` in `ApertureCli`, and skips
the built-in `SimpleAuthCommand`. The source must declare package `com.itsjool.aperture.cli.cmd`
and provide a Picocli command group named `auth`.

The reusable OIDC implementation lives in `implementations/aperture-cli-auth-oidc`:

```xml
<plugin>
  <groupId>com.itsjool</groupId>
  <artifactId>aperture-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <dependencies>
    <dependency>
      <groupId>com.itsjool</groupId>
      <artifactId>aperture-cli-auth-oidc</artifactId>
      <version>${project.version}</version>
    </dependency>
  </dependencies>
  <configuration>
    <cli>
      <enabled>true</enabled>
      <extensions>
        <extension>com.itsjool.aperture.cli.oidc.OidcDeviceCodeCliExtension</extension>
      </extensions>
    </cli>
  </configuration>
</plugin>
```

It generates `auth login`, `auth refresh`, `auth logout`, and `auth me` for OIDC device authorization:

```bash
apkc auth login \
  --issuer http://localhost:8181/realms/aperture \
  --client-id aperture-cli
```

`login` discovers the issuer, starts the device flow, prints the verification URL and user code,
polls the token endpoint, then stores `auth.kind=bearer`, `accessToken`, `refreshToken`, and
`oidc: { issuer, clientId }` on the active profile. Subsequent `auth refresh` and `auth login`
can reuse the persisted issuer/client id.

For a full worked example — a real Keycloak realm wired to the device grant, a manual
browser walkthrough, and a headless `device-flow-smoke.sh` script that verifies the same flow
in CI — see `demos/aperture-keycloak-cli-demo/README.md`.

## Custom commands

Beyond auth, you can contribute entirely new top-level commands via the `CliCommandContribution` SPI.

The generated CLI is a standalone Maven project that's compiled — and, for native binaries, run through GraalVM `native-image` — long after the aperture plugin has finished running. There's no live JVM at that point to load a runtime plugin object into, and `native-image` can't discover or invoke arbitrary classes reflectively. So `CliCommandContribution` implementations don't hand over a command *object*; they act as **source emitters**: `commandSource(binaryName)` returns the complete Java source of a Picocli `@Command` class, which the generator writes into the generated project's `com.itsjool.aperture.cli.cmd` package and compiles alongside the entity and auth commands. The result is an ordinary generated-project class with no reflection involved — fully native-image friendly.

```java
public interface CliCommandContribution {
    String id();
    String commandClassName();
    String commandSource(String binaryName);
}
```

A single class may implement both `CliAuthExtension` and `CliCommandContribution` if it wants to contribute auth commands and other top-level commands together — the mojo instantiates each configured class once and adds it to whichever list(s) it implements.

Here's a trimmed version of the demo `StatusCommand`, contributed by `SimpleStatusCliContribution` in `aperture-simple-cli`. It hits the public `/actuator/health` endpoint with plain `java.net.http` (no auth headers required) and reports the active profile's login state:

```java
public class SimpleStatusCliContribution implements CliCommandContribution {
    @Override public String id() { return "simple-status"; }
    @Override public String commandClassName() { return "StatusCommand"; }

    @Override
    public String commandSource(String binaryName) {
        return """
            package com.itsjool.aperture.cli.cmd;

            import com.itsjool.aperture.cli.ApertureCli;
            import com.itsjool.aperture.cli.config.ConfigStore;
            import com.itsjool.aperture.cli.config.ProfileConfig;
            import picocli.CommandLine;
            import picocli.CommandLine.Command;

            @Command(name = "status", description = "Show server health and auth status", mixinStandardHelpOptions = true)
            public class StatusCommand implements Runnable {
                @CommandLine.ParentCommand ApertureCli root;

                @Override
                public void run() {
                    var config = ConfigStore.load();
                    ProfileConfig profile = ConfigStore.activeProfile(config, root.global.profile);
                    if (root.global.server != null) profile.server = root.global.server;
                    if (profile.server == null) {
                        System.err.println("No server configured. Use --server or: @BINARY@ config set-server <url>");
                        System.exit(1);
                        return;
                    }
                    // GET <server>/actuator/health with plain java.net.http, then print
                    // server URL, health status, active profile, and auth kind/logged-in state.
                    // ...
                }
            }
            """.replace("@BINARY@", binaryName);
    }
}
```

Register it exactly like a `CliAuthExtension` — as another entry under `<cli><extensions>`:

```xml
<configuration>
  <cli>
    <enabled>true</enabled>
    <extensions>
      <extension>com.itsjool.aperture.cli.simple.SimpleAuthCliExtension</extension>
      <extension>com.itsjool.aperture.cli.simple.SimpleStatusCliContribution</extension>
    </extensions>
  </cli>
</configuration>
```

The contributed command appears as a top-level subcommand alongside the entity and `auth`/`config`/`apply` commands — in the demo app, `aperture status`.

## Fat JAR vs native binary

The build produces two possible artifacts depending on your JDK:

| Artifact | Requires | Path | Cold start |
|---|---|---|---|
| Fat JAR | Any JDK | `target/generated-cli/aperture-cli/target/aperture-cli-0.0.1-SNAPSHOT.jar` | ~1–2 s |
| Native binary | GraalVM (see below) | `target/generated-cli/aperture-cli/target/<binaryName>` | ~30 ms |

The exec-maven-plugin snippet above detects `native-image` at build time: if it's present the native binary is built; otherwise the fat JAR is produced and a clear message is printed. **The build never fails because GraalVM is absent.**

To use the fat JAR:
```bash
java -jar target/generated-cli/aperture-cli/target/aperture-cli-0.0.1-SNAPSHOT.jar --help
```

To use the native binary (after a GraalVM build):
```bash
target/generated-cli/aperture-cli/target/aperture --help
```

## Getting GraalVM for native builds

The project uses [mise](https://mise.jdx.dev) for toolchain management. Switch to Oracle GraalVM — a drop-in replacement for a standard JDK that adds `native-image`:

```toml
# mise.toml
[tools]
java = "oracle-graalvm-25.0.3"   # includes native-image
maven = "3.9.16"
```

Then reinstall:
```bash
mise install
```

`native-image` is bundled with Oracle GraalVM — no separate installation step.

### System dependencies

`native-image` links against system C libraries. Make sure these are installed before building:

::: code-group

```bash [Fedora / RHEL / Rocky]
sudo dnf install gcc zlib-devel
```

```bash [Ubuntu / Debian]
sudo apt install gcc zlib1g-dev
```

```bash [macOS]
# Xcode Command Line Tools cover everything needed
xcode-select --install
```

:::

If the build fails with `collect2: error: ld returned 1 exit status` and the error report says `libz.a is missing`, `zlib-devel` (or `zlib1g-dev`) is the missing package.

To build the native binary manually outside of the Maven lifecycle:
```bash
cd target/generated-cli/aperture-cli
mvn package -Pnative -DskipTests --no-transfer-progress
./target/aperture version
```

## Platform targeting

`native-image` always compiles for the **host platform**:

| Build machine | Binary target |
|---|---|
| Linux x86-64 | Linux ELF binary |
| macOS arm64 | macOS Mach-O (Apple Silicon) |
| macOS x86-64 | macOS Mach-O (Intel) |
| Windows x64 | `aperture.exe` |

To distribute binaries for multiple platforms, build on each target in CI (GitHub Actions matrix, for example). The fat JAR runs anywhere a JVM is present.

## What the CLI generates

The CLI is **verb-first**, kubectl-style: a fixed set of top-level verbs, each with one subcommand per entity (named by its plural resource path). There is no per-entity top-level command group.

```
<binary> get customers [--page N] [--size N] [--filter key=value] [--sort field] [--include rel]
<binary> get customers <id>
<binary> create customers --name "Acme" --email "a@example.com"
<binary> update customers <id> --name "Acme Corp"
<binary> delete customers <id>
```

`get` with no id lists resources; passing an id fetches that one resource. Either way it passes JSON:API query options through to the server:

```bash
aperture get customers --filter status=ACTIVE --sort -createdAt --include orders
```

Filters may be repeated or comma-separated. They are encoded as `filter[key]=value`.

For entities with `optimisticLocking: true`, `update` and `delete` take a `--if-match <etag>` option — the CLI does not fetch the current ETag automatically, so pass the `version` attribute from a prior `get`/`create` call, quoted (e.g. `--if-match '"3"'`). Omitting it on a locked entity returns HTTP 428.

`create` and `update` also accept `-f <file>` as an alternative to typed flags, and `delete` accepts `-f <file> [-y]` — see [Declarative apply](#declarative-apply) below. These `-f` paths share the same file format and lookup machinery as `apply`.

Plus a built-in `auth` command group for token-based login:

```
<binary> auth login --username admin@example.com --password
<binary> auth token --client-id svc-1 --client-secret
<binary> auth me
<binary> auth refresh
<binary> auth logout
```

### API keys

The generated CLI can create, list, and disable personal API keys through the auth provider, then store a raw key on a profile for subsequent API calls:

```bash
aperture auth api-keys create --name smoke-key --non-expiring
aperture auth api-keys create --name smoke-key --expires-at 2027-01-01T00:00:00Z
aperture auth api-keys list
aperture auth api-keys disable <key-id>
aperture config set-api-key <raw-key>
aperture get customers
```

Some tenants disallow non-expiring keys; if `--non-expiring` is rejected, use `--expires-at` instead.

`config set-api-key` stores credentials as `auth.kind=api-key`. Entity and apply commands then send the key as `X-API-Key`.
`config show` reports the active auth kind, but does not print bearer tokens or API key values.

Service-account tokens are available through `auth token`:

```bash
aperture auth token --client-id svc-1 --client-secret s3cret
```

The returned access token is stored on the resolved profile as a bearer token.

## Declarative apply

The generated CLI can create or delete resources from YAML files. This is useful for seed data, demo setup, and repeatable smoke tests.

Top-level YAML keys are plural JSON:API resource paths. Each value is a list of records:

```yaml
customers:
  - name: Initech Corporation
    email: ar@initech.example

products:
  - name: Widget Pro
    sku: WGT-001
    unit_price: 49.99

invoices:
  - amount: 199.97
    status: DRAFT
    customer: Initech Corporation
```

Relationship values can be UUIDs or display values. For display values, the CLI first checks resources created earlier in the same apply run, then falls back to an API lookup using the target entity's natural key.

Records can also set an explicit `_ref:` key — a user-chosen label, valid for any entity, that other records in the same run can reference instead of (or as well as) the natural key:

```yaml
customers:
  - _ref: acme
    name: Initech Corporation
    email: ar@initech.example

invoices:
  - customer: acme   # resolves via _ref, same as "Initech Corporation" would
    amount: 199.97
    status: DRAFT
```

`_ref` is never sent to the server — it's stripped like any other field the entity doesn't declare. It's most useful for entities with no natural key (no unique/name/code/sku/email/company_name/username field), where the only alternative is an internal, order-dependent positional label (see Limitations below).

Common commands:

```bash
aperture apply -f resources/customers.yaml
aperture apply -f resources/
aperture apply -f resources/customers.yaml --dry-run
aperture apply -f resources/customers.yaml --upsert
aperture apply -f resources/ --continue-on-error
aperture apply -f resources/ --atomic

# create/update/delete also accept -f — a resource-less file mode on the same verbs used
# for typed-flag CRUD, sharing apply's file format and lookup machinery (kubectl parity:
# `-f` and a resource subcommand are mutually exclusive on the verb).
aperture create -f resources/customers.yaml
aperture update -f resources/customers.yaml

aperture delete -f resources/customers.yaml
aperture delete -f resources/customers.yaml --yes
aperture delete -f resources/customers.yaml --dry-run
```

Directories are processed alphabetically. If one file depends on another, number the files explicitly, for example `01-customers.yaml`, `02-products.yaml`, `03-invoices.yaml`.

For a worked example of `--atomic` creating multiple new, cross-referencing resources in one call (including what happens when one of them fails validation), see `demos/aperture-demo/resources-atomic/order.yaml`.

`delete -f` processes records in reverse order so child resources are deleted before parent resources. Without `--yes`, it prompts before sending any delete requests.

Unlike the plain `update`/`delete` subcommands with typed flags (which require an explicit `--if-match`), `apply --upsert`, `update -f`, and `delete -f` fetch the current ETag themselves before patching or deleting, so optimistic-locked entities work without any extra flags.

Limitations:

| Behaviour | Notes |
|---|---|
| Dependency ordering | The CLI does not build a dependency graph. Use file order for dependencies. |
| Upsert | `--upsert` (and the default skip-on-conflict path) require the server to return a conflict for duplicate natural keys, which in turn requires the natural-key field to be declared `unique: true` in the manifest. If it isn't, `apply` prints a one-time warning per entity type and applying the same file twice creates duplicates instead of skipping or patching. |
| Atomic apply | `--atomic` batches everything through JSON:API Atomic Operations (`POST {versionPrefix}/operations`). Unlike the default mode, it has no `--upsert`/skip-on-conflict path at all: it always issues `add` operations, so re-running the same file creates duplicate resources rather than erroring or being skipped. Use it for "create N related resources together, all-or-nothing," not for repeated/idempotent syncs. |
| Referencing a not-yet-created resource that has no natural key | Set `_ref:` explicitly (see above) rather than relying on the fallback: entities with no natural key and no `_ref` fall back to an internal positional label (`<entityPath>-<index>`) for same-batch `--atomic` cross-references, which isn't user-facing and shifts if records are reordered. |

## Global options

All commands accept these flags (inherited by every subcommand):

| Flag | Description |
|---|---|
| `--server <url>` | API base URL (overrides profile) |
| `--profile <name>` | Config profile to use |
| `--api-version <v>` | API version prefix (e.g. `1` → `/api/v1/...`) |
| `--tenant <id>` | Tenant context header (SuperAdmin use) |
| `--scope <field>=<value>` | Scope context header for a `scopedBy` entity (repeatable) |
| `--format table\|json` | Output format (default: `table`) |
| `-v, --verbose` | Print HTTP request/response details |

If your manifests declare an `ApiVersionConfig` (`manifests/framework/versions.yaml`), every entity is registered *only* under its real versions — there is no unversioned fallback. You shouldn't normally need `--api-version` at all, though: the generated CLI bakes in the manifest's `ACTIVE` version as `GlobalOptions.DEFAULT_API_VERSION`, used whenever neither `--api-version` nor a profile's pinned version (`config set-api-version`) is set. Use `--api-version`/`config set-api-version` to target a different (e.g. `SUNSET`) version explicitly.

## Scope context (`scopedBy`)

Some entities declare `scopedBy: <field>` in their manifest — a partition key, not a role. **Every operation on a scoped entity is fail-closed on the matching scope context, including `create`** — but the failure *shape* depends on what's being evaluated. A **list** (`get tasks`) with no `X-Aperture-Scope-<Field>` header comes back `200` with an **empty result set** (the server compiles the scope check into a SQL predicate, so nothing matches) — you don't see a 403, you see zero rows. A **single-object** evaluation returns `403`: a fetch by id, and the read-back a `create`/`update` performs to echo the just-written record (so even a bare `create tasks` fails closed with 403 if no scope header is present, though the write itself may already have landed). Distinct from all of that is the **400-on-mismatch** case: if a scope value *is* present in context but the relationship in the request body names a different one, the write is rejected outright with 400 before anything is persisted. So: no scope header → list is empty (200), single-object read/read-back is 403; present but wrong scope value on create/update → 400. `scopedBy` partitions data, it does not authorize access to a partition — any authenticated caller may name any scope value; pair it with an ABAC policy for real per-scope access control (see the manifest guide's `scopedBy` section).

The CLI layers scope context exactly like `--tenant`, kubectl-namespace-style:

```bash
# One-off, for a single command:
aperture get tasks --scope project=<project-id>

# Sticky, for every subsequent command on this profile:
aperture config set-scope project <project-id>
aperture get tasks                                  # now scoped automatically
aperture config unset-scope project
```

`--scope <field>=<value>` is repeatable — a command touching several entities can set a different scope per field in one invocation:

```bash
aperture get tasks --scope project=<project-id> --scope team=<team-id>
```

Precedence: `--scope` overrides a persisted profile scope for the same field; any field not overridden on the command line falls through to what's stored on the profile. Field names are canonicalized to lowercase wherever they're set (`--scope Project=x` and `--scope project=x` collapse to the same entry) — the server also lowercases the header suffix it reads (`X-Aperture-Scope-<Field>`), so casing never matters.

`config show` lists the active profile's configured scopes alongside its tenant:

```bash
aperture config show
# ...
# Tenant:          (none)
# Scopes:          project=8f14e45f-fceb-4dc5-8b1a-4d6c9e6f2b1a
```

Without any scope configured, a request to a scoped entity fails closed — a list comes back empty, a create fails on the read-back:

```bash
aperture get tasks
# (no rows)   HTTP 200 with an empty result set — the scope filter matches nothing

aperture create tasks --title "Ship it" --status TODO --project-id <project-id>
# Error: ...HTTP 403...   (scope header missing on the read-back, even though the write itself was well-formed)

aperture create tasks --title "Ship it" --status TODO --project-id <project-id> --scope project=<project-id>
# succeeds — scope in context matches the project on the record
```

## Shell completion

Generated CLIs include Picocli's completion generator:

```bash
aperture generate-completion > aperture-completion.sh
source aperture-completion.sh
```

Regenerate the script after changing manifests so entity commands and options stay current.

## Configuration profiles

Credentials and server settings are stored in a per-user config file:

| OS | Location |
|---|---|
| Linux | `~/.config/<binaryName>/config.json` |
| macOS | `~/Library/Application Support/<BinaryName>/config.json` |
| Windows | `%APPDATA%\<BinaryName>\config.json` |

The file is plaintext JSON so operators can inspect and repair profiles directly. It is created with `rw-------`
permissions on first login on POSIX file systems, and parent directories are restricted to the owner.

Profile helpers:

```bash
aperture config show
aperture config set-server http://localhost:8080
aperture config set-api-version 1
aperture config set-tenant tenant-a
aperture config set-scope project <project-id>
aperture config unset-scope project
aperture config set-api-key <raw-key>
aperture config use staging
aperture config list-profiles
aperture config delete-profile staging
```

`config delete-profile` refuses to delete the active profile; switch profiles first with `config use`.

You can also use environment variables to override the config. The prefix is derived from the generated binary
name by uppercasing it and replacing non-alphanumeric characters with underscores. The default `aperture`
binary therefore uses:

| Variable | Purpose |
|---|---|
| `APERTURE_CONFIG` | Absolute path to the config file |
| `APERTURE_SERVER` | Server URL |
| `APERTURE_TOKEN` | Bearer token (overrides stored credentials) |
| `APERTURE_API_KEY` | API key |
| `APERTURE_TENANT` | Tenant context |
