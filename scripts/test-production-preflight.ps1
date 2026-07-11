[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$verifierPath = Join-Path $PSScriptRoot "verify-production-compose.ps1"
$script:productionComposeFilePath = Join-Path $repositoryRoot "docker-compose.prod.yml"
$exampleEnvironmentPath = Join-Path $repositoryRoot ".env.prod.example"
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd("\", "/")
$fixtureRoot = Join-Path $temporaryRoot ("techdesk-production-preflight-" + [guid]::NewGuid().ToString("N"))
$resolvedFixtureRoot = [IO.Path]::GetFullPath($fixtureRoot)

if (-not $resolvedFixtureRoot.StartsWith(
        $temporaryRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase
)) {
    throw "Refusing to create a preflight fixture outside the operating-system temporary directory."
}

$secretDirectory = Join-Path $fixtureRoot "secrets"
$environmentFilePath = Join-Path $fixtureRoot ".env.prod.test"

function Write-FixtureEnvironmentFile {
    param(
        [Parameter(Mandatory)]
        [hashtable]$Overrides
    )

    $lines = [Collections.Generic.List[string]]::new()
    foreach ($line in [IO.File]::ReadAllLines($exampleEnvironmentPath)) {
        $lines.Add($line)
    }

    foreach ($entry in $Overrides.GetEnumerator()) {
        $prefix = "$($entry.Key)="
        $matchingIndexes = @()
        for ($index = 0; $index -lt $lines.Count; $index++) {
            if ($lines[$index].StartsWith($prefix, [StringComparison]::Ordinal)) {
                $matchingIndexes += $index
            }
        }
        if ($matchingIndexes.Count -ne 1) {
            throw "Expected exactly one '$($entry.Key)' entry in .env.prod.example."
        }
        $lines[$matchingIndexes[0]] = $prefix + [string]$entry.Value
    }

    [IO.File]::WriteAllLines(
        $environmentFilePath,
        $lines,
        [Text.UTF8Encoding]::new($false)
    )
}

function Write-MutatedProductionComposeFile {
    param(
        [Parameter(Mandatory)]
        [string]$FileName,

        [Parameter(Mandatory)]
        [string]$Pattern,

        [Parameter(Mandatory)]
        [AllowEmptyString()]
        [string]$ReplacementText
    )

    $source = [IO.File]::ReadAllText((Join-Path $repositoryRoot "docker-compose.prod.yml"))
    $regularExpression = [regex]::new($Pattern)
    $matches = $regularExpression.Matches($source)
    if ($matches.Count -ne 1) {
        throw "Production Compose test mutation '$FileName' expected one match but found $($matches.Count)."
    }
    $replacement = $ReplacementText
    $mutated = $regularExpression.Replace(
        $source,
        [Text.RegularExpressions.MatchEvaluator] { param($match) $replacement },
        1
    )

    $path = Join-Path $fixtureRoot $FileName
    [IO.File]::WriteAllText($path, $mutated, [Text.UTF8Encoding]::new($false))
    return $path
}

function Assert-StrictPreflightRejected {
    param(
        [Parameter(Mandatory)]
        [string]$ExpectedMessagePattern,

        [Parameter(Mandatory)]
        [string]$Scenario
    )

    $rejection = $null
    try {
        & $verifierPath `
            -EnvironmentFile $environmentFilePath `
            -ProductionComposeFile $script:productionComposeFilePath `
            -Strict | Out-Null
    }
    catch {
        $rejection = $_
    }

    if ($null -eq $rejection) {
        throw "Strict preflight unexpectedly accepted: $Scenario."
    }
    if ($rejection.Exception.Message -notmatch $ExpectedMessagePattern) {
        throw "Strict preflight rejected '$Scenario' for an unexpected reason: $($rejection.Exception.Message)"
    }
    Write-Output "Rejected as expected: $Scenario."
}

New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
try {
    $secretValues = [ordered]@{
        "postgres_admin_password" = "postgres-" + [guid]::NewGuid().ToString("N")
        "auth_db_password" = "auth-db-" + [guid]::NewGuid().ToString("N")
        "tenant_db_password" = "tenant-db-" + [guid]::NewGuid().ToString("N")
        "audit_db_password" = "audit-db-" + [guid]::NewGuid().ToString("N")
        "provisioner_db_password" = "provisioner-db-" + [guid]::NewGuid().ToString("N")
        "auth_jwt_secret" = "jwt-" + [guid]::NewGuid().ToString("N")
        "audit_internal_key" = "audit-key-" + [guid]::NewGuid().ToString("N")
        "redis_password" = "redis-" + [guid]::NewGuid().ToString("N")
        "smtp_password" = "smtp-" + [guid]::NewGuid().ToString("N")
    }
    foreach ($secret in $secretValues.GetEnumerator()) {
        [IO.File]::WriteAllText(
            (Join-Path $secretDirectory $secret.Key),
            $secret.Value,
            [Text.UTF8Encoding]::new($false)
        )
    }

    $flywayConfiguration = @(
        "flyway.url=jdbc:postgresql://postgres:5432/techdesk",
        "flyway.user=techdesk_admin",
        "flyway.password=$($secretValues['postgres_admin_password'])",
        "flyway.schemas=public",
        "flyway.defaultSchema=public",
        "flyway.locations=filesystem:/flyway/sql",
        "flyway.connectRetries=60",
        "flyway.cleanDisabled=true",
        "flyway.validateMigrationNaming=true",
        "flyway.validateOnMigrate=true"
    ) -join [Environment]::NewLine
    [IO.File]::WriteAllText(
        (Join-Path $secretDirectory "flyway.conf"),
        $flywayConfiguration,
        [Text.UTF8Encoding]::new($false)
    )

    $composeSecretDirectory = $secretDirectory.Replace("\", "/")
    $environmentOverrides = @{
        "TECHDESK_SECRETS_DIR" = $composeSecretDirectory
        "LOOPBACK_INGRESS_FIREWALL_ENFORCED" = "true"
        "EXTERNAL_EGRESS_POLICY_ENFORCED" = "true"
        "SMTP_HOST" = "smtp.acme-corp.net"
        "SMTP_USERNAME" = "techdesk-production-mailer"
        "AUTH_PASSWORD_RESET_URL" = "https://app.acme-corp.net/reset-password"
        "AUTH_MAIL_FROM" = "no-reply@acme-corp.net"
        "TENANT_PORTAL_URL_TEMPLATE" = "https://%s.acme-corp.net"
        "TENANT_INVITATION_URL" = "https://app.acme-corp.net/accept-invitation"
        "TENANT_MAIL_FROM" = "no-reply@acme-corp.net"
        "DISCOVERY_SERVER_IMAGE" = "ghcr.io/acme/techdesk-discovery-server@sha256:" + ("1" * 64)
        "API_GATEWAY_IMAGE" = "ghcr.io/acme/techdesk-api-gateway@sha256:" + ("2" * 64)
        "AUTH_SERVICE_IMAGE" = "ghcr.io/acme/techdesk-auth-service@sha256:" + ("3" * 64)
        "TENANT_SERVICE_IMAGE" = "ghcr.io/acme/techdesk-tenant-service@sha256:" + ("4" * 64)
        "AUDIT_SERVICE_IMAGE" = "ghcr.io/acme/techdesk-audit-service@sha256:" + ("5" * 64)
    }
    Write-FixtureEnvironmentFile -Overrides $environmentOverrides

    & $verifierPath `
        -EnvironmentFile $environmentFilePath `
        -ProductionComposeFile $script:productionComposeFilePath `
        -Strict | Out-Null
    Write-Output "Accepted as expected: independent strong secrets and complete production settings."

    $jwtPath = Join-Path $secretDirectory "auth_jwt_secret"
    [IO.File]::WriteAllText($jwtPath, "too-short", [Text.UTF8Encoding]::new($false))
    Assert-StrictPreflightRejected -ExpectedMessagePattern "at least 32 UTF-8 bytes" -Scenario "a weak JWT key"
    [IO.File]::WriteAllText($jwtPath, $secretValues["auth_jwt_secret"], [Text.UTF8Encoding]::new($false))

    $smtpPasswordPath = Join-Path $secretDirectory "smtp_password"
    [IO.File]::WriteAllText($smtpPasswordPath, ("a" * 32), [Text.UTF8Encoding]::new($false))
    Assert-StrictPreflightRejected -ExpectedMessagePattern "at least 8 distinct characters" -Scenario "a repeated-character SMTP password"
    [IO.File]::WriteAllText($smtpPasswordPath, $secretValues["smtp_password"], [Text.UTF8Encoding]::new($false))

    [IO.File]::WriteAllText($smtpPasswordPath, "abcdefghiabcdefghi", [Text.UTF8Encoding]::new($false))
    Assert-StrictPreflightRejected -ExpectedMessagePattern "must not be a repeated pattern" -Scenario "a repeated multi-character SMTP password"
    [IO.File]::WriteAllText($smtpPasswordPath, $secretValues["smtp_password"], [Text.UTF8Encoding]::new($false))

    $redisPath = Join-Path $secretDirectory "redis_password"
    [IO.File]::WriteAllText($redisPath, "", [Text.UTF8Encoding]::new($false))
    Assert-StrictPreflightRejected -ExpectedMessagePattern "must not be empty" -Scenario "an empty Redis password"
    [IO.File]::WriteAllText($redisPath, $secretValues["redis_password"], [Text.UTF8Encoding]::new($false))

    $tenantPasswordPath = Join-Path $secretDirectory "tenant_db_password"
    [IO.File]::WriteAllText($tenantPasswordPath, $secretValues["auth_db_password"], [Text.UTF8Encoding]::new($false))
    Assert-StrictPreflightRejected -ExpectedMessagePattern "must not reuse a value" -Scenario "reused application database credentials"
    [IO.File]::WriteAllText($tenantPasswordPath, $secretValues["tenant_db_password"], [Text.UTF8Encoding]::new($false))

    $flywayPath = Join-Path $secretDirectory "flyway.conf"
    $invalidFlywayConfiguration = $flywayConfiguration.Replace(
        "flyway.locations=filesystem:/flyway/sql",
        "flyway.locations=filesystem:/unexpected"
    )
    [IO.File]::WriteAllText($flywayPath, $invalidFlywayConfiguration, [Text.UTF8Encoding]::new($false))
    Assert-StrictPreflightRejected -ExpectedMessagePattern "expected public schema" -Scenario "an unexpected Flyway migration location"
    [IO.File]::WriteAllText($flywayPath, $flywayConfiguration, [Text.UTF8Encoding]::new($false))

    $caseChangedFlywayConfiguration = $flywayConfiguration.Replace(
        "flyway.locations=filesystem:/flyway/sql",
        "flyway.locations=FILESYSTEM:/FLYWAY/SQL"
    )
    [IO.File]::WriteAllText($flywayPath, $caseChangedFlywayConfiguration, [Text.UTF8Encoding]::new($false))
    Assert-StrictPreflightRejected -ExpectedMessagePattern "expected public schema" -Scenario "case-changed Flyway location semantics"
    [IO.File]::WriteAllText($flywayPath, $flywayConfiguration, [Text.UTF8Encoding]::new($false))

    $ambiguousFlywayConfiguration = $flywayConfiguration + [Environment]::NewLine + "flyway.cleanDisabled:false"
    [IO.File]::WriteAllText($flywayPath, $ambiguousFlywayConfiguration, [Text.UTF8Encoding]::new($false))
    Assert-StrictPreflightRejected -ExpectedMessagePattern "only unescaped key=value settings" -Scenario "an alternate-syntax Flyway override"
    [IO.File]::WriteAllText($flywayPath, $flywayConfiguration, [Text.UTF8Encoding]::new($false))

    $environmentOverrides["POSTGRES_DB"] = "unexpected_database"
    Write-FixtureEnvironmentFile -Overrides $environmentOverrides
    Assert-StrictPreflightRejected -ExpectedMessagePattern "production PostgreSQL service and database" -Scenario "a Flyway URL that disagrees with normalized PostgreSQL settings"
    $environmentOverrides["POSTGRES_DB"] = "techdesk"

    $environmentOverrides["LOOPBACK_INGRESS_FIREWALL_ENFORCED"] = "false"
    Write-FixtureEnvironmentFile -Overrides $environmentOverrides
    Assert-StrictPreflightRejected -ExpectedMessagePattern "external firewall around loopback" -Scenario "missing loopback-ingress firewall controls"
    $environmentOverrides["LOOPBACK_INGRESS_FIREWALL_ENFORCED"] = "true"

    $environmentOverrides["EXTERNAL_EGRESS_POLICY_ENFORCED"] = "false"
    Write-FixtureEnvironmentFile -Overrides $environmentOverrides
    Assert-StrictPreflightRejected -ExpectedMessagePattern "destination-level egress enforcement" -Scenario "missing external egress controls"

    $environmentOverrides["EXTERNAL_EGRESS_POLICY_ENFORCED"] = "true"
    $environmentOverrides["TENANT_PORTAL_URL_TEMPLATE"] = "https://portal.acme-corp.net"
    Write-FixtureEnvironmentFile -Overrides $environmentOverrides
    Assert-StrictPreflightRejected -ExpectedMessagePattern "exactly one '%s'" -Scenario "a tenant portal URL without a tenant placeholder"

    $environmentOverrides["TENANT_PORTAL_URL_TEMPLATE"] = "https://user%s@evil.acme-corp.net"
    Write-FixtureEnvironmentFile -Overrides $environmentOverrides
    Assert-StrictPreflightRejected -ExpectedMessagePattern "valid HTTPS DNS host" -Scenario "a tenant placeholder hidden in URL user information"

    $environmentOverrides["TENANT_PORTAL_URL_TEMPLATE"] = "https://%s.acme-corp.net"
    $environmentOverrides["SMTP_HOST"] = "smtp.example.com"
    Write-FixtureEnvironmentFile -Overrides $environmentOverrides
    Assert-StrictPreflightRejected -ExpectedMessagePattern "example or local placeholder" -Scenario "an example SMTP host"

    $environmentOverrides["SMTP_HOST"] = "smtp.acme-corp.net"
    Write-FixtureEnvironmentFile -Overrides $environmentOverrides

    $script:productionComposeFilePath = Write-MutatedProductionComposeFile `
        -FileName "docker-compose.postgres-trust.test.yml" `
        -Pattern "(?m)^      POSTGRES_PASSWORD_FILE: /run/secrets/postgres_admin_password\r?$" `
        -ReplacementText "      POSTGRES_PASSWORD_FILE: /run/secrets/postgres_admin_password`n      POSTGRES_HOST_AUTH_METHOD: trust"
    Assert-StrictPreflightRejected -ExpectedMessagePattern "PostgreSQL environment" -Scenario "passwordless PostgreSQL host authentication"

    $script:productionComposeFilePath = Write-MutatedProductionComposeFile `
        -FileName "docker-compose.redis-no-password.test.yml" `
        -Pattern "(?m)^      - exec redis-server --appendonly yes --requirepass .*$" `
        -ReplacementText "      - exec redis-server --appendonly yes"
    Assert-StrictPreflightRejected -ExpectedMessagePattern "mounted authentication secret" -Scenario "Redis started without requirepass"

    $script:productionComposeFilePath = Write-MutatedProductionComposeFile `
        -FileName "docker-compose.redis-fake-health.test.yml" `
        -Pattern "(?m)^        - REDISCLI_AUTH=.*redis-cli ping\r?$" `
        -ReplacementText "        - REDISCLI_AUTH=`"ignored`" true"
    Assert-StrictPreflightRejected -ExpectedMessagePattern "authenticated ping" -Scenario "a no-op Redis healthcheck carrying REDISCLI_AUTH text"

    $script:productionComposeFilePath = Write-MutatedProductionComposeFile `
        -FileName "docker-compose.flyway-environment.test.yml" `
        -Pattern "(?m)^      - migrate\r?$" `
        -ReplacementText "      - migrate`n    environment:`n      FLYWAY_CLEAN_DISABLED: `"false`""
    Assert-StrictPreflightRejected -ExpectedMessagePattern "higher-precedence environment configuration" -Scenario "a Flyway environment override"

    $script:productionComposeFilePath = Write-MutatedProductionComposeFile `
        -FileName "docker-compose.flyway-cli.test.yml" `
        -Pattern "(?m)^      - migrate\r?$" `
        -ReplacementText "      - -cleanDisabled=false`n      - migrate"
    Assert-StrictPreflightRejected -ExpectedMessagePattern "execute only migrate" -Scenario "a higher-precedence Flyway CLI override"

    $script:productionComposeFilePath = Write-MutatedProductionComposeFile `
        -FileName "docker-compose.fake-healthcheck.test.yml" `
        -Pattern "(?m)^        - CMD\r?\n        - curl\r?\n        - --fail\r?\n        - --silent\r?\n        - --show-error\r?\n        - --max-time\r?\n        - `"5`"\r?\n        - http://127\.0\.0\.1:9080/actuator/health\r?$" `
        -ReplacementText "        - CMD`n        - `"true`"`n        - ignored`n        - http://127.0.0.1:9080/actuator/health"
    Assert-StrictPreflightRejected -ExpectedMessagePattern "exact private management health probe" -Scenario "a no-op healthcheck carrying the expected endpoint as an unused argument"

    $script:productionComposeFilePath = Write-MutatedProductionComposeFile `
        -FileName "docker-compose.unsafe-tmpfs.test.yml" `
        -Pattern "(?m)^    - /tmp:rw,noexec,nosuid,size=128m\r?$" `
        -ReplacementText "    - /tmp:rw,nosuid,size=128m"
    Assert-StrictPreflightRejected -ExpectedMessagePattern "128 MiB noexec/nosuid" -Scenario "an executable Java temporary filesystem"

    $script:productionComposeFilePath = Write-MutatedProductionComposeFile `
        -FileName "docker-compose.udp-gateway.test.yml" `
        -Pattern "(?m)^        protocol: tcp\r?$" `
        -ReplacementText "        protocol: udp"
    Assert-StrictPreflightRejected -ExpectedMessagePattern "bind TCP port 8080" -Scenario "a UDP-only gateway publication"

    $script:productionComposeFilePath = Write-MutatedProductionComposeFile `
        -FileName "docker-compose.privileged.test.yml" `
        -Pattern "(?m)^  api-gateway:\r?\n    <<: \*java-production\r?\n" `
        -ReplacementText "  api-gateway:`n    <<: *java-production`n    privileged: true`n"
    Assert-StrictPreflightRejected -ExpectedMessagePattern "must not be privileged" -Scenario "a privileged API Gateway container"

    $script:productionComposeFilePath = Write-MutatedProductionComposeFile `
        -FileName "docker-compose.cap-add.test.yml" `
        -Pattern "(?m)^  api-gateway:\r?\n    <<: \*java-production\r?\n" `
        -ReplacementText "  api-gateway:`n    <<: *java-production`n    cap_add:`n      - NET_ADMIN`n"
    Assert-StrictPreflightRejected -ExpectedMessagePattern "must not add Linux capabilities" -Scenario "an API Gateway container with an added capability"

    $script:productionComposeFilePath = Write-MutatedProductionComposeFile `
        -FileName "docker-compose.docker-socket.test.yml" `
        -Pattern "(?m)^  api-gateway:\r?\n    <<: \*java-production\r?\n" `
        -ReplacementText "  api-gateway:`n    <<: *java-production`n    volumes:`n      - /var/run/docker.sock:/var/run/docker.sock`n"
    Assert-StrictPreflightRejected -ExpectedMessagePattern "must not configure 'volumes'" -Scenario "a Docker socket mounted into API Gateway"

    $script:productionComposeFilePath = Write-MutatedProductionComposeFile `
        -FileName "docker-compose.unconfined-seccomp.test.yml" `
        -Pattern "(?m)^    - no-new-privileges:true\r?$" `
        -ReplacementText "    - no-new-privileges:true`n    - seccomp=unconfined"
    Assert-StrictPreflightRejected -ExpectedMessagePattern "only no-new-privileges" -Scenario "an unconfined Java seccomp profile"

    $script:productionComposeFilePath = Write-MutatedProductionComposeFile `
        -FileName "docker-compose.missing-secret.test.yml" `
        -Pattern "(?m)^      - source: auth_db_password\r?\n        target: spring\.datasource\.password\r?\n" `
        -ReplacementText ""
    Assert-StrictPreflightRejected -ExpectedMessagePattern "Secret grants for 'auth-service'" -Scenario "a missing auth-service database secret"

    $script:productionComposeFilePath = Join-Path $repositoryRoot "docker-compose.prod.yml"

    Write-Output "Production preflight positive and negative contract tests passed."
}
finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        $cleanupPath = [IO.Path]::GetFullPath($fixtureRoot)
        if (-not $cleanupPath.StartsWith(
                $temporaryRoot + [IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase
        )) {
            throw "Refusing to clean a preflight fixture outside the operating-system temporary directory."
        }
        Remove-Item -LiteralPath $cleanupPath -Recurse -Force
    }
}
