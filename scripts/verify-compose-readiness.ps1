[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot

function Get-ServiceConfig {
    param(
        [Parameter(Mandatory)]
        [object]$Services,

        [Parameter(Mandatory)]
        [string]$Name
    )

    $property = $Services.PSObject.Properties[$Name]
    if ($null -eq $property) {
        throw "Compose service '$Name' is missing."
    }

    return $property.Value
}

function Assert-DependencyCondition {
    param(
        [Parameter(Mandatory)]
        [object]$Services,

        [Parameter(Mandatory)]
        [string]$ServiceName,

        [Parameter(Mandatory)]
        [string]$DependencyName,

        [Parameter(Mandatory)]
        [string]$ExpectedCondition
    )

    $service = Get-ServiceConfig -Services $Services -Name $ServiceName
    $dependency = $service.depends_on.PSObject.Properties[$DependencyName]

    if ($null -eq $dependency) {
        throw "'$ServiceName' must depend on '$DependencyName'."
    }

    if ($dependency.Value.condition -ne $ExpectedCondition) {
        throw "'$ServiceName' -> '$DependencyName' must use condition '$ExpectedCondition'."
    }
}

Push-Location $repositoryRoot
try {
    $composeJson = & docker compose --env-file .env.example config --format json
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose config failed with exit code $LASTEXITCODE."
    }

    $config = $composeJson | ConvertFrom-Json
    $services = $config.services
    $javaServices = [ordered]@{
        "discovery-server" = "http://127.0.0.1:8761/actuator/health"
        "api-gateway" = "http://127.0.0.1:8080/actuator/health"
        "auth-service" = "http://127.0.0.1:8081/actuator/health"
        "tenant-service" = "http://127.0.0.1:8082/actuator/health"
        "audit-service" = "http://127.0.0.1:8083/actuator/health"
    }

    foreach ($entry in $javaServices.GetEnumerator()) {
        $service = Get-ServiceConfig -Services $services -Name $entry.Key
        if ($null -eq $service.healthcheck) {
            throw "'$($entry.Key)' must define a health check."
        }

        $probe = @($service.healthcheck.test)
        if ($probe.Count -lt 2 -or $probe[0] -ne "CMD") {
            throw "'$($entry.Key)' health check must use exec-form CMD."
        }
        if ($probe -notcontains "curl" -or $probe -notcontains $entry.Value) {
            throw "'$($entry.Key)' health check must probe '$($entry.Value)' with curl."
        }
        if ($service.healthcheck.start_period -notin @("5m0s", "5m", "300s")) {
            throw "'$($entry.Key)' must retain the five-minute slow-start grace period."
        }
    }

    foreach ($infrastructureService in @("postgres", "redis")) {
        $service = Get-ServiceConfig -Services $services -Name $infrastructureService
        if ($null -eq $service.healthcheck) {
            throw "'$infrastructureService' must define a health check."
        }
    }

    Assert-DependencyCondition $services "flyway" "postgres" "service_healthy"
    Assert-DependencyCondition $services "database-role-bootstrap" "flyway" "service_completed_successfully"
    Assert-DependencyCondition $services "api-gateway" "discovery-server" "service_healthy"

    foreach ($serviceName in @("auth-service", "tenant-service", "audit-service")) {
        Assert-DependencyCondition $services $serviceName "discovery-server" "service_healthy"
        Assert-DependencyCondition $services $serviceName "database-role-bootstrap" "service_completed_successfully"
    }

    Assert-DependencyCondition $services "auth-service" "redis" "service_healthy"

    foreach ($oneShotService in @("flyway", "database-role-bootstrap")) {
        $service = Get-ServiceConfig -Services $services -Name $oneShotService
        if ($service.restart -ne "no") {
            throw "One-shot service '$oneShotService' must keep restart: no."
        }
    }

    Write-Output "Compose readiness contract verified for five Java services and the critical startup dependency graph."
}
finally {
    Pop-Location
}
