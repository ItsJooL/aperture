package com.itsjool.aperture.cli.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itsjool.aperture.cli.spi.CliCommandContribution;
import com.itsjool.aperture.engine.config.TenancyMode;
import com.itsjool.aperture.engine.model.CliConfig;
import com.itsjool.aperture.engine.model.EntityDef;
import com.itsjool.aperture.engine.model.FieldDef;
import com.itsjool.aperture.engine.model.FrameworkConfigDef;
import com.itsjool.aperture.engine.model.ResolvedDomainModel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedCliRunTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    static Path workDir;

    static HttpServer server;
    static GeneratedCliHarness cli;
    static Map<String, String> requestBodies = new ConcurrentHashMap<>();
    static Map<String, String> requestHeaders = new ConcurrentHashMap<>();

    @BeforeAll
    static void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/auth/login", exchange -> respond(exchange, 200,
            "{\"accessToken\":\"tok-abc\",\"refreshToken\":\"ref-abc\"}"));
        server.createContext("/auth/token", exchange -> {
            String body = readBody(exchange);
            requestBodies.put("TOKEN", body);
            if (body.contains("bad-secret")) {
                respond(exchange, 401, "{\"message\":\"bad credentials\"}");
            } else {
                respond(exchange, 200, "{\"clientId\":\"svc-1\",\"accessToken\":\"svc-tok\"}");
            }
        });
        server.createContext("/auth/me/api-keys", exchange -> {
            String body = readBody(exchange);
            requestBodies.put("API_KEYS_" + exchange.getRequestMethod(), body);
            if ("POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 201, """
                    {"record":{"id":"key-1","tenantId":"tenant-a","userId":"user-1","status":"ACTIVE","createdAt":"2026-07-03T08:00:00Z","expiresAt":null,"lastUsedAt":null},"secret":"apk-secret"}
                    """);
            } else {
                respond(exchange, 200, """
                    [{"id":"key-1","tenantId":"tenant-a","userId":"user-1","status":"ACTIVE","createdAt":"2026-07-03T08:00:00Z","expiresAt":null,"lastUsedAt":null}]
                    """);
            }
        });
        server.createContext("/auth/me/api-keys/missing/disable", exchange ->
            respond(exchange, 404, "{\"message\":\"not found\"}"));
        server.createContext("/api/v1/customers", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                requestBodies.put("GET_PATH", exchange.getRequestURI().toString());
            }
            requestBodies.put(exchange.getRequestMethod(), readBody(exchange));
            String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
            if (apiKey != null) {
                requestHeaders.put(exchange.getRequestMethod() + "_X_API_KEY", apiKey);
            }
            String scopeProject = exchange.getRequestHeaders().getFirst("X-Aperture-Scope-project");
            if (scopeProject != null) {
                requestHeaders.put(exchange.getRequestMethod() + "_SCOPE_PROJECT", scopeProject);
            }
            String scopeTeam = exchange.getRequestHeaders().getFirst("X-Aperture-Scope-team");
            if (scopeTeam != null) {
                requestHeaders.put(exchange.getRequestMethod() + "_SCOPE_TEAM", scopeTeam);
            }
            respond(exchange, "POST".equals(exchange.getRequestMethod()) ? 201 : 200,
                "{\"data\":{\"type\":\"customers\",\"id\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"attributes\":{\"name\":\"Acme\",\"email\":\"a@b.c\"}}}");
        });
        server.createContext("/api/v1/orders", exchange -> {
            requestBodies.put("ORDERS_" + exchange.getRequestMethod(), readBody(exchange));
            respond(exchange, "POST".equals(exchange.getRequestMethod()) ? 201 : 200,
                "{\"data\":{\"type\":\"orders\",\"id\":\"33333333-3333-3333-3333-333333333333\",\"attributes\":{}}}");
        });
        server.createContext("/api/v1/operations", exchange -> {
            String body = readBody(exchange);
            requestBodies.put("OPERATIONS_" + exchange.getRequestMethod(), body);
            int opCount = MAPPER.readTree(body).get("atomic:operations").size();
            var results = MAPPER.createArrayNode();
            for (int i = 0; i < opCount; i++) {
                var result = MAPPER.createObjectNode();
                result.putObject("data").put("type", "x").put("id", "22222222-2222-2222-2222-" + String.format("%012d", i));
                results.add(result);
            }
            respond(exchange, 200, MAPPER.createObjectNode().set("atomic:results", results).toString());
        });
        server.start();
        cli = GeneratedCliHarness.generateAndCompile(demoModel(), workDir);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void rootHelpListsAllCommands() throws Exception {
        var result = cli.run("--help");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout())
            .contains("Get or list resources")
            .contains("Create resources")
            .contains("Update resources")
            .contains("Delete resources")
            .contains("apply")
            .contains("auth")
            .contains("generate-completion");
    }

    @Test
    void nounFirstCommandGroupsAreRemoved() throws Exception {
        // Clean break: the old noun-first surface (`aperture customers get <id>`) is gone —
        // "customers" is not a top-level command anymore, only a subcommand of the verbs.
        var result = cli.run("customers");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).containsIgnoringCase("unmatched argument");
    }

    @Test
    void generateCompletionPrintsShellScript() throws Exception {
        var result = cli.run("generate-completion");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout())
            .contains("aperture")
            .contains("customers")
            .contains("config");
    }

    @Test
    void createHelpDoesNotFailOnMissingRequiredOptions() throws Exception {
        var result = cli.run("create", "customers", "--help");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("--name");
        assertThat(result.stderr()).doesNotContain("Missing required");
    }

    @Test
    void createSendsJsonApiBody() throws Exception {
        requestBodies.clear();

        var result = cli.run("--server", serverUrl(), "create", "customers",
            "--name", "Acme", "--email", "a@b.c", "--format", "json");

        assertThat(result.exitCode()).isZero();
        var body = MAPPER.readTree(requestBodies.get("POST"));
        assertThat(body.at("/data/type").asText()).isEqualTo("customers");
        assertThat(body.at("/data/attributes/name").asText()).isEqualTo("Acme");
        assertThat(body.at("/data/attributes/email").asText()).isEqualTo("a@b.c");
    }

    @Test
    void loginWithProfileOverrideWritesToThatProfile() throws Exception {
        var login = cli.run("--server", serverUrl(), "--profile", "staging",
            "auth", "login", "--username", "u", "--password", "p");
        assertThat(login.exitCode()).isZero();

        var staging = cli.run("--profile", "staging", "config", "show");
        assertThat(staging.exitCode()).isZero();
        assertThat(staging.stdout())
            .contains("Authenticated:   yes")
            .contains("(as u)");

        var defaultProfile = cli.run("config", "show");
        assertThat(defaultProfile.exitCode()).isZero();
        assertThat(defaultProfile.stdout()).contains("Authenticated:   no");
    }

    @Test
    void serviceAccountTokenStoresBearerOnProfile() throws Exception {
        var result = cli.run("--server", serverUrl(), "--profile", "svc",
            "auth", "token", "--client-id", "svc-1", "--client-secret", "s3cret");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("Service-account token stored for profile 'svc'");
        assertThat(requestBodies.get("TOKEN")).contains("svc-1").contains("s3cret");

        var profile = cli.run("--profile", "svc", "config", "show");
        assertThat(profile.exitCode()).isZero();
        assertThat(profile.stdout()).contains("Authenticated:   yes");
    }

    @Test
    void serviceAccountTokenFailsCleanlyOn401() throws Exception {
        var result = cli.run("--server", serverUrl(), "--profile", "svc-fail",
            "auth", "token", "--client-id", "svc-1", "--client-secret", "bad-secret");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("401");

        var profile = cli.run("--profile", "svc-fail", "config", "show");
        assertThat(profile.exitCode()).isZero();
        assertThat(profile.stdout()).contains("Authenticated:   no");
    }

    @Test
    void apiKeysCreatePostsNameAndPrintsSecret() throws Exception {
        requestBodies.clear();

        var result = cli.run("--server", serverUrl(), "auth", "api-keys", "create",
            "--name", "smoke-key", "--non-expiring");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout())
            .contains("apk-secret")
            .contains("not shown again");
        var body = MAPPER.readTree(requestBodies.get("API_KEYS_POST"));
        assertThat(body.path("name").asText()).isEqualTo("smoke-key");
        assertThat(body.path("nonExpiring").asBoolean()).isTrue();
    }

    @Test
    void apiKeysListRendersTable() throws Exception {
        var result = cli.run("--server", serverUrl(), "auth", "api-keys", "list");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout())
            .contains("key-1")
            .contains("ACTIVE");
    }

    @Test
    void apiKeysDisableHits404Cleanly() throws Exception {
        var result = cli.run("--server", serverUrl(), "auth", "api-keys", "disable", "missing");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("404");
    }

    @Test
    void setApiKeyThenEntityCallSendsXApiKeyHeader() throws Exception {
        requestHeaders.clear();

        var set = cli.run("--profile", "api-key-profile", "config", "set-api-key", "apk-secret");
        assertThat(set.exitCode()).isZero();

        var list = cli.run("--server", serverUrl(), "--profile", "api-key-profile", "get", "customers");
        assertThat(list.exitCode()).isZero();
        assertThat(requestHeaders.get("GET_X_API_KEY")).isEqualTo("apk-secret");
    }

    @Test
    void listPassesFilterSortAndIncludeQueryOptions() throws Exception {
        requestBodies.clear();

        var result = cli.run("--server", serverUrl(), "get", "customers",
            "--page", "2", "--size", "5",
            "--filter", "name=Acme & Sons",
            "--sort", "-createdAt,name",
            "--include", "orders,owner");

        assertThat(result.exitCode()).isZero();
        assertThat(requestBodies.get("GET_PATH"))
            .contains("/api/v1/customers?")
            .contains("page%5Bnumber%5D=2")
            .contains("page%5Bsize%5D=5")
            .contains("filter%5Bname%5D=Acme+%26+Sons")
            .contains("sort=-createdAt%2Cname")
            .contains("include=orders%2Cowner");
    }

    @Test
    void credentialsStoredPlaintextWithOwnerOnlyPermissions() throws Exception {
        var login = cli.run("--server", serverUrl(), "--profile", "plain",
            "auth", "login", "--username", "plain-user", "--password", "p");
        assertThat(login.exitCode()).isZero();

        Path configFile = cli.homeDir().resolve(".config/aperture/config.json");
        String config = Files.readString(configFile);
        assertThat(config)
            .contains("tok-abc")
            .contains("plain-user")
            .doesNotContain("\"data\"")
            .doesNotContain("\"v\"");
        assertThat(cli.generatedSource("config/ConfigStore.java"))
            .doesNotContain("ENC_KEY")
            .doesNotContain("Cipher")
            .doesNotContain("encryptConfig");
        assertThat(Files.getPosixFilePermissions(configFile))
            .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void configEnvVarOverridesConfigPath(@TempDir Path dir) throws Exception {
        GeneratedCliHarness isolatedCli = GeneratedCliHarness.generateAndCompile(demoModel(), dir);
        Path customConfig = dir.resolve("custom-config.json");

        var login = isolatedCli.run(Map.of("APERTURE_CONFIG", customConfig.toString()),
            "--server", serverUrl(), "auth", "login", "--username", "env-user", "--password", "p");

        assertThat(login.exitCode()).isZero();
        assertThat(Files.readString(customConfig)).contains("env-user");
        assertThat(Files.exists(isolatedCli.homeDir().resolve(".config/aperture/config.json"))).isFalse();
    }

    @Test
    void setTenantWritesToResolvedProfile() throws Exception {
        var set = cli.run("--profile", "tenant-profile", "config", "set-tenant", "tenant-a");
        assertThat(set.exitCode()).isZero();

        var show = cli.run("--profile", "tenant-profile", "config", "show");
        assertThat(show.exitCode()).isZero();
        assertThat(show.stdout()).contains("Tenant:          tenant-a");
    }

    @Test
    void scopeFlagSendsScopeHeaderOnRequest() throws Exception {
        requestHeaders.clear();

        var result = cli.run("--server", serverUrl(), "--scope", "project=proj-1", "get", "customers");

        assertThat(result.exitCode()).isZero();
        assertThat(requestHeaders.get("GET_SCOPE_PROJECT")).isEqualTo("proj-1");
    }

    @Test
    void scopeFlagFieldNameIsLowercasedInHeader() throws Exception {
        requestHeaders.clear();

        var result = cli.run("--server", serverUrl(), "--scope", "Project=proj-2", "get", "customers");

        assertThat(result.exitCode()).isZero();
        assertThat(requestHeaders.get("GET_SCOPE_PROJECT")).isEqualTo("proj-2");
    }

    @Test
    void setScopeThenBareCommandSendsPersistedScopeHeader() throws Exception {
        requestHeaders.clear();

        var set = cli.run("--profile", "scope-profile", "config", "set-scope", "project", "proj-sticky");
        assertThat(set.exitCode()).isZero();

        var result = cli.run("--server", serverUrl(), "--profile", "scope-profile", "get", "customers");
        assertThat(result.exitCode()).isZero();
        assertThat(requestHeaders.get("GET_SCOPE_PROJECT")).isEqualTo("proj-sticky");
    }

    @Test
    void scopeFlagOverridesPersistedProfileScopeForSameField() throws Exception {
        requestHeaders.clear();

        var set = cli.run("--profile", "scope-override", "config", "set-scope", "project", "proj-old");
        assertThat(set.exitCode()).isZero();

        var result = cli.run("--server", serverUrl(), "--profile", "scope-override",
            "--scope", "project=proj-new", "get", "customers");
        assertThat(result.exitCode()).isZero();
        assertThat(requestHeaders.get("GET_SCOPE_PROJECT")).isEqualTo("proj-new");
    }

    @Test
    void multipleScopeFlagsForDifferentFieldsAreAllSent() throws Exception {
        requestHeaders.clear();

        var result = cli.run("--server", serverUrl(),
            "--scope", "project=proj-1", "--scope", "team=team-9", "get", "customers");

        assertThat(result.exitCode()).isZero();
        assertThat(requestHeaders.get("GET_SCOPE_PROJECT")).isEqualTo("proj-1");
        assertThat(requestHeaders.get("GET_SCOPE_TEAM")).isEqualTo("team-9");
    }

    @Test
    void createEntityVerbCommandWithScopeSendsScopeHeaderOnRequest() throws Exception {
        // Entity-verb path (create customers ...), as opposed to the -f file-mode path exercised
        // below — both must apply the same --scope override before building the ApiClient.
        requestHeaders.clear();

        var result = cli.run("--server", serverUrl(), "--scope", "project=proj-create",
            "create", "customers", "--name", "Acme", "--email", "a@b.c");

        assertThat(result.exitCode()).isZero();
        assertThat(requestHeaders.get("POST_SCOPE_PROJECT")).isEqualTo("proj-create");
    }

    @Test
    void createFileModeWithScopeSendsScopeHeaderOnRequest(@TempDir Path dir) throws Exception {
        // The `create -f` path builds its ApiClient in CreateCommand.runFileMode() — a separate
        // code path from the entity-verb subcommand above — which must apply --scope too.
        requestHeaders.clear();
        Path file = dir.resolve("seed.yaml");
        Files.writeString(file, """
            customers:
              - name: Acme
                email: a@b.c
            """);

        var result = cli.run("--server", serverUrl(), "--scope", "project=proj-file",
            "create", "-f", file.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(requestHeaders.get("POST_SCOPE_PROJECT")).isEqualTo("proj-file");
    }

    @Test
    void applyFileModeWithScopeSendsScopeHeaderOnRequest(@TempDir Path dir) throws Exception {
        // ApplyCommand builds its ApiClient in its own generated run() method (CliTemplates),
        // a third code path distinct from both above — must also apply --scope.
        requestHeaders.clear();
        Path file = dir.resolve("seed.yaml");
        Files.writeString(file, """
            customers:
              - name: Acme
                email: a@b.c
            """);

        var result = cli.run("--server", serverUrl(), "--scope", "project=proj-apply",
            "apply", "-f", file.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(requestHeaders.get("POST_SCOPE_PROJECT")).isEqualTo("proj-apply");
    }

    @Test
    void configSetScopeShowUnsetScopeRoundTrip() throws Exception {
        var set = cli.run("--profile", "scope-roundtrip", "config", "set-scope", "Project", "proj-7");
        assertThat(set.exitCode()).isZero();
        assertThat(set.stdout()).contains("Scope 'project' set to proj-7 for profile 'scope-roundtrip'.");

        var show = cli.run("--profile", "scope-roundtrip", "config", "show");
        assertThat(show.exitCode()).isZero();
        assertThat(show.stdout()).contains("Scopes:          project=proj-7");

        var unset = cli.run("--profile", "scope-roundtrip", "config", "unset-scope", "project");
        assertThat(unset.exitCode()).isZero();

        var showAfter = cli.run("--profile", "scope-roundtrip", "config", "show");
        assertThat(showAfter.exitCode()).isZero();
        assertThat(showAfter.stdout()).contains("Scopes:          (none)");
    }

    @Test
    void deleteProfileRefusesActiveProfile() throws Exception {
        var use = cli.run("config", "use", "active-delete");
        assertThat(use.exitCode()).isZero();

        var result = cli.run("config", "delete-profile", "active-delete");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("Cannot delete active profile 'active-delete'");
    }

    @Test
    void showDisplaysTenantAndAuthKindWithoutTokenValues() throws Exception {
        var setTenant = cli.run("--profile", "show-kind", "config", "set-tenant", "tenant-a");
        assertThat(setTenant.exitCode()).isZero();
        var setKey = cli.run("--profile", "show-kind", "config", "set-api-key", "apk-secret");
        assertThat(setKey.exitCode()).isZero();

        var show = cli.run("--profile", "show-kind", "config", "show");

        assertThat(show.exitCode()).isZero();
        assertThat(show.stdout())
            .contains("Tenant:          tenant-a")
            .contains("Auth:            api-key")
            .doesNotContain("apk-secret");
    }

    @Test
    void applyCreatesResourcesFromYamlFile(@TempDir Path dir) throws Exception {
        requestBodies.clear();
        Path file = dir.resolve("seed.yaml");
        Files.writeString(file, """
            customers:
              - name: Acme
                email: a@b.c
            """);

        var result = cli.run("--server", serverUrl(), "apply", "-f", file.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("Applied 1 resource(s)");
        assertThat(requestBodies).containsKey("POST");
    }

    @Test
    void applyDryRunMakesNoHttpCalls(@TempDir Path dir) throws Exception {
        requestBodies.clear();
        Path file = dir.resolve("seed.yaml");
        Files.writeString(file, """
            customers:
              - name: Acme
                email: a@b.c
            """);

        var result = cli.run("--server", serverUrl(), "apply", "-f", file.toString(), "--dry-run");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("[DRY-RUN] would create customers[0]");
        assertThat(requestBodies).isEmpty();
    }

    @Test
    void atomicApplyLinksResourcesByNaturalKeyInOneBatch(@TempDir Path dir) throws Exception {
        requestBodies.clear();
        Path file = dir.resolve("seed.yaml");
        Files.writeString(file, """
            customers:
              - name: Acme
                email: a@b.c
            orders:
              - customer: Acme
                label: First order
            """);

        var result = cli.run("--server", serverUrl(), "apply", "-f", file.toString(), "--atomic");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("Applied 2 resource(s) atomically");
        var body = MAPPER.readTree(requestBodies.get("OPERATIONS_POST"));
        var ops = body.get("atomic:operations");
        assertThat(ops).hasSize(2);
        var orderOp = ops.get(1).get("data");
        assertThat(orderOp.get("type").asText()).isEqualTo("orders");
        // The order references "Acme" (the customer's natural key), resolved in-batch via
        // lid rather than a live lookup — no /api/v1/customers GET should have occurred for it.
        assertThat(orderOp.at("/relationships/customer/data/lid").asText())
            .isEqualTo(ops.get(0).get("data").get("lid").asText());
    }

    @Test
    void atomicApplyLinksResourcesByUserAssignedRef(@TempDir Path dir) throws Exception {
        requestBodies.clear();
        Path file = dir.resolve("seed.yaml");
        Files.writeString(file, """
            customers:
              - _ref: main-customer
                name: Acme
                email: a@b.c
            orders:
              - customer: main-customer
                label: First order
            """);

        var result = cli.run("--server", serverUrl(), "apply", "-f", file.toString(), "--atomic");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("Applied 2 resource(s) atomically");
        var body = MAPPER.readTree(requestBodies.get("OPERATIONS_POST"));
        var ops = body.get("atomic:operations");
        var orderOp = ops.get(1).get("data");
        // "main-customer" is not Customer's natural key ("name") — this only resolves if the
        // CLI's lid cache is keyed by the user-assigned _ref, not just the natural key.
        assertThat(orderOp.at("/relationships/customer/data/lid").asText())
            .isEqualTo(ops.get(0).get("data").get("lid").asText());
    }

    @Test
    void applyWarnsOnceWhenNaturalKeyIsNotDeclaredUnique(@TempDir Path dir) throws Exception {
        requestBodies.clear();
        Path file = dir.resolve("seed.yaml");
        Files.writeString(file, """
            orders:
              - name: First order
                label: A
              - name: Second order
                label: B
            """);

        var result = cli.run("--server", serverUrl(), "apply", "-f", file.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("Applied 2 resource(s)");
        long warnCount = result.stderr().lines()
            .filter(line -> line.contains("orders' natural key 'name' is not declared unique"))
            .count();
        assertThat(warnCount).isEqualTo(1); // once per entity type, not once per record
    }

    @Test
    void applyDoesNotWarnWhenNaturalKeyIsUnique(@TempDir Path dir) throws Exception {
        requestBodies.clear();
        Path file = dir.resolve("seed.yaml");
        Files.writeString(file, """
            customers:
              - name: Acme
                email: a@b.c
            """);

        var result = cli.run("--server", serverUrl(), "apply", "-f", file.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).doesNotContain("not declared unique");
    }

    @Test
    void deleteFileAbortsWithoutConfirmation(@TempDir Path dir) throws Exception {
        requestBodies.clear();
        Path file = dir.resolve("seed.yaml");
        Files.writeString(file, """
            customers:
              - name: Acme
                email: a@b.c
            """);

        var result = cli.run("--server", serverUrl(), "delete", "-f", file.toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("Aborted.");
        assertThat(requestBodies).isEmpty();
    }

    @Test
    void unresolvableRelationshipSkipsEntireRecordAndCountsFailedOnce(@TempDir Path dir) throws Exception {
        requestBodies.clear();
        Path file = dir.resolve("seed.yaml");
        Files.writeString(file, """
            orders:
              - customer: NoSuchCustomer
                label: First order
            """);

        var result = cli.run("--server", serverUrl(), "apply", "-f", file.toString(), "--continue-on-error");

        assertThat(result.exitCode()).isZero();
        // The relationship lookup fails (mock /customers GET never returns a matching array
        // entry), so the whole record must be skipped rather than POSTed without the
        // relationship — and it must be counted as failed exactly once (not also skipped).
        assertThat(requestBodies).doesNotContainKey("ORDERS_POST");
        assertThat(result.stderr()).contains("cannot resolve relationship");
        assertThat(result.stdout()).contains("Applied 0 resource(s)  skipped=0  failed=1");
    }

    @Test
    void deleteFileEscapesSingleQuoteInNaturalKeyLookup(@TempDir Path dir) throws Exception {
        requestBodies.clear();
        Path file = dir.resolve("seed.yaml");
        Files.writeString(file, """
            customers:
              - name: O'Brien Ltd
            """);

        var result = cli.run("--server", serverUrl(), "delete", "-f", file.toString(), "--yes");

        assertThat(result.exitCode()).isZero();
        String getPath = requestBodies.get("GET_PATH");
        assertThat(getPath).isNotNull();
        String decoded = java.net.URLDecoder.decode(getPath, StandardCharsets.UTF_8);
        // RSQL requires the embedded quote to be backslash-escaped so the filter stays
        // well-formed instead of breaking out of the quoted value.
        assertThat(decoded).contains("name=='O\\'Brien Ltd'");
    }

    @Test
    void upsertLooksUpByNaturalKeyAndReusesLookupForEtag(@TempDir Path dir) throws Exception {
        HttpServer localServer = HttpServer.create(new InetSocketAddress(0), 0);
        java.util.concurrent.atomic.AtomicInteger getCount = new java.util.concurrent.atomic.AtomicInteger();
        Map<String, String> localBodies = new ConcurrentHashMap<>();
        localServer.createContext("/api/v1/customers", exchange -> {
            String method = exchange.getRequestMethod();
            if ("POST".equals(method)) {
                respond(exchange, 409, "{\"message\":\"already exists\"}");
            } else if ("GET".equals(method)) {
                getCount.incrementAndGet();
                localBodies.put("GET_PATH", exchange.getRequestURI().toString());
                respond(exchange, 200,
                    "{\"data\":[{\"type\":\"customers\",\"id\":\"22222222-2222-2222-2222-222222222222\","
                        + "\"attributes\":{\"name\":\"Acme\",\"version\":3}}]}");
            } else if ("PATCH".equals(method)) {
                localBodies.put("PATCH", readBody(exchange));
                localBodies.put("PATCH_IF_MATCH", exchange.getRequestHeaders().getFirst("If-Match"));
                respond(exchange, 200, "{}");
            }
        });
        localServer.start();
        try {
            GeneratedCliHarness localCli = GeneratedCliHarness.generateAndCompile(demoModel(), dir);
            Path file = dir.resolve("seed.yaml");
            Files.writeString(file, """
                customers:
                  - _ref: main-customer
                    name: Acme
                    email: a@b.c
                """);

            var result = localCli.run("--server", "http://127.0.0.1:" + localServer.getAddress().getPort(),
                "apply", "-f", file.toString(), "--upsert");

            assertThat(result.exitCode()).isZero();
            // The lookup must use the natural key ("Acme"), not the user-assigned _ref
            // ("main-customer"), which the server has no field to filter on.
            String decodedGet = java.net.URLDecoder.decode(localBodies.get("GET_PATH"), StandardCharsets.UTF_8);
            assertThat(decodedGet).contains("name=='Acme'");
            assertThat(localBodies.get("PATCH")).contains("22222222-2222-2222-2222-222222222222");
            // The etag from the natural-key lookup is reused directly — no extra GET for it.
            assertThat(localBodies.get("PATCH_IF_MATCH")).isEqualTo("\"3\"");
            assertThat(getCount.get()).isEqualTo(1);
        } finally {
            localServer.stop(0);
        }
    }

    @Test
    void deleteFileNaturalKeyPathReusesLookupForEtagInsteadOfASecondGet(@TempDir Path dir) throws Exception {
        HttpServer localServer = HttpServer.create(new InetSocketAddress(0), 0);
        java.util.concurrent.atomic.AtomicInteger getCount = new java.util.concurrent.atomic.AtomicInteger();
        Map<String, String> localBodies = new ConcurrentHashMap<>();
        localServer.createContext("/api/v1/customers", exchange -> {
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                getCount.incrementAndGet();
                respond(exchange, 200,
                    "{\"data\":[{\"type\":\"customers\",\"id\":\"33333333-3333-3333-3333-333333333333\","
                        + "\"attributes\":{\"name\":\"Acme\",\"version\":5}}]}");
            } else if ("DELETE".equals(method)) {
                localBodies.put("DELETE_IF_MATCH", exchange.getRequestHeaders().getFirst("If-Match"));
                respond(exchange, 200, "{}");
            }
        });
        localServer.start();
        try {
            GeneratedCliHarness localCli = GeneratedCliHarness.generateAndCompile(demoModel(), dir);
            Path file = dir.resolve("seed.yaml");
            Files.writeString(file, """
                customers:
                  - name: Acme
                """);

            var result = localCli.run("--server", "http://127.0.0.1:" + localServer.getAddress().getPort(),
                "delete", "-f", file.toString(), "--yes");

            assertThat(result.exitCode()).isZero();
            // One lookup resolves both the id and its version; no separate fetchEtag GET.
            assertThat(getCount.get()).isEqualTo(1);
            assertThat(localBodies.get("DELETE_IF_MATCH")).isEqualTo("\"5\"");
        } finally {
            localServer.stop(0);
        }
    }

    @Test
    void resolveRelIdCachesLookupAcrossRecordsInSameRun(@TempDir Path dir) throws Exception {
        HttpServer localServer = HttpServer.create(new InetSocketAddress(0), 0);
        java.util.concurrent.atomic.AtomicInteger getCount = new java.util.concurrent.atomic.AtomicInteger();
        localServer.createContext("/api/v1/customers", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                getCount.incrementAndGet();
                respond(exchange, 200,
                    "{\"data\":[{\"type\":\"customers\",\"id\":\"44444444-4444-4444-4444-444444444444\","
                        + "\"attributes\":{\"name\":\"Acme\"}}]}");
            }
        });
        localServer.createContext("/api/v1/orders", exchange ->
            respond(exchange, 201,
                "{\"data\":{\"type\":\"orders\",\"id\":\"55555555-5555-5555-5555-555555555555\",\"attributes\":{}}}"));
        localServer.start();
        try {
            GeneratedCliHarness localCli = GeneratedCliHarness.generateAndCompile(demoModel(), dir);
            Path file = dir.resolve("seed.yaml");
            Files.writeString(file, """
                orders:
                  - customer: Acme
                    label: First order
                  - customer: Acme
                    label: Second order
                  - customer: Acme
                    label: Third order
                """);

            var result = localCli.run("--server", "http://127.0.0.1:" + localServer.getAddress().getPort(),
                "apply", "-f", file.toString());

            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).contains("Applied 3 resource(s)");
            // All three records reference the same customer by natural key — the cache populated
            // by resolveRelId's first lookup must be reused, costing one HTTP GET, not three.
            assertThat(getCount.get()).isEqualTo(1);
        } finally {
            localServer.stop(0);
        }
    }

    @Test
    void logoutWarnsUsingBinarySpecificEnvVarName(@TempDir Path dir) throws Exception {
        GeneratedCliHarness myCli = GeneratedCliHarness.generateAndCompile(modelWithBinaryName("mycli"), dir);

        var result = myCli.run(Map.of("MYCLI_TOKEN", "some-token"), "auth", "logout");

        assertThat(result.exitCode()).isZero();
        // The warning must reference the binary-specific env var (matching ConfigStore's
        // envPrefix derivation), not a hardcoded "APERTURE_TOKEN" that only applies to the
        // default binary name.
        assertThat(result.stderr()).contains("MYCLI_TOKEN environment variable is set");
        assertThat(result.stderr()).doesNotContain("APERTURE_TOKEN");
    }

    @Test
    void commandContributionIsCompiledAndRunnableAsASubcommand(@TempDir Path dir) throws Exception {
        CliCommandContribution stub = new CliCommandContribution() {
            @Override public String id() { return "stub-status"; }
            @Override public String commandClassName() { return "StubStatusCommand"; }
            @Override public String commandSource(String binaryName) {
                return """
                    package com.itsjool.aperture.cli.cmd;

                    import picocli.CommandLine.Command;

                    @Command(name = "stub-status", description = "stub command from a CliCommandContribution")
                    public class StubStatusCommand implements Runnable {
                        @Override public void run() {
                            System.out.println("STUB-STATUS-MARKER for @BINARY@");
                        }
                    }
                    """.replace("@BINARY@", binaryName);
            }
        };
        GeneratedCliHarness contributedCli = GeneratedCliHarness.generateAndCompile(demoModel(), dir, List.of(stub));

        var help = contributedCli.run("--help");
        assertThat(help.exitCode()).isZero();
        assertThat(help.stdout()).contains("stub-status");

        var result = contributedCli.run("stub-status");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("STUB-STATUS-MARKER for aperture");
    }

    private static ResolvedDomainModel modelWithBinaryName(String binaryName) {
        EntityDef customer = new EntityDef("Customer", "customers", null, null, false, false, true,
            Map.of("name", new FieldDef("string", true, true, false, false, null, null, null, null, null, null, null)),
            null, null, null, null, null);
        FrameworkConfigDef framework = new FrameworkConfigDef(
            List.of(), TenancyMode.POOL, null, new CliConfig(binaryName));
        return new ResolvedDomainModel(List.of(customer), List.of(), framework, List.of(), List.of(), List.of());
    }

    private static ResolvedDomainModel demoModel() {
        EntityDef customer = new EntityDef("Customer", "customers", null, null, false, false, true,
            Map.of(
                "name", new FieldDef("string", true, true, false, false, null, null, null, null, null, null, null),
                "email", new FieldDef("string", false, false, false, false, null, null, null, null, null, null, null)
            ),
            null, null, null, null, null);
        // No natural key on purpose — exercises the same _ref/positional-label path as
        // Invoice in the demo app, since Order has no unique/name/code/sku/email field.
        EntityDef order = new EntityDef("Order", "orders", null, null, false, false, true,
            Map.of(
                "label", new FieldDef("string", false, false, false, false, null, null, null, null, null, null, null),
                // Not unique on purpose: exercises the natural-key-not-unique warning
                // (detectNaturalKey() picks "name" as a candidate regardless of uniqueness).
                "name", new FieldDef("string", false, false, false, false, null, null, null, null, null, null, null),
                "customer", new FieldDef("ref", true, false, false, false, null, null, null, "ManyToOne", "Customer", null, null)
            ),
            null, null, null, null, null);
        FrameworkConfigDef framework = new FrameworkConfigDef(
            List.of(), TenancyMode.POOL, null, new CliConfig("aperture"));
        return new ResolvedDomainModel(List.of(customer, order), List.of(), framework, List.of(), List.of(), List.of());
    }

    private static String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
