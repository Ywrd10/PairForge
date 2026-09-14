param(
    # Explicit opt-in: temporarily stops each PairForge Compose dependency.
    [switch]$CheckOutages
)

. (Join-Path $PSScriptRoot 'environment.ps1')
Add-Type -AssemblyName System.Net.Http
Import-PairForgeEnvironment
$apiPort = if ($env:API_PORT) { $env:API_PORT } else { '8080' }
$workerPort = if ($env:WORKER_PORT) { $env:WORKER_PORT } else { '8081' }
$frontendPort = if ($env:FRONTEND_PORT) { $env:FRONTEND_PORT } else { '5173' }
$apiUrl = "http://127.0.0.1:$apiPort"
$workerUrl = "http://127.0.0.1:$workerPort"
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.UseProxy = $false
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(10)

function Assert-Http {
    param([string]$Url, [int]$Code, [string]$Status = '')
    $response = $client.GetAsync($Url).GetAwaiter().GetResult()
    try {
        if ([int]$response.StatusCode -ne $Code) {
            throw "$Url expected HTTP $Code, received $([int]$response.StatusCode)."
        }
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if ($Status) {
            $health = $body | ConvertFrom-Json
            if ($health.status -ne $Status) { throw "$Url did not report $Status." }
            if ($health.PSObject.Properties.Name -contains 'components' -or
                $health.PSObject.Properties.Name -contains 'details') {
                throw "$Url exposed internal health details."
            }
        }
        return $body
    } finally { $response.Dispose() }
}

function Wait-Probe {
    param([string]$Url, [int]$Code, [string]$Status)
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        try { Assert-Http $Url $Code $Status | Out-Null; return }
        catch {
            if ([DateTime]::UtcNow -ge $deadline) { throw }
            Start-Sleep -Seconds 1
        }
    } while ($true)
}

Push-Location $script:PairForgeRoot
try {
    Invoke-PairForgeCommand docker @('compose', 'config', '--quiet')
    foreach ($baseUrl in @($apiUrl, $workerUrl)) {
        Wait-Probe "$baseUrl/actuator/health" 200 'UP'
        Wait-Probe "$baseUrl/actuator/health/readiness" 200 'UP'
        Assert-Http "$baseUrl/actuator/health/liveness" 200 'UP' | Out-Null
        foreach ($endpoint in @('env', 'configprops', 'heapdump', 'beans', 'metrics', 'loggers', 'shutdown')) {
            Assert-Http "$baseUrl/actuator/$endpoint" 404 | Out-Null
        }
    }
    $html = Assert-Http "http://127.0.0.1:$frontendPort" 200
    if ($html -notmatch '<title>PairForge</title>') { throw 'Frontend did not serve PairForge.' }
    Write-Host 'Startup, health, endpoint exposure, and frontend HTTP checks passed.'

    if ($CheckOutages) {
        foreach ($service in @('postgres', 'redis', 'rabbitmq')) {
            try {
                Invoke-PairForgeCommand docker @('compose', 'stop', '--timeout', '5', $service)
                Wait-Probe "$apiUrl/actuator/health/readiness" 503 'DOWN'
                $workerStatus = if ($service -eq 'redis') { 'UP' } else { 'DOWN' }
                $workerCode = if ($service -eq 'redis') { 200 } else { 503 }
                Wait-Probe "$workerUrl/actuator/health/readiness" $workerCode $workerStatus
                foreach ($baseUrl in @($apiUrl, $workerUrl)) {
                    Assert-Http "$baseUrl/actuator/health/liveness" 200 'UP' | Out-Null
                }
            } finally {
                Invoke-PairForgeCommand docker @('compose', 'up', '-d', '--wait', '--wait-timeout', '180', $service)
                Wait-Probe "$apiUrl/actuator/health/readiness" 200 'UP'
                Wait-Probe "$workerUrl/actuator/health/readiness" 200 'UP'
            }
            Write-Host "$service outage/recovery checks passed."
        }
    }
} finally {
    Pop-Location
    $client.Dispose()
}
