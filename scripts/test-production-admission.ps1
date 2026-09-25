#Requires -Version 7.0
# Isolated Linux filesystem; systemd/readiness are fault-injected, not deployed evidence.
$ErrorActionPreference='Stop'
$image='mcr.microsoft.com/powershell@sha256:62300a213a9293916333df2b014cd3a8f22fb0b0b65f2bb446aaf436bcf8c868'
$fixture=@'
$ErrorActionPreference='Stop'
$global:restartFails=$false
$global:healthy=$true
function global:systemctl { $global:LASTEXITCODE=if($global:restartFails){1}else{0} }
function global:systemd-tmpfiles {
    [void][IO.Directory]::CreateDirectory('/run/pairforge-operations')
    & chmod 755 /run/pairforge-operations
}
function global:Invoke-RestMethod { @{status=if($global:healthy){'UP'}else{'DOWN'}} }
function Reject([scriptblock]$action) {
    $rejected=$false
    try { & $action } catch { $rejected=$true }
    if(-not $rejected) { throw 'Expected operation rejection' }
    if(Test-Path /run/pairforge-operations/execution-enabled) { throw 'Rejected operation left admission open' }
}
Reject { & /scripts/set-production-admission.ps1 -Mode Open }
$global:healthy=$false
Reject { & /scripts/set-production-admission.ps1 -Mode Open -WorkerReadinessVerified }
$global:healthy=$true
& /scripts/set-production-admission.ps1 -Mode Open -WorkerReadinessVerified
if(-not (Test-Path /run/pairforge-operations/execution-enabled -PathType Leaf)) { throw 'Admission did not open' }
$global:restartFails=$true
Reject { & /scripts/set-production-admission.ps1 -Mode Close }
$global:restartFails=$false
& /scripts/set-production-admission.ps1 -Mode Close
if(Test-Path /run/pairforge-operations/execution-enabled) { throw 'Admission did not remain closed' }
Write-Host 'Admission operator checks passed: confirmation required, dependency readiness required, explicit open, restart failure stays closed, repeated close.'
'@
$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("try {`n$fixture`n} catch { Write-Error `$_; exit 1 }"))
& docker run --rm --network none --mount "type=bind,source=$PSScriptRoot,target=/scripts,readonly" $image pwsh -NoProfile -OutputFormat Text -EncodedCommand $encoded
if($LASTEXITCODE -ne 0) { throw 'Admission operator fixture failed' }
