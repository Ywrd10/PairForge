param([switch]$InitializeCredentials)

. (Join-Path $PSScriptRoot 'environment.ps1')

if ($InitializeCredentials) {
    $envPath = Join-Path $script:PairForgeRoot '.env'
    $contents = [IO.File]::ReadAllText($envPath).TrimEnd() + "`n"
    if ($contents -match '(?m)^WORKER_DB_PASSWORD=[^\r\n]+') {
        throw 'Worker credentials already exist; they will not be overwritten.'
    }
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $bytes = New-Object byte[] 24
        $generator.GetBytes($bytes)
        $password = ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
    } finally { $generator.Dispose() }
    if ($contents -notmatch '(?m)^WORKER_DB_USER=') { $contents += "`nWORKER_DB_USER=pairforge_worker`n" }
    if ($contents -match '(?m)^WORKER_DB_PASSWORD=\r?$') {
        $contents = [regex]::Replace($contents, '(?m)^WORKER_DB_PASSWORD=\r?$', "WORKER_DB_PASSWORD=$password")
    } else { $contents += "WORKER_DB_PASSWORD=$password`n" }
    [IO.File]::WriteAllText($envPath, $contents)
    Write-Host 'Added unique worker credentials to ignored .env; existing credentials preserved.'
}

Import-PairForgeEnvironment
if (-not $env:WORKER_DB_USER) { $env:WORKER_DB_USER = 'pairforge_worker' }
if (-not $env:WORKER_DB_PASSWORD) { throw 'WORKER_DB_PASSWORD is required; use -InitializeCredentials for an existing .env.' }
if ($env:WORKER_DB_PASSWORD -notmatch '^[A-Za-z0-9_./:-]{16,128}$') {
    throw 'WORKER_DB_PASSWORD must be 16-128 characters using the documented environment alphabet.'
}
$adminUser = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'pairforge' }
$database = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { 'pairforge' }
if ($env:WORKER_DB_USER -notmatch '^pairforge_worker[a-z0-9_]*$' -or $env:WORKER_DB_USER -eq $adminUser) {
    throw 'Use a distinct worker role named pairforge_worker (or that prefix with a test suffix).'
}

Push-Location $script:PairForgeRoot
try {
    # Pass the password through stdin, never shell interpolation or process arguments.
    $quotedUser = $env:WORKER_DB_USER.Replace("'", "''")
    $quotedPassword = $env:WORKER_DB_PASSWORD.Replace("'", "''")
    $sql = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'provision-worker.sql'))
    $sql = $sql.Replace('\getenv worker_user WORKER_DB_USER', "\set worker_user '$quotedUser'")
    $sql = $sql.Replace('\getenv worker_password WORKER_DB_PASSWORD', "\set worker_password '$quotedPassword'")
    $sql | & docker compose exec -T postgres psql --quiet -U $adminUser -d $database
    if ($LASTEXITCODE -ne 0) { throw 'Worker role provisioning failed.' }
    Write-Host 'Worker database role provisioned with health-only access.'
} finally { Pop-Location }
