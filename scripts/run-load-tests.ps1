#Requires -Version 7.4
param([Parameter(Mandatory)][ValidateSet('smoke','local')][string]$Profile)
$ErrorActionPreference='Stop'
$repository=Split-Path $PSScriptRoot -Parent
. "$PSScriptRoot/check-ci-prerequisites.ps1"
Assert-CiPrerequisites -Frontend
Push-Location $repository
try {
    & "$PSScriptRoot/prepare-sandbox.ps1"
    $maven=if($IsWindows){'.\mvnw.cmd'}else{'./mvnw'}
    & $maven --batch-mode --no-transfer-progress -DskipTests package
    if($LASTEXITCODE -ne 0){throw 'Load fixture packaging failed'}
    Push-Location (Join-Path $repository 'frontend')
    try {
        & npm ci
        if($LASTEXITCODE -ne 0){throw 'Locked dependency installation failed'}
        & npm run test:load
        if($LASTEXITCODE -ne 0){throw 'Harness tests failed'}
        & npm run "load:$Profile"
        if($LASTEXITCODE -ne 0){throw 'Load scenario failed; inspect the sanitized report under .tmp/load'}
    } finally { Pop-Location }
} finally { Pop-Location }
