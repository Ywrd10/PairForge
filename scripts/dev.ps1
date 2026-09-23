param(
    [Parameter(Mandatory)]
    [ValidateSet('infrastructure', 'backend', 'worker', 'frontend', 'stop')]
    [string]$Service,
    [switch]$Initialize,
    [switch]$Sandbox
)

if ($Sandbox -and $Service -ne 'worker') { throw '-Sandbox applies only to the worker.' }

. (Join-Path $PSScriptRoot 'environment.ps1')
$originalJwtKey = $env:JWT_KEY_HEX
Push-Location $script:PairForgeRoot
try {
    if ($Initialize) { Initialize-PairForgeEnvironment }
    Import-PairForgeEnvironment
    if ($Service -ne 'backend') { $env:JWT_KEY_HEX = $null }
    switch ($Service) {
        'infrastructure' {
            Invoke-PairForgeCommand docker @('compose', 'config', '--quiet')
            Invoke-PairForgeCommand docker @('compose', 'up', '-d', '--wait', '--wait-timeout', '180')
        }
        'stop' {
            # Preserve PostgreSQL/RabbitMQ volumes. Stop app terminals with Ctrl+C.
            Invoke-PairForgeCommand docker @('compose', 'stop')
        }
        'backend' {
            if ($env:JWT_KEY_HEX -notmatch '^[a-fA-F0-9]{64}$') {
                throw 'Initialize the API signing key with scripts/initialize-auth.ps1.'
            }
            Invoke-PairForgeCommand (Join-Path $script:PairForgeRoot 'mvnw.cmd') @(
                '--no-transfer-progress', '-pl', 'backend', 'spring-boot:run',
                '-Dspring-boot.run.profiles=local'
            )
        }
        'worker' {
            if (-not $env:WORKER_DB_PASSWORD) {
                throw 'Provision worker credentials with scripts/provision-worker.ps1 before starting the worker.'
            }
            $bootstrapPassword = $env:POSTGRES_PASSWORD
            try {
                # The worker must not inherit the API/bootstrap database password.
                $env:POSTGRES_PASSWORD = $null
                $workerProfiles = if ($Sandbox) { 'local,sandbox' } else { 'local' }
                Invoke-PairForgeCommand (Join-Path $script:PairForgeRoot 'mvnw.cmd') @(
                    '--no-transfer-progress', '-pl', 'execution-worker', 'spring-boot:run',
                    "-Dspring-boot.run.profiles=$workerProfiles"
                )
            } finally { $env:POSTGRES_PASSWORD = $bootstrapPassword }
        }
        'frontend' {
            $originalApiBase = $env:VITE_API_BASE_URL
            Push-Location (Join-Path $script:PairForgeRoot 'frontend')
            try {
                if (-not $env:VITE_API_BASE_URL) {
                    $apiPort = if ($env:API_PORT) { $env:API_PORT } else { '8080' }
                    $env:VITE_API_BASE_URL = "http://127.0.0.1:$apiPort"
                }
                $frontendPort = if ($env:FRONTEND_PORT) { $env:FRONTEND_PORT } else { '5173' }
                Invoke-PairForgeCommand npm.cmd @('run', 'dev', '--', '--port', $frontendPort)
            } finally { $env:VITE_API_BASE_URL = $originalApiBase; Pop-Location }
        }
    }
} finally {
    $env:JWT_KEY_HEX = $originalJwtKey
    Pop-Location
}
