Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Exercise the real initializer in a disposable fixture; never contact Docker.
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('pairforge-provision-test-' + [guid]::NewGuid())
$fixtureScripts = Join-Path $fixture 'scripts'
[void][IO.Directory]::CreateDirectory($fixtureScripts)
$keys = @('POSTGRES_PASSWORD', 'RABBITMQ_PASSWORD', 'WORKER_DB_USER', 'WORKER_DB_PASSWORD')
$savedEnvironment = @{}
foreach ($key in $keys) {
    $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    [Environment]::SetEnvironmentVariable($key, $null, 'Process')
}
$dockerSpy = @{ Calls = 0 }
function docker {
    $input | Out-Null
    $dockerSpy.Calls++
    $global:LASTEXITCODE = 0
}

try {
    foreach ($name in @('environment.ps1', 'provision-worker.ps1', 'provision-worker.sql')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $fixtureScripts
    }
    $envPath = Join-Path $fixture '.env'
    foreach ($newline in @("`n", "`r`n")) {
        foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $null, 'Process') }
        $databasePassword = [guid]::NewGuid().ToString('N')
        $brokerPassword = [guid]::NewGuid().ToString('N')
        $original = @("POSTGRES_PASSWORD=$databasePassword", "RABBITMQ_PASSWORD=$brokerPassword",
            'WORKER_DB_USER=pairforge_worker', 'WORKER_DB_PASSWORD=') -join $newline
        [IO.File]::WriteAllText($envPath, $original + $newline)
        & (Join-Path $fixtureScripts 'provision-worker.ps1') -InitializeCredentials
        $updated = [IO.File]::ReadAllText($envPath)
        if ($updated -notmatch '(?m)^WORKER_DB_PASSWORD=[a-f0-9]{48}\r?$' -or
            -not $updated.Contains("POSTGRES_PASSWORD=$databasePassword") -or
            -not $updated.Contains("RABBITMQ_PASSWORD=$brokerPassword")) {
            throw 'Initialization failed to add worker credentials while preserving existing secrets.'
        }
        $refused = $false
        try { & (Join-Path $fixtureScripts 'provision-worker.ps1') -InitializeCredentials }
        catch {
            if ($_.Exception.Message -notlike 'Worker credentials already exist*') { throw }
            $refused = $true
        }
        if (-not $refused -or [IO.File]::ReadAllText($envPath) -cne $updated) {
            throw 'Repeat initialization must refuse to overwrite credentials.'
        }
    }
    if ($dockerSpy.Calls -ne 2) { throw 'Unexpected provisioning calls.' }
    Write-Host 'Worker credential tests passed: LF, CRLF, secret preservation, overwrite refusal.'
} finally {
    foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key], 'Process') }
    # Remove only the known fixture files, then their empty directories.
    foreach ($name in @('environment.ps1', 'provision-worker.ps1', 'provision-worker.sql')) {
        Remove-Item -LiteralPath (Join-Path $fixtureScripts $name) -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath (Join-Path $fixture '.env') -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $fixtureScripts
    Remove-Item -LiteralPath $fixture
}
