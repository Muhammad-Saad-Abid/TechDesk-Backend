# Production deployment contract

The default `docker-compose.yml` remains the local-development stack. Its host
ports bind to `127.0.0.1`, so PostgreSQL, Redis, MailHog, pgAdmin, Eureka, and
internal APIs are not intentionally exposed to the LAN. Docker Engine releases
older than 28 had a same-L2 loopback-publishing caveat, so host firewall policy
remains mandatory for production defense in depth.

The local PostgreSQL host port defaults to `15432` (container-to-container
traffic remains on `5432`). Override `POSTGRES_HOST_PORT` in the ignored `.env`
file if another loopback port is preferred.

Production always uses both Compose files:

```powershell
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod up -d --wait --wait-timeout 900
```

The production overlay deliberately enforces these boundaries:

- only API Gateway publishes a host port, bound to loopback for a TLS reverse
  proxy such as Caddy, Nginx, or a cloud load balancer;
- API Gateway, application, and data traffic remain on Docker-internal
  networks; the loopback port publication is its only ingress path;
- only auth-service and tenant-service attach to the dedicated non-internal
  egress network required for production SMTP;
- pgAdmin and MailHog are excluded unless the `development-tools` profile is
  explicitly enabled, and they still publish no production ports;
- Java management endpoints use private ports `9080`-`9083` and `9761`;
- application images and infrastructure images must use immutable SHA-256
  digest references;
- Java containers use a read-only root filesystem, bounded temporary storage,
  a dedicated non-root UID/GID 10001, no Linux capabilities, and no privilege
  escalation;
- passwords, JWT material, and internal service keys are mounted as files and
  never placed in the normalized service environment.

## Prepare production configuration

1. Copy `.env.prod.example` to the ignored `.env.prod` file.
2. Replace all five `ghcr.io/replace-me/...` application images with images
   published by CI and pinned as `repository@sha256:<64 hex characters>`.
3. Set the public HTTPS URLs, verified sender addresses, SMTP host, and SMTP
   username.
4. Configure the host firewall so the gateway's loopback port is reachable
   only by the local TLS reverse proxy, then set
   `LOOPBACK_INGRESS_FIREWALL_ENFORCED=true`. This remains required even when
   the Docker Engine version has corrected older loopback-publishing behavior.
5. Enforce destination-level outbound policy outside Compose. Allow
   auth-service and tenant-service to reach only the required DNS resolver and
   SMTP relay/port, using a host firewall, CNI/network policy, or an explicit
   egress proxy. Compose network membership limits which services receive
   outbound connectivity, but a normal bridge network cannot itself restrict
   destinations. Set `EXTERNAL_EGRESS_POLICY_ENFORCED=true` only after that
   control is active; strict preflight requires this explicit operator
   assertion but cannot inspect the external firewall.
6. Point `TECHDESK_SECRETS_DIR` to an absolute directory outside this
   repository. File-backed Compose secrets preserve host permissions on Linux;
   keep the files non-world-readable and grant read access with host ACLs to the
   exact container UIDs that consume them. In particular, Java-facing secrets
   require read access for UID `10001`. PostgreSQL's password file also requires
   access for the `postgres` UID in the pinned image. Root-run one-shot
   containers can read owner-protected files. Prefer an orchestrator/external
   secret manager for a real production cluster, and verify permissions on the
   target Linux host rather than assuming Docker Desktop semantics.
7. Place these files in that directory, with no trailing commentary:

   - `postgres_admin_password`
   - `auth_db_password`
   - `tenant_db_password`
   - `audit_db_password`
   - `provisioner_db_password`
   - `auth_jwt_secret`
   - `audit_internal_key`
   - `redis_password`
   - `smtp_password`
   - `flyway.conf`

Generate independent random values for every password/key. JWT and internal
keys must contain at least 32 UTF-8 bytes; database, Redis, and SMTP passwords
must contain at least 16. Do not reuse any scalar secret value. The one
intentional duplication is `flyway.password`, which must match
`postgres_admin_password` because Flyway connects as `techdesk_admin`.

`flyway.conf` is itself a secret because it contains the administrator
password. Strict preflight permits only the ten case-sensitive, unescaped
`key=value` settings shown below, preventing alternate property syntax or
higher-precedence migration overrides:

```properties
flyway.url=jdbc:postgresql://postgres:5432/techdesk
flyway.user=techdesk_admin
flyway.password=replace-with-the-production-admin-password
flyway.schemas=public
flyway.defaultSchema=public
flyway.locations=filesystem:/flyway/sql
flyway.connectRetries=60
flyway.cleanDisabled=true
flyway.validateMigrationNaming=true
flyway.validateOnMigrate=true
```

## Mandatory preflight

First verify the tracked example contract (this requires no real secrets or
published application images):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-production-compose.ps1
```

Before a real deployment, strict mode additionally rejects placeholder images
and public settings; requires every secret file outside the repository;
validates non-empty minimum-strength scalar secrets; rejects reused secrets;
checks the Flyway administrator configuration; and requires the explicit
ingress-firewall and external egress-policy assertions:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-production-compose.ps1 -EnvironmentFile .env.prod -Strict
```

The preflight itself has an executable positive/negative contract suite. It
creates disposable secrets under the operating-system temporary directory and
proves that weak or repetitive secrets, empty passwords, credential reuse,
unsafe Flyway settings, missing external egress controls, invalid tenant URL
templates, example public settings, privileged/capability-added containers,
fake health probes, executable temporary filesystems, non-TCP gateway ingress,
container escape mounts/options, passwordless PostgreSQL or Redis, and
incorrect service secret grants are rejected:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-production-preflight.ps1
```

Terminate TLS before the loopback gateway port and expose only HTTPS publicly.
Never place secret values in `.env.prod`, command-line arguments, image tags,
or source control. Never enable the `development-tools` profile on a production
host.
