# Aperture Keycloak CLI Demo

## What this demo proves

An Aperture-generated CLI logs in against a real OIDC provider (Keycloak)
using the OAuth 2.0 device authorization grant, with **no server changes and
no CLI code hand-written**. `aperture-cli-auth-oidc` is a generation-time
`CliAuthExtension` that replaces the CLI's entire `auth` command group
(tier-2 of the auth SPI — full source replacement, not just path
customization); the API server independently validates the resulting
Keycloak-issued JWTs via the shared `aperture-keycloak-auth` module. One demo,
both halves of the OIDC story: the CLI acquires the token, the server
verifies it, and neither side knows about the other's implementation.

## Why device code for a CLI

A CLI has nowhere safe to keep a client secret, and it usually can't open a
browser and catch a `localhost` redirect (SSH sessions, containers, CI). The
device authorization grant (RFC 8628) sidesteps both problems: the CLI asks
the IdP for a short code, the human approves it in whatever browser they
already have open and already trust, and the CLI polls until that approval
lands. It's the standard pattern for CLI tools talking to OIDC providers
(`gh`, `az`, `aws sso` all use a variant of it).

## Run

```bash
mise run docker-deploy
mvn -pl demos/aperture-keycloak-cli-demo -am package -DskipTests
```

Keycloak runs at `http://localhost:8181`; the Aperture API runs at
`http://localhost:8081`. The `mvn package` step also builds the generated
CLI (`apkc`) — as a native binary if `native-image` is on your `PATH`,
otherwise as a fat jar — under `target/generated-cli/aperture-cli/target/`.

## What happens when you run `auth login`

```bash
cd target/generated-cli/aperture-cli
./target/apkc auth login --issuer http://localhost:8181/realms/aperture --client-id aperture-cli
```

Under the hood:

1. **Discovery** — the CLI fetches
   `{issuer}/.well-known/openid-configuration` to find Keycloak's device,
   token, and userinfo endpoints. Nothing about them is hardcoded.
2. **Device authorization** — it `POST`s to the `device_authorization_endpoint`
   with `client_id` and `scope=openid`, and Keycloak hands back a
   `user_code`, a `verification_uri_complete`, and a polling `interval`.
3. **You approve in a browser you already trust** — the CLI prints the
   verification URL and code and starts polling. This is the actual security
   model: the CLI never sees your password. You prove who you are inside a
   browser session Keycloak already trusts, and the device code is the only
   thing that crosses back to the CLI.
4. **Polling** — the CLI polls the `token_endpoint` every `interval` seconds
   (Keycloak's default is 5s), with `authorization_pending` coming back
   until you approve. If Keycloak replies `slow_down`, the CLI backs off by
   +5s before polling again rather than hammering the endpoint.
   `access_denied` or the device code expiring both stop the CLI cleanly
   with a clear message — no infinite polling.
5. **Tokens land in your profile** — once approved, the access/refresh
   tokens are written to `~/.config/apkc/config.json` (or your `APKC_CONFIG`
   override) alongside `oidc: {issuer, clientId}`, so `auth login` needs no
   flags next time. This is deliberately file-based, `0600`, not an OS
   keychain — fine for a demo, worth knowing before pointing this at
   anything real.

## Why there are two Keycloak clients

The realm defines two separate clients on purpose, mirroring a real
deployment:

- **`aperture-api`** — the client the *server* validates JWT audiences
  against (used by the password grant in the Bruno collection and the
  seeder).
- **`aperture-cli`** — a public client with
  `"attributes": {"oauth2.device.authorization.grant.enabled": "true"}` in
  `keycloak/aperture-realm.json`, the attribute that turns on the device
  grant. Nothing else about it is special-cased; any Keycloak client with
  that attribute set works with this extension.

Keeping them separate means the CLI's login flow and the server's audience
checks can evolve independently, which is how you'd actually run this.

## Device Login (the walkthrough)

From the generated CLI directory:

```bash
cd target/generated-cli/aperture-cli
./target/apkc config set-server http://localhost:8081
./target/apkc auth login --issuer http://localhost:8181/realms/aperture --client-id aperture-cli
```

Open the verification URL printed by the CLI — **watching this happen in a
browser is the point of this walkthrough** — then sign in as one of the
seeded realm users:

| User | Password | Role |
|---|---|---|
| `admin@keycloak-demo.com` | `Admin123!` | `Admin` |
| `user@keycloak-demo.com` | `User123!` | `User` |

Approve the "Grant Access to aperture-cli" consent screen. The CLI's
`Waiting for approval...` line resolves within one polling interval of your
approval — watch the dots stop and `logged in (expires in ...)` print.

After approval:

```bash
./target/apkc get products     # Bearer token sent like any other login
./target/apkc auth me          # GET userinfo_endpoint with the stored token
./target/apkc auth refresh     # refresh_token grant against the token endpoint
./target/apkc auth logout      # clears the profile, revokes the refresh token
```

(If you built a fat jar instead of a native binary, replace `./target/apkc`
above with `java -jar target/aperture-cli-0.0.1-SNAPSHOT.jar`.)

## Automated verification

`device-flow-smoke.sh` proves the exact same flow with no browser: it starts
`apkc auth login` in the background, then uses `curl` with a cookie jar to
play the browser's part — `GET` the verification URI, submit Keycloak's
login form, submit the consent form — before asserting `apkc auth me` and
`apkc get products` both work with the resulting token. It brings the stack
up, waits on healthchecks, and tears everything down (`docker compose down
-v`) in a trap so it cleans up even on failure.

```bash
./device-flow-smoke.sh
```

The manual walkthrough above stays the primary way to experience this demo —
*seeing* the device flow happen in a browser, code and consent screen
included, is what it's for. The script exists so the same flow can be
verified headlessly (CI, or a quick sanity check after touching the OIDC
extension) without watching a browser screen.

## Bruno API Collection

Import `api-collection/` into [Bruno](https://usebruno.com) for
password-grant requests against `aperture-api` covering auth, products, and
orders, plus a cleanup folder. It's a separate, faster path for exercising
the server side of this demo when you don't need the CLI's device flow.
