Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('pairforge-auth-test-' + [guid]::NewGuid())
$fixtureScripts = Join-Path $fixture 'scripts'
[void][IO.Directory]::CreateDirectory($fixtureScripts)
[void][IO.Directory]::CreateDirectory((Join-Path $fixture 'frontend'))
$keys = @('POSTGRES_PASSWORD', 'RABBITMQ_PASSWORD', 'WORKER_DB_PASSWORD', 'JWT_KEY_HEX')
$saved = @{}
foreach ($key in $keys) { $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
try {
    foreach ($name in @('initialize-auth.ps1', 'environment.ps1', 'dev.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $fixtureScripts
    }
    # Stub only process launch, while running the actual environment and dev scripts.
    $stub = @'
function Invoke-PairForgeCommand {
    param([string]$Executable, [string[]]$Arguments)
    if ($Arguments -contains 'backend') {
        if ($env:JWT_KEY_HEX -notmatch '^[a-f0-9]{64}$') { throw 'API did not receive its key.' }
    } elseif ($env:JWT_KEY_HEX) { throw 'Non-API process inherited the signing key.' }
}
'@
    [IO.File]::AppendAllText((Join-Path $fixtureScripts 'environment.ps1'), "`n" + $stub)
    $envPath = Join-Path $fixture '.env'
    foreach ($newline in @("`n", "`r`n")) {
        foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $null, 'Process') }
        $secret = [guid]::NewGuid().ToString('N')
        $original = @("POSTGRES_PASSWORD=$secret", "RABBITMQ_PASSWORD=$secret",
            "WORKER_DB_PASSWORD=$secret", 'JWT_KEY_HEX=') -join $newline
        [IO.File]::WriteAllText($envPath, $original + $newline)
        & (Join-Path $fixtureScripts 'initialize-auth.ps1')
        $updated = [IO.File]::ReadAllText($envPath)
        if ($updated -notmatch '(?m)^JWT_KEY_HEX=[a-f0-9]{64}\r?$' -or
            -not $updated.Contains("POSTGRES_PASSWORD=$secret")) { throw 'Invalid initialization.' }
        $refused = $false
        try { & (Join-Path $fixtureScripts 'initialize-auth.ps1') }
        catch { if ($_.Exception.Message -notlike 'JWT_KEY_HEX already exists*') { throw }; $refused = $true }
        if (-not $refused -or [IO.File]::ReadAllText($envPath) -cne $updated) { throw 'Existing key was overwritten.' }
        foreach ($service in @('backend', 'worker', 'frontend')) {
            & (Join-Path $fixtureScripts 'dev.ps1') -Service $service
        }
        if ($env:JWT_KEY_HEX) { throw 'Launcher did not restore the caller environment.' }
    }
    Write-Host 'Auth environment tests passed: LF/CRLF, preservation, overwrite refusal, API-only signing key.'
} finally {
    foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    foreach ($name in @('initialize-auth.ps1', 'environment.ps1', 'dev.ps1')) {
        Remove-Item -LiteralPath (Join-Path $fixtureScripts $name) -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath (Join-Path $fixture '.env') -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $fixture 'frontend')
    Remove-Item -LiteralPath $fixtureScripts
    Remove-Item -LiteralPath $fixture
}
