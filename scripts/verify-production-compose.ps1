[CmdletBinding()]
param(
    [string]$EnvironmentFile = ".env.prod.example",
    [string]$ProductionComposeFile = "docker-compose.prod.yml",
    [switch]$Strict
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot

function Get-NamedProperty {
    param(
        [Parameter(Mandatory)]
        [object]$Object,

        [Parameter(Mandatory)]
        [string]$Name,

        [Parameter(Mandatory)]
        [string]$Description
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        throw "$Description '$Name' is missing."
    }

    return $property.Value
}

function Get-OptionalArray {
    param(
        [Parameter(Mandatory)]
        [object]$Object,

        [Parameter(Mandatory)]
        [string]$Name
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        return @()
    }

    return @($property.Value)
}

function Assert-ExactSet {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [string[]]$Actual,

        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [string[]]$Expected,

        [Parameter(Mandatory)]
        [string]$Description
    )

    $actualSorted = @($Actual | Sort-Object -CaseSensitive -Unique)
    $expectedSorted = @($Expected | Sort-Object -CaseSensitive -Unique)
    if (($actualSorted -join "|") -cne ($expectedSorted -join "|")) {
        throw "$Description must be [$($expectedSorted -join ', ')], but was [$($actualSorted -join ', ')]."
    }
}

function Assert-StrongSecretValue {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyString()]
        [string]$Value,

        [Parameter(Mandatory)]
        [string]$Description,

        [Parameter(Mandatory)]
        [int]$MinimumUtf8Bytes
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "$Description must not be empty or whitespace-only."
    }
    if ($Value -cne $Value.Trim()) {
        throw "$Description must not contain leading or trailing whitespace."
    }
    if ($Value -match "[\x00-\x1f\x7f]") {
        throw "$Description must be a single-line value without control characters."
    }

    $utf8ByteCount = [Text.Encoding]::UTF8.GetByteCount($Value)
    if ($utf8ByteCount -lt $MinimumUtf8Bytes) {
        throw "$Description must contain at least $MinimumUtf8Bytes UTF-8 bytes."
    }
    if ($utf8ByteCount -gt 4096) {
        throw "$Description is unexpectedly large for a scalar secret."
    }
    $distinctCharacterCount = @($Value.ToCharArray() | Sort-Object -Unique).Count
    if ($distinctCharacterCount -lt 8) {
        throw "$Description must contain at least 8 distinct characters."
    }
    if ($Value -match "^(.+?)\1+$") {
        throw "$Description must not be a repeated pattern."
    }
    if ($Value -match "(?i)(replace[-_ ]?(with|me)|change[-_ ]?me|changeme|placeholder|example|unused-by-production-secret-overlay|^password[0-9]*$|^secret[0-9]*$)") {
        throw "$Description still contains a placeholder value."
    }
}

