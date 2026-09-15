Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:PairForgeRoot = Split-Path -Parent $PSScriptRoot

function Initialize-PairForgeEnvironment {
    $envPath = Join-Path $script:PairForgeRoot '.env'
    if (Test-Path -LiteralPath $envPath) {
        throw '.env already exists; initialization will not overwrite it.'
    }
    $template = [IO.File]::ReadAllText((Join-Path $script:PairForgeRoot '.env.example'))
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        foreach ($name in @('POSTGRES_PASSWORD', 'RABBITMQ_PASSWORD', 'WORKER_DB_PASSWORD', 'JWT_KEY_HEX')) {
            $length = if ($name -eq 'JWT_KEY_HEX') { 32 } else { 24 }
            $bytes = New-Object byte[] $length
            $generator.GetBytes($bytes)
            $value = ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
            $template = [regex]::Replace($template, "(?m)^$name=\r?$", "$name=$value")
        }
        [IO.File]::WriteAllText($envPath, $template)
    } finally {
        $generator.Dispose()
    }
    Write-Host 'Created ignored .env with unique local credentials.'
}

function Import-PairForgeEnvironment {
    $envPath = Join-Path $script:PairForgeRoot '.env'
    $allowed = @(
        'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD', 'POSTGRES_PORT',
        'WORKER_DB_USER', 'WORKER_DB_PASSWORD',
        'JWT_KEY_HEX',
        'REDIS_PORT', 'RABBITMQ_USER', 'RABBITMQ_PASSWORD', 'RABBITMQ_PORT',
        'API_PORT', 'WORKER_PORT', 'FRONTEND_PORT'
    )
    if (Test-Path -LiteralPath $envPath) {
        foreach ($line in [IO.File]::ReadAllLines($envPath)) {
            if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
            if ($line -notmatch '^([A-Z_]+)=([A-Za-z0-9_./:-]*)$' -or $allowed -notcontains $Matches[1]) {
                throw '.env must contain supported KEY=VALUE entries only (letters, digits, _, ., /, :, -).'
            }
            $name, $value = $Matches[1], $Matches[2]
            if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name, 'Process'))) {
                [Environment]::SetEnvironmentVariable($name, $value, 'Process')
            }
        }
    }
    foreach ($name in @('POSTGRES_PASSWORD', 'RABBITMQ_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            throw "$name is required. Initialize .env or set the process environment."
        }
    }
    foreach ($name in @('POSTGRES_PORT', 'REDIS_PORT', 'RABBITMQ_PORT', 'API_PORT', 'WORKER_PORT', 'FRONTEND_PORT')) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ($value -and ($value -notmatch '^\d{1,5}$' -or [int]$value -lt 1 -or [int]$value -gt 65535)) {
            throw "$name must be a TCP port from 1 to 65535."
        }
    }
}

function Invoke-PairForgeCommand {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE." }
}
