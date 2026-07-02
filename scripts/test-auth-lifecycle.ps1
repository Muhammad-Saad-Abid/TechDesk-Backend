param(
    [string]$GatewayBaseUrl = "http://localhost:8080",
    [string]$DatabaseUser = "techdesk",
    [string]$DatabaseName = "techdesk"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
$projectRoot = Split-Path -Parent $PSScriptRoot
$runId = "{0}{1}" -f (
    Get-Date -Format "yyyyMMddHHmmss"
), ([Guid]::NewGuid().ToString("N").Substring(0, 6))
$tenantSlug = "e2e-$runId"
$tenantId = "tenant_$($tenantSlug.Replace('-', '_'))"
$tenantHost = "$tenantSlug.techdesk.local"
$email = "auth-e2e-$runId@example.com"
$password = "E2eSecurePassword123!"
$userId = $null
$redisKey = $null
$client = [System.Net.Http.HttpClient]::new()

function Invoke-GatewayRequest {
    param(
        [string]$Path,
        [hashtable]$Body,
        [bool]$UseTenantHost = $true,
        [string]$AccessToken = $null
    )

    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post,
        "$GatewayBaseUrl/auth-service/api/auth/$Path"
    )

    if ($UseTenantHost) {
        $request.Headers.Host = $tenantHost
    }

    if ($AccessToken) {
        $request.Headers.Authorization =
            [System.Net.Http.Headers.AuthenticationHeaderValue]::new(
                "Bearer",
                $AccessToken
            )
    }

    $json = $Body | ConvertTo-Json -Compress
    $request.Content = [System.Net.Http.StringContent]::new(
        $json,
        [System.Text.Encoding]::UTF8,
        "application/json"
    )

    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $parsedBody = if ($content) {
            $content | ConvertFrom-Json
        } else {
            $null
        }

        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Body = $parsedBody
            RawBody = $content
        }
    } finally {
        $request.Dispose()
    }
}

function Assert-Status {
    param(
        $Response,
        [int]$Expected,
        [string]$Step
    )

    if ($Response.StatusCode -ne $Expected) {
        throw "$Step expected HTTP $Expected but received $($Response.StatusCode): $($Response.RawBody)"
    }
}

function Get-Sha256Hex {
    param([string]$Value)

    $sha256 = [System.Security.Cryptography.SHA256]::Create()

    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        $hash = $sha256.ComputeHash($bytes)
        return -join ($hash | ForEach-Object { $_.ToString("x2") })
    } finally {
        $sha256.Dispose()
    }
}

try {
    $register = Invoke-GatewayRequest -Path "register" -Body @{
        email = $email
        password = $password
        firstName = "Auth"
        lastName = "Lifecycle"
    }
    Assert-Status $register 201 "Register"
    $userId = [long]$register.Body.userId

    if ($register.Body.tenantId -ne $tenantId) {
        throw "Register returned tenant '$($register.Body.tenantId)' instead of '$tenantId'."
    }

    $login = Invoke-GatewayRequest -Path "login" -Body @{
        email = $email
        password = $password
    }
    Assert-Status $login 200 "Login"

    if (-not $login.Body.accessToken -or -not $login.Body.refreshToken) {
        throw "Login did not return both access and refresh tokens."
    }

    # No tenant subdomain here: the gateway must resolve the tenant from the
    # signed access JWT and inject X-Tenant-ID before forwarding the request.
    $rotation = Invoke-GatewayRequest `
        -Path "refresh-token" `
        -Body @{ refreshToken = $login.Body.refreshToken } `
        -UseTenantHost $false `
        -AccessToken $login.Body.accessToken
    Assert-Status $rotation 200 "Refresh rotation"

    if ($rotation.Body.refreshToken -eq $login.Body.refreshToken) {
        throw "Refresh rotation returned the original one-time token."
    }

    $replay = Invoke-GatewayRequest `
        -Path "refresh-token" `
        -Body @{ refreshToken = $login.Body.refreshToken } `
        -UseTenantHost $false `
        -AccessToken $login.Body.accessToken
    Assert-Status $replay 401 "Refresh replay"

    if ($replay.Body.code -ne "REFRESH_TOKEN_REUSE_DETECTED") {
        throw "Replay returned unexpected error code '$($replay.Body.code)'."
    }

    # Create a new session after replay revocation so logout is exercised on
    # an active token rather than on one already revoked by the replay guard.
    $secondLogin = Invoke-GatewayRequest -Path "login" -Body @{
        email = $email
        password = $password
    }
    Assert-Status $secondLogin 200 "Second login"

    $logoutToken = [string]$secondLogin.Body.refreshToken
    $logout = Invoke-GatewayRequest -Path "logout" -Body @{
        refreshToken = $logoutToken
    }
    Assert-Status $logout 204 "Logout"

    $tokenHash = Get-Sha256Hex $logoutToken
    $redisKey = "auth:refresh:blacklist:$tokenHash"

    Push-Location $projectRoot
    try {
        $ttlOutput = & docker compose exec -T redis redis-cli TTL $redisKey

        if ($LASTEXITCODE -ne 0) {
            throw "Unable to read the Redis blacklist TTL."
        }
    } finally {
        Pop-Location
    }

    $ttlSeconds = [long]($ttlOutput | Select-Object -Last 1)

    if ($ttlSeconds -le 0 -or $ttlSeconds -gt 604800) {
        throw "Redis blacklist TTL '$ttlSeconds' is outside the expected 7-day lifetime."
    }

    $loggedOutRefresh = Invoke-GatewayRequest -Path "refresh-token" -Body @{
        refreshToken = $logoutToken
    }
    Assert-Status $loggedOutRefresh 401 "Logged-out token reuse"

    if ($loggedOutRefresh.Body.code -ne "INVALID_REFRESH_TOKEN") {
        throw "Logged-out token returned unexpected error code '$($loggedOutRefresh.Body.code)'."
    }

    Write-Host "PASS: Auth lifecycle completed through the API Gateway."
    Write-Host "PASS: JWT tenant fallback resolved $tenantId."
    Write-Host "PASS: Replay detection revoked active sessions and returned 401."
    Write-Host "PASS: Logout created a Redis blacklist entry with TTL $ttlSeconds seconds."
} finally {
    $client.Dispose()

    Push-Location $projectRoot
    try {
        if ($redisKey) {
            & docker compose exec -T redis redis-cli DEL $redisKey | Out-Null
        }

        if ($userId) {
            $cleanupSql = @"
DELETE FROM audit_logs WHERE entity_id = '$userId';
DELETE FROM auth_users WHERE id = $userId;
"@
            & docker compose exec -T postgres psql `
                -U $DatabaseUser `
                -d $DatabaseName `
                -v ON_ERROR_STOP=1 `
                -c $cleanupSql | Out-Null
        }
    } finally {
        Pop-Location
    }
}
