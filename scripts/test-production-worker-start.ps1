#Requires -Version 7.0
# Fault-inject worker startup in a disposable Linux filesystem; no AWS access.
$ErrorActionPreference='Stop'
$image='mcr.microsoft.com/powershell@sha256:62300a213a9293916333df2b014cd3a8f22fb0b0b65f2bb446aaf436bcf8c868'
$fixture=@'
$ErrorActionPreference='Stop'
[void][IO.Directory]::CreateDirectory('/etc/pairforge')
$global:metadataHeaders=@('HTTP/1.1 401 Unauthorized','Server: EC2ws'); $global:metadataExit=0; $global:started=0
function global:curl { $global:LASTEXITCODE=$global:metadataExit; return $global:metadataHeaders }
function global:systemctl {
    if($args[0] -eq 'is-active') { $global:LASTEXITCODE=3; return }
    if($args[0] -eq 'start') {
        if(-not (Test-Path /etc/pairforge/worker-start-approved)) { throw 'No one-use start approval' }
        $global:started++; $global:LASTEXITCODE=0; return
    }
    throw 'Unexpected systemctl operation'
}
function Reject {
    $rejected=$false
    try { & /scripts/start-production-worker.ps1 -ConfirmedPreviousWorkerStopped } catch {
        if($_.Exception.Message -notlike 'Worker metadata endpoint*') { throw }
        $rejected=$true
    }
    if(-not $rejected -or $global:started -ne 0 -or (Test-Path /etc/pairforge/worker-start-approved)) { throw 'Metadata rejection did not fail closed' }
}
Reject
$global:metadataHeaders=@(); $global:metadataExit=6
Reject
$global:metadataHeaders=@('HTTP/1.1 403 Forbidden','Server: unrelated-proxy'); $global:metadataExit=0
Reject
$global:metadataHeaders=@('HTTP/1.1 403 Forbidden','Server: EC2ws'); $global:metadataExit=0
& /scripts/start-production-worker.ps1 -ConfirmedPreviousWorkerStopped
if($global:started -ne 1 -or (Test-Path /etc/pairforge/worker-start-approved)) { throw 'Expected one start on explicit EC2 metadata denial' }
$global:metadataExit=28
& /scripts/start-production-worker.ps1 -ConfirmedPreviousWorkerStopped
if($global:started -ne 2 -or (Test-Path /etc/pairforge/worker-start-approved)) { throw 'Expected second start and consumed approval' }
Write-Host 'Worker start checks passed: reachable metadata and uncertain curl failure rejected; EC2ws denial or unreachable metadata permits operator-approved start.'
'@
$encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("try {`n$fixture`n} catch { Write-Error `$_; exit 1 }"))
& docker run --rm --network none --mount "type=bind,source=$PSScriptRoot,target=/scripts,readonly" $image pwsh -NoProfile -OutputFormat Text -EncodedCommand $encoded
if($LASTEXITCODE -ne 0) { throw 'Worker startup fixture failed' }
