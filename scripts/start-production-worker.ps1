#Requires -Version 7.0
param([Parameter(Mandatory)][switch]$ConfirmedPreviousWorkerStopped)
$ErrorActionPreference = 'Stop'
if ($IsWindows -or -not $ConfirmedPreviousWorkerStopped) { throw 'Requires Linux and explicit predecessor-stop confirmation' }
$uid = & id -u
if ($LASTEXITCODE -ne 0 -or $uid -ne '0') { throw 'Run as root after the recovery checklist' }
& systemctl is-active --quiet pairforge-worker
if ($LASTEXITCODE -eq 0) { throw 'Worker already active' }
# Bootstrap requires IMDS for SSH setup, but execution must never start with it usable.
# The deployer must independently verify HttpEndpoint=disabled through EC2.
# A disabled EC2 metadata endpoint can return a 403 from EC2ws instead of
# refusing the TCP connection. Probe token issuance without proxy settings.
$metadataHeaders=@(& curl --noproxy '*' --silent --dump-header - --output /dev/null --request PUT --header 'X-aws-ec2-metadata-token-ttl-seconds: 1' --connect-timeout 1 --max-time 2 http://169.254.169.254/latest/api/token)
$metadataExit=$LASTEXITCODE
$explicitDenial=$metadataExit -eq 0 -and @($metadataHeaders -match '^HTTP/1\.[01] 403(?: |$)').Count -eq 1 -and @($metadataHeaders -match '^Server:\s*EC2ws\s*$').Count -eq 1
if(-not $explicitDenial -and $metadataExit -notin @(7,28)) { throw 'Worker metadata endpoint is reachable or its isolation could not be checked' }
$marker = '/etc/pairforge/worker-start-approved'
[IO.File]::WriteAllText($marker, 'Operator confirmed predecessor stopped')
[IO.File]::SetUnixFileMode($marker, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite)
try {
    & systemctl start pairforge-worker
    if ($LASTEXITCODE -ne 0) { throw 'Worker failed startup; inspect safe service diagnostics and follow recovery checklist' }
} finally { if (Test-Path -LiteralPath $marker) { Remove-Item -LiteralPath $marker -Force } }
