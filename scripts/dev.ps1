param(
    [Parameter(Mandatory)]
    [ValidateSet('infrastructure', 'backend', 'worker', 'frontend', 'stop')]
    [string]$Service,
    [switch]$Initialize
)

. (Join-Path $PSScriptRoot 'environment.ps1')
Push-Location $script:PairForgeRoot
try {
    if ($Initialize) { Initialize-PairForgeEnvironment }
    Import-PairForgeEnvironment
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
            Invoke-PairForgeCommand (Join-Path $script:PairForgeRoot 'mvnw.cmd') @(
                '--no-transfer-progress', '-pl', 'backend', 'spring-boot:run',
                '-Dspring-boot.run.profiles=local'
            )
        }
        'worker' {
            Invoke-PairForgeCommand (Join-Path $script:PairForgeRoot 'mvnw.cmd') @(
                '--no-transfer-progress', '-pl', 'execution-worker', 'spring-boot:run',
                '-Dspring-boot.run.profiles=local'
            )
        }
        'frontend' {
            Push-Location (Join-Path $script:PairForgeRoot 'frontend')
            try {
                $frontendPort = if ($env:FRONTEND_PORT) { $env:FRONTEND_PORT } else { '5173' }
                Invoke-PairForgeCommand npm.cmd @('run', 'dev', '--', '--port', $frontendPort)
            } finally { Pop-Location }
        }
    }
} finally { Pop-Location }
