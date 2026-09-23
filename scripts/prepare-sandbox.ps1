# Build trusted images once. Jobs use immutable image IDs with --pull=never.
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
foreach ($language in @('java', 'python')) {
    docker build --file "$repository/infra/sandbox/$language.Dockerfile" --tag "pairforge-sandbox-${language}:m10" "$repository/infra/sandbox"
    if ($LASTEXITCODE -ne 0) { throw "Sandbox $language image build failed" }
    $imageId = docker image inspect --format '{{.Id}}' "pairforge-sandbox-${language}:m10"
    if ($LASTEXITCODE -ne 0 -or $imageId -notmatch '^sha256:[a-f0-9]{64}$') { throw 'Cannot resolve immutable sandbox image ID' }
    $variable = "PAIRFORGE_SANDBOX_$($language.ToUpperInvariant())_IMAGE"
    [Environment]::SetEnvironmentVariable($variable, $imageId, 'Process')
    if ($env:GITHUB_ENV) { Add-Content -LiteralPath $env:GITHUB_ENV -Value "$variable=$imageId" }
    Write-Host "$variable=$imageId"
}
