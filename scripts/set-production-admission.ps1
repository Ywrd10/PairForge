#Requires -Version 7.0
param([Parameter(Mandatory)][ValidateSet('Close','Open')][string]$Mode,
      [switch]$WorkerReadinessVerified)
$ErrorActionPreference='Stop'
if($IsWindows) { throw 'Run on the Linux application host' }
$uid=& id -u
if($LASTEXITCODE -ne 0 -or $uid -ne '0') { throw 'Requires root on the application host' }
$directory='/run/pairforge-operations'
$marker=Join-Path $directory 'execution-enabled'
if($Mode -eq 'Close') {
    if(Test-Path -LiteralPath $marker) { Remove-Item -LiteralPath $marker -Force }
    # Wait for requests that passed the gate before removal to finish before draining.
    & systemctl restart pairforge-api
    if($LASTEXITCODE -ne 0) { throw 'Admission is closed, but API restart failed; do not claim a completed drain' }
    Write-Host 'Admission closed; API restart completed. Drain durable work before stopping the worker.'
    exit 0
}
if(-not $WorkerReadinessVerified) { throw 'First verify the dedicated worker readiness and sandbox preflight through private administration' }
# tmpfiles creates the root-owned directory; never create it with API ownership.
& systemd-tmpfiles --create /etc/tmpfiles.d/pairforge-operations.conf
if($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $directory -PathType Container)) { throw 'Admission directory unavailable' }
$owner=& stat -c '%u:%a' $directory
if($LASTEXITCODE -ne 0 -or $owner -ne '0:755') { throw 'Admission directory must be root-owned with mode 0755' }
$health=Invoke-RestMethod -Uri 'http://127.0.0.1:8082/actuator/health/readiness' -TimeoutSec 5
if($health.status -ne 'UP') { throw 'API dependencies are not ready' }
$stream=[IO.File]::Open($marker,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
try { $stream.WriteByte(1) } finally { $stream.Dispose() }
[IO.File]::SetUnixFileMode($marker,[IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::GroupRead -bor [IO.UnixFileMode]::OtherRead)
Write-Host 'Execution admission opened for explicitly approved account UUIDs only.'