function Read-StrongScalarSecret {
    param(
        [Parameter(Mandatory)]
        [string]$Path,

        [Parameter(Mandatory)]
        [string]$Name,

        [Parameter(Mandatory)]
        [int]$MinimumUtf8Bytes
    )

    $content = [IO.File]::ReadAllText($Path)
    $value = $content.TrimEnd([char[]]"`r`n")
    Assert-StrongSecretValue `
        -Value $value `
        -Description "Secret file '$Name'" `
        -MinimumUtf8Bytes $MinimumUtf8Bytes
    return $value
}

function Get-RequiredFlywaySetting {
    param(
        [Parameter(Mandatory)]
        [string]$Content,

        [Parameter(Mandatory)]
        [string]$Name
    )

    $escapedName = [regex]::Escape($Name)
    $matchingLines = @($Content -split "`r?`n" | Where-Object {
        $_ -cmatch "^[ `t]*$escapedName[ `t]*="
    })
    if ($matchingLines.Count -ne 1) {
        throw "flyway.conf must define '$Name' exactly once."
    }

    $separatorIndex = $matchingLines[0].IndexOf("=", [StringComparison]::Ordinal)
    $value = $matchingLines[0].Substring($separatorIndex + 1).Trim()
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "flyway.conf setting '$Name' must not be empty."
    }
    return $value
}

Push-Location $repositoryRoot
try {
    $environmentPath = Resolve-Path -LiteralPath $EnvironmentFile
    $productionComposePath = Resolve-Path -LiteralPath $ProductionComposeFile
    $composeJson = & docker compose `
        -f docker-compose.yml `
        -f $productionComposePath `
        --env-file $environmentPath `
        config --format json
    if ($LASTEXITCODE -ne 0) {
        throw "Production Compose normalization failed with exit code $LASTEXITCODE."
    }

    $config = $composeJson | ConvertFrom-Json
    $services = $config.services
    $expectedServices = @(
        "postgres",
        "database-role-bootstrap",
        "redis",
        "flyway",
        "discovery-server",
        "api-gateway",
        "auth-service",
        "tenant-service",
        "audit-service"
    )
    Assert-ExactSet `
        -Actual @($services.PSObject.Properties.Name) `
        -Expected $expectedServices `
        -Description "Enabled production services"

    $expectedSecretNames = @(
        "postgres_admin_password",
        "auth_db_password",
        "tenant_db_password",
        "audit_db_password",
        "provisioner_db_password",
        "auth_jwt_secret",
        "audit_internal_key",
        "redis_password",
        "smtp_password",
        "flyway_configuration"
    )
    Assert-ExactSet `
        -Actual @($config.secrets.PSObject.Properties.Name) `
        -Expected $expectedSecretNames `
        -Description "Production secret definitions"
    Assert-ExactSet `
        -Actual @($config.networks.PSObject.Properties.Name) `
        -Expected @("app", "data", "egress") `
        -Description "Production networks"

    $expectedNetworks = @{
        "postgres" = @("data")
        "database-role-bootstrap" = @("data")
        "redis" = @("data")
        "flyway" = @("data")
        "discovery-server" = @("app")
        "api-gateway" = @("app")
        "auth-service" = @("app", "data", "egress")
        "tenant-service" = @("app", "data", "egress")
        "audit-service" = @("app", "data")
    }
    $directSecretEnvironmentNames = @(
        "POSTGRES_PASSWORD",
        "PGPASSWORD",
        "AUTH_DB_PASSWORD",
        "TENANT_DB_PASSWORD",
        "AUDIT_DB_PASSWORD",
        "PROVISIONER_DB_PASSWORD",
        "AUTH_JWT_SECRET",
        "TENANT_JWT_SECRET",
        "AUDIT_INTERNAL_KEY",
        "SPRING_DATASOURCE_PASSWORD",
        "TENANT_PROVISIONING_DATASOURCE_PASSWORD",
        "SPRING_DATA_REDIS_PASSWORD",
        "SPRING_MAIL_PASSWORD",
        "PGADMIN_PASSWORD"
    )
    $expectedSecretGrants = @{
        "postgres" = @(
            "postgres_admin_password=>/run/secrets/postgres_admin_password"
        )
        "database-role-bootstrap" = @(
            "postgres_admin_password=>/run/secrets/postgres_admin_password",
            "auth_db_password=>/run/secrets/auth_db_password",
            "tenant_db_password=>/run/secrets/tenant_db_password",
            "audit_db_password=>/run/secrets/audit_db_password",
            "provisioner_db_password=>/run/secrets/provisioner_db_password"
        )
        "redis" = @(
            "redis_password=>/run/secrets/redis_password"
        )
        "flyway" = @(
            "flyway_configuration=>flyway.conf"
        )
        "discovery-server" = @()
        "api-gateway" = @(
            "auth_jwt_secret=>tenant.resolution.jwt-hmac-secret"
        )
        "auth-service" = @(
            "auth_db_password=>spring.datasource.password",
            "auth_jwt_secret=>auth.jwt.secret",
            "audit_internal_key=>auth.multitenancy.audit-internal-key",
            "redis_password=>spring.data.redis.password",
            "smtp_password=>spring.mail.password"
        )
        "tenant-service" = @(
            "tenant_db_password=>spring.datasource.password",
            "provisioner_db_password=>tenant.provisioning-database.password",
            "auth_jwt_secret=>tenant.security.jwt-secret",
            "smtp_password=>spring.mail.password"
        )
        "audit-service" = @(
            "audit_db_password=>spring.datasource.password",
            "audit_internal_key=>audit.internal-key"
        )
    }

    foreach ($serviceName in $expectedServices) {
        $service = Get-NamedProperty $services $serviceName "Compose service"
        if ($service.privileged -eq $true) {
            throw "Production service '$serviceName' must not be privileged."
        }
        if (@(Get-OptionalArray $service "cap_add").Count -ne 0) {
            throw "Production service '$serviceName' must not add Linux capabilities."
        }
        if ($null -ne $service.PSObject.Properties["build"]) {
            throw "Production service '$serviceName' must use a published image, not build locally."
        }
        if ($service.image -notmatch "@sha256:[0-9a-f]{64}$") {
            throw "Production service '$serviceName' image must be pinned by a sha256 digest."
        }
        if ($Strict -and (
                $service.image -match "replace-me" -or
                $service.image -match "@sha256:0{64}$"
        )) {
            throw "Production service '$serviceName' still uses an example image placeholder."
        }

        $ports = @(Get-OptionalArray $service "ports")
        if ($serviceName -eq "api-gateway") {
            if ($ports.Count -ne 1) {
                throw "The production gateway must publish exactly one port."
            }
            if ($ports[0].target -ne 8080 -or
                $ports[0].host_ip -ne "127.0.0.1" -or
                $ports[0].protocol -ne "tcp") {
                throw "The production gateway must bind TCP port 8080 to 127.0.0.1 behind TLS ingress."
            }
            $ingressPolicyLabelName = "com.techdesksystem.security.loopback-ingress-firewall-enforced"
            $ingressPolicyLabel = $service.labels.PSObject.Properties[$ingressPolicyLabelName]
            if ($null -eq $ingressPolicyLabel -or $ingressPolicyLabel.Value -cnotmatch "^(true|false)$") {
                throw "API Gateway must declare the external loopback-ingress firewall status."
            }
            if ($Strict -and $ingressPolicyLabel.Value -cne "true") {
                throw "Strict deployment requires an external firewall around loopback gateway ingress."
            }
        }
        elseif ($ports.Count -ne 0) {
            throw "Production service '$serviceName' must not publish a host port."
        }

        $networkNames = @()
        if ($null -ne $service.networks) {
            $networkNames = @($service.networks.PSObject.Properties.Name)
        }
        Assert-ExactSet `
            -Actual $networkNames `
            -Expected $expectedNetworks[$serviceName] `
            -Description "Networks for '$serviceName'"

        $actualSecretGrants = @()
        if ($null -ne $service.PSObject.Properties["secrets"]) {
            foreach ($secretGrant in @($service.secrets)) {
                if ($null -ne $secretGrant) {
                    $actualSecretGrants += "$($secretGrant.source)=>$($secretGrant.target)"
                }
            }
        }
        if ($actualSecretGrants.Count -ne $expectedSecretGrants[$serviceName].Count) {
            throw "Secret grants for '$serviceName' have an unexpected count."
        }
        Assert-ExactSet `
            -Actual $actualSecretGrants `
            -Expected $expectedSecretGrants[$serviceName] `
            -Description "Secret grants for '$serviceName'"

        if ($null -ne $service.environment) {
            foreach ($secretName in $directSecretEnvironmentNames) {
                if ($null -ne $service.environment.PSObject.Properties[$secretName]) {
                    throw "Production service '$serviceName' leaks '$secretName' through its environment."
                }
            }
            foreach ($value in $service.environment.PSObject.Properties.Value) {
                if ([string]$value -like "*unused-by-production-secret-overlay*") {
                    throw "Production service '$serviceName' retained a compatibility sentinel."
                }
            }
        }
    }

    foreach ($networkName in @("app", "data")) {
        $network = Get-NamedProperty $config.networks $networkName "Compose network"
        if ($network.internal -ne $true) {
            throw "Production network '$networkName' must be internal."
        }
    }
    $egressNetwork = Get-NamedProperty $config.networks "egress" "Compose network"
    if ($egressNetwork.internal -eq $true) {
        throw "Production network 'egress' must provide SMTP connectivity."
    }

    $javaHealthPorts = @{
        "discovery-server" = 9761
        "api-gateway" = 9080
        "auth-service" = 9081
        "tenant-service" = 9082
        "audit-service" = 9083
    }
    foreach ($entry in $javaHealthPorts.GetEnumerator()) {
        $service = Get-NamedProperty $services $entry.Key "Compose service"
        if ($service.read_only -ne $true -or $service.init -ne $true) {
            throw "Production Java service '$($entry.Key)' must be read-only and use an init process."
        }
        if ($service.user -ne "10001:10001") {
            throw "Production Java service '$($entry.Key)' must run as UID/GID 10001, not root."
        }
        if ($service.restart -ne "unless-stopped") {
            throw "Production Java service '$($entry.Key)' must restart unless stopped."
        }
        if (@($service.cap_drop) -notcontains "ALL") {
            throw "Production Java service '$($entry.Key)' must drop all Linux capabilities."
        }
        $securityOptions = @(Get-OptionalArray $service "security_opt")
        if ($securityOptions.Count -ne 1 -or
            $securityOptions[0] -ne "no-new-privileges:true") {
            throw "Production Java service '$($entry.Key)' must use only no-new-privileges security hardening."
        }
        foreach ($fieldName in @("volumes", "devices", "device_cgroup_rules", "group_add")) {
            $configuredValues = @(Get-OptionalArray $service $fieldName)
            if ($configuredValues.Count -ne 0) {
                throw "Production Java service '$($entry.Key)' must not configure '$fieldName'."
            }
        }
        foreach ($fieldName in @("pid", "ipc", "cgroup", "uts", "userns_mode", "use_api_socket")) {
            $field = $service.PSObject.Properties[$fieldName]
            if ($null -ne $field -and
                $null -ne $field.Value -and
                [string]$field.Value -ne "" -and
                $field.Value -ne $false) {
                throw "Production Java service '$($entry.Key)' must not share host namespace or API setting '$fieldName'."
            }
        }
        $temporaryMounts = @(Get-OptionalArray $service "tmpfs")
        if ($temporaryMounts.Count -ne 1 -or
            $temporaryMounts[0] -ne "/tmp:rw,noexec,nosuid,size=128m") {
            throw "Production Java service '$($entry.Key)' must mount a 128 MiB noexec/nosuid /tmp tmpfs."
        }

        $expectedProbe = "http://127.0.0.1:$($entry.Value)/actuator/health"
        $expectedHealthCommand = @(
            "CMD",
            "curl",
            "--fail",
            "--silent",
            "--show-error",
            "--max-time",
            "5",
            $expectedProbe
        )
        $actualHealthCommand = @($service.healthcheck.test)
        if (($actualHealthCommand -join "`0") -cne ($expectedHealthCommand -join "`0")) {
            throw "Production Java service '$($entry.Key)' must execute the exact private management health probe."
        }
    }

    $postgres = Get-NamedProperty $services "postgres" "Compose service"
    Assert-ExactSet `
        -Actual @($postgres.environment.PSObject.Properties.Name) `
        -Expected @("POSTGRES_DB", "POSTGRES_PASSWORD_FILE", "POSTGRES_USER") `
        -Description "PostgreSQL environment"
    if ($postgres.environment.POSTGRES_PASSWORD_FILE -cne "/run/secrets/postgres_admin_password") {
        throw "PostgreSQL must read its administrator password from a mounted secret."
    }
    $postgresCommand = @(Get-OptionalArray $postgres "command")
    if ($postgresCommand.Count -ne 0 -or
        $null -ne $postgres.entrypoint) {
        throw "PostgreSQL must retain its image authentication command and entrypoint."
    }

    $redis = Get-NamedProperty $services "redis" "Compose service"
    $expectedRedisCommand = @(
        "sh",
        "-ec",
        'exec redis-server --appendonly yes --requirepass "$$(cat /run/secrets/redis_password)"'
    )
    if ((@($redis.command) -join "`0") -cne ($expectedRedisCommand -join "`0")) {
        throw "Redis must start with append-only persistence and its mounted authentication secret."
    }
    $expectedRedisHealthCommand = @(
        "CMD-SHELL",
        'REDISCLI_AUTH="$$(cat /run/secrets/redis_password)" redis-cli ping'
    )
    if ((@($redis.healthcheck.test) -join "`0") -cne ($expectedRedisHealthCommand -join "`0")) {
        throw "Redis health checks must execute an authenticated ping with the mounted secret."
    }
    if (($null -ne $redis.PSObject.Properties["environment"] -and
            @($redis.environment.PSObject.Properties).Count -ne 0) -or
        $null -ne $redis.entrypoint) {
        throw "Redis must not accept environment or entrypoint authentication overrides."
    }

    $flyway = Get-NamedProperty $services "flyway" "Compose service"
    $flywayCommand = @($flyway.command)
    $expectedFlywayCommand = @("-configFiles=/run/secrets/flyway.conf", "migrate")
    if (($flywayCommand -join "`0") -cne ($expectedFlywayCommand -join "`0")) {
        throw "Flyway must execute only migrate with its mounted configuration secret."
    }
    if ($null -ne $flyway.PSObject.Properties["environment"] -and
        @($flyway.environment.PSObject.Properties).Count -ne 0) {
        throw "Flyway must not accept higher-precedence environment configuration."
    }
    if ($null -ne $flyway.entrypoint) {
        throw "Flyway must retain its image entrypoint."
    }

    $bootstrap = Get-NamedProperty $services "database-role-bootstrap" "Compose service"
    $bootstrapCommand = @($bootstrap.command) -join " "
    if ($bootstrapCommand -match "--set") {
        throw "Database role bootstrap must not pass credentials as psql arguments."
    }

    foreach ($serviceName in @("auth-service", "tenant-service")) {
        $service = Get-NamedProperty $services $serviceName "Compose service"
        if ($service.environment.SPRING_MAIL_HOST -eq "mailhog") {
            throw "Production service '$serviceName' must not use MailHog."
        }
        foreach ($setting in @(
                "SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH",
                "SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE",
                "SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED"
        )) {
            if ($service.environment.$setting -cne "true") {
                throw "Production service '$serviceName' must enable SMTP auth and required STARTTLS."
            }
        }

        $egressPolicyLabelName = "com.techdesksystem.security.external-egress-policy-enforced"
        $egressPolicyLabel = $service.labels.PSObject.Properties[$egressPolicyLabelName]
        if ($null -eq $egressPolicyLabel -or $egressPolicyLabel.Value -cnotmatch "^(true|false)$") {
            throw "Production service '$serviceName' must declare the external egress-policy status."
        }
        if ($Strict -and $egressPolicyLabel.Value -cne "true") {
            throw "Strict deployment requires external destination-level egress enforcement for '$serviceName'."
        }
    }
    if ($services.'auth-service'.environment.AUTH_PASSWORD_RESET_URL -notmatch "^https://") {
        throw "Production password reset URLs must use HTTPS."
    }
    foreach ($setting in @("TENANT_PORTAL_URL_TEMPLATE", "TENANT_INVITATION_URL")) {
        if ($services.'tenant-service'.environment.$setting -notmatch "^https://") {
            throw "Production tenant URL '$setting' must use HTTPS."
        }
    }
    $tenantPortalTemplate = [string]$services.'tenant-service'.environment.TENANT_PORTAL_URL_TEMPLATE
    $resolvedTenantPortalUri = $null
    if ([regex]::Matches($tenantPortalTemplate, "%s").Count -ne 1 -or
        $tenantPortalTemplate -notmatch "(?i)^https://(?:[a-z0-9-]+\.)*[a-z0-9-]*%s[a-z0-9-]*(?:\.[a-z0-9-]+)+(?::[0-9]{1,5})?(/|$)" -or
        -not [Uri]::TryCreate(
            $tenantPortalTemplate.Replace("%s", "tenant-slug"),
            [UriKind]::Absolute,
            [ref]$resolvedTenantPortalUri
        ) -or
        $resolvedTenantPortalUri.Scheme -ne "https" -or
        $resolvedTenantPortalUri.HostNameType -ne [UriHostNameType]::Dns) {
        throw "TENANT_PORTAL_URL_TEMPLATE must contain exactly one '%s' placeholder in a valid HTTPS DNS host."
    }

    if ($Strict) {
        $repositoryFullPath = [IO.Path]::GetFullPath($repositoryRoot).TrimEnd("\", "/")
        $secretPaths = @{}
        foreach ($secretProperty in $config.secrets.PSObject.Properties) {
            $secretPath = [IO.Path]::GetFullPath([string]$secretProperty.Value.file)
            if (-not (Test-Path -LiteralPath $secretPath -PathType Leaf)) {
                throw "Secret file '$($secretProperty.Name)' does not exist."
            }
            if ($secretPath.StartsWith($repositoryFullPath, [StringComparison]::OrdinalIgnoreCase)) {
                throw "Secret file '$($secretProperty.Name)' must be stored outside the repository."
            }
            $secretPaths[$secretProperty.Name] = $secretPath
        }

        $scalarSecretMinimums = [ordered]@{
            "postgres_admin_password" = 16
            "auth_db_password" = 16
            "tenant_db_password" = 16
            "audit_db_password" = 16
            "provisioner_db_password" = 16
            "auth_jwt_secret" = 32
            "audit_internal_key" = 32
            "redis_password" = 16
            "smtp_password" = 16
        }
        $secretValues = @{}
        foreach ($secretName in $scalarSecretMinimums.Keys) {
            $secretValues[$secretName] = Read-StrongScalarSecret `
                -Path $secretPaths[$secretName] `
                -Name $secretName `
                -MinimumUtf8Bytes $scalarSecretMinimums[$secretName]
        }

        $duplicateSecretGroups = @($secretValues.GetEnumerator() |
            Group-Object -Property Value |
            Where-Object { $_.Count -gt 1 })
        if ($duplicateSecretGroups.Count -gt 0) {
            $duplicateNames = @($duplicateSecretGroups[0].Group | ForEach-Object { $_.Key }) -join ", "
            throw "Independent production secrets must not reuse a value: $duplicateNames."
        }

        $flywayContent = [IO.File]::ReadAllText($secretPaths["flyway_configuration"])
        if ([string]::IsNullOrWhiteSpace($flywayContent)) {
            throw "Secret file 'flyway_configuration' must not be empty."
        }
        if ([Text.Encoding]::UTF8.GetByteCount($flywayContent) -gt 65536) {
            throw "Secret file 'flyway_configuration' is unexpectedly large."
        }
        $expectedFlywaySettingNames = @(
            "flyway.url",
            "flyway.user",
            "flyway.password",
            "flyway.schemas",
            "flyway.defaultSchema",
            "flyway.locations",
            "flyway.connectRetries",
            "flyway.cleanDisabled",
            "flyway.validateMigrationNaming",
            "flyway.validateOnMigrate"
        )
        $observedFlywaySettingNames = @()
        foreach ($line in $flywayContent -split "`r?`n") {
            if ([string]::IsNullOrWhiteSpace($line)) {
                continue
            }
            if ($line.Contains("\") -or
                $line -cnotmatch "^(?<name>[A-Za-z][A-Za-z0-9.]*)=(?<value>.*)$") {
                throw "flyway.conf must contain only unescaped key=value settings."
            }
            $settingName = $Matches["name"]
            if ($expectedFlywaySettingNames -cnotcontains $settingName) {
                throw "flyway.conf contains unsupported setting '$settingName'."
            }
            $observedFlywaySettingNames += $settingName
        }
        if ($observedFlywaySettingNames.Count -ne $expectedFlywaySettingNames.Count) {
            throw "flyway.conf must define each required setting exactly once."
        }
        Assert-ExactSet `
            -Actual $observedFlywaySettingNames `
            -Expected $expectedFlywaySettingNames `
            -Description "flyway.conf settings"

        $flywayUrl = Get-RequiredFlywaySetting -Content $flywayContent -Name "flyway.url"
        $flywayUser = Get-RequiredFlywaySetting -Content $flywayContent -Name "flyway.user"
        $flywayPassword = Get-RequiredFlywaySetting -Content $flywayContent -Name "flyway.password"
        $flywaySchemas = Get-RequiredFlywaySetting -Content $flywayContent -Name "flyway.schemas"
        $flywayDefaultSchema = Get-RequiredFlywaySetting -Content $flywayContent -Name "flyway.defaultSchema"
        $flywayLocations = Get-RequiredFlywaySetting -Content $flywayContent -Name "flyway.locations"
        $flywayConnectRetriesValue = Get-RequiredFlywaySetting -Content $flywayContent -Name "flyway.connectRetries"
        $flywayCleanDisabled = Get-RequiredFlywaySetting -Content $flywayContent -Name "flyway.cleanDisabled"
        $flywayValidateMigrationNaming = Get-RequiredFlywaySetting -Content $flywayContent -Name "flyway.validateMigrationNaming"
        $flywayValidateOnMigrate = Get-RequiredFlywaySetting -Content $flywayContent -Name "flyway.validateOnMigrate"

        $postgresDatabase = [string]$postgres.environment.POSTGRES_DB
        $postgresUser = [string]$postgres.environment.POSTGRES_USER
        if ($postgresDatabase -cnotmatch "^[a-z_][a-z0-9_]{0,62}$" -or
            $postgresUser -cnotmatch "^[a-z_][a-z0-9_]{0,62}$") {
            throw "Production PostgreSQL database and administrator role must be safe identifiers."
        }
        $expectedFlywayUrl = "jdbc:postgresql://postgres:5432/$postgresDatabase"
        if ($flywayUrl -cne $expectedFlywayUrl) {
            throw "flyway.conf must target the production PostgreSQL service and database."
        }
        if ($flywayUser -cne $postgresUser) {
            throw "flyway.conf user must match the normalized PostgreSQL administrator role."
        }
        if ($flywaySchemas -cne "public" -or
            $flywayDefaultSchema -cne "public" -or
            $flywayLocations -cne "filesystem:/flyway/sql") {
            throw "flyway.conf must migrate only the expected public schema from /flyway/sql."
        }
        $flywayConnectRetries = 0
        if (-not [int]::TryParse($flywayConnectRetriesValue, [ref]$flywayConnectRetries) -or
            $flywayConnectRetries -lt 1) {
            throw "flyway.conf must configure at least one connection retry."
        }
        if ($flywayCleanDisabled -cne "true" -or
            $flywayValidateMigrationNaming -cne "true" -or
            $flywayValidateOnMigrate -cne "true") {
            throw "flyway.conf must disable clean and enable migration-name and migrate-time validation."
        }
        Assert-StrongSecretValue `
            -Value $flywayPassword `
            -Description "flyway.conf password" `
            -MinimumUtf8Bytes 16
        if ($flywayPassword -cne $secretValues["postgres_admin_password"]) {
            throw "flyway.conf password must match postgres_admin_password."
        }

        $strictPublicSettings = [ordered]@{
            "SMTP_HOST" = $services.'auth-service'.environment.SPRING_MAIL_HOST
            "SMTP_USERNAME" = $services.'auth-service'.environment.SPRING_MAIL_USERNAME
            "AUTH_PASSWORD_RESET_URL" = $services.'auth-service'.environment.AUTH_PASSWORD_RESET_URL
            "AUTH_MAIL_FROM" = $services.'auth-service'.environment.AUTH_MAIL_FROM
            "TENANT_PORTAL_URL_TEMPLATE" = $services.'tenant-service'.environment.TENANT_PORTAL_URL_TEMPLATE
            "TENANT_INVITATION_URL" = $services.'tenant-service'.environment.TENANT_INVITATION_URL
            "TENANT_MAIL_FROM" = $services.'tenant-service'.environment.TENANT_MAIL_FROM
        }
        foreach ($setting in $strictPublicSettings.GetEnumerator()) {
            if ([string]::IsNullOrWhiteSpace([string]$setting.Value) -or
                [string]$setting.Value -match "(?i)(replace[-_ ]?with|example\.(com|org|net)|\.example\.|\.invalid([/:]|$)|localhost|127\.0\.0\.1)") {
                throw "Production setting '$($setting.Key)' still contains an example or local placeholder."
            }
        }
    }

    $mode = if ($Strict) { "strict" } else { "structural example" }
    Write-Output "Production Compose contract verified in $mode mode: one loopback gateway port, private app/data networks, dedicated SMTP egress, mounted secrets, authenticated Redis, TLS mail, immutable images, and hardened Java containers."
}
finally {
    Pop-Location
}
