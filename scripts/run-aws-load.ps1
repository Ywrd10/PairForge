#Requires -Version 7.4
param(
    [Parameter(Mandatory)][string]$NodePath,
    [string]$ReportDirectory="$PSScriptRoot/../.tmp/m16-aws",
    [string]$ResumeReport,
    [switch]$ApprovedSevenMinuteContinuation,
    [switch]$ApprovedTwentyMinuteContinuation
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/operator-config.ps1"
$operator=Get-PairForgeOperatorConfig
$target='https://d3pq3na8h2es74.cloudfront.net'
$root=Split-Path $PSScriptRoot -Parent
$ReportDirectory=[IO.Path]::GetFullPath($ReportDirectory)
if($ApprovedSevenMinuteContinuation -and $ApprovedTwentyMinuteContinuation){throw 'Select one approved operating window'}
if(($ApprovedSevenMinuteContinuation -or $ApprovedTwentyMinuteContinuation) -and -not $ResumeReport){throw 'Continuation requires the verified report'}
if(-not (Test-Path -LiteralPath $NodePath -PathType Leaf)){throw 'Node executable is missing'}
if($ResumeReport -and -not (Test-Path -LiteralPath $ResumeReport -PathType Leaf)){throw 'Checkpoint report is missing'}
if(Test-Path (Join-Path $ReportDirectory 'report.json')) { throw 'Use a new report directory; never overwrite a benchmark or automatically rerun it' }
[void](New-Item -ItemType Directory -Force -Path $ReportDirectory)
$sessions=@()
$process=$null
$authenticatedMarker=Join-Path $ReportDirectory 'authenticated.json'
$windowStart=$null
function Assert-ContinuationTime {
    if($ApprovedTwentyMinuteContinuation){
        $started=[DateTimeOffset]::Parse($windowStart)
        $elapsed=([DateTimeOffset]::UtcNow-$started).TotalSeconds
        # Preserve six minutes of workload plus five minutes for shutdown.
        if($elapsed -lt 0 -or $elapsed -gt 540){throw 'Insufficient approved twenty-minute operating window'}
    }
}
try {
    if($ApprovedTwentyMinuteContinuation){
        [void](Read-Host 'PRIVATE TERMINAL READY. Tell Codex it is open and visible. Wait for startup/preflight instructions before pressing Enter')
        $windowStart=(Get-Content -LiteralPath (Join-Path $root '.tmp/m16-aws-continuation-start-time.txt') -Raw).Trim()
        Assert-ContinuationTime
    }
    Write-Host 'Enter only the two approved PairForge test accounts. Credentials stay in this process and are not written to disk.'
    foreach($index in 1..2) {
        Assert-ContinuationTime
        $email=Read-Host "Account $index email"
        $secure=Read-Host "Account $index password" -AsSecureString
        $password=[Net.NetworkCredential]::new('', $secure).Password
        try {
            $body=@{email=$email;password=$password}|ConvertTo-Json -Compress
            $session=Invoke-RestMethod -Uri "$target/api/auth/login" -Method Post -ContentType 'application/json' -Headers @{Origin=$target} -Body $body -TimeoutSec 10 -MaximumRedirection 0
            $sessions+=@{accessToken=$session.accessToken;expiresAt=$session.expiresAt}
            Write-Host "Account $index authenticated."
        } finally { $body=$null;$password=$null;$secure.Dispose();$session=$null }
    }
    if($ApprovedTwentyMinuteContinuation){
        $ids=@(foreach($session in $sessions){
            $account=Invoke-RestMethod -Uri "$target/api/auth/me" -Headers @{Authorization="Bearer $($session.accessToken)";Origin=$target} -TimeoutSec 10 -MaximumRedirection 0
            $account.id
        })
        Assert-PairForgeBenchmarkIdentity $ids $operator.ApprovedBenchmarkUsers
        Assert-ContinuationTime
        # Non-secret status only; passwords and tokens never leave process memory/stdin.
        @{authenticated=$true;at=[DateTimeOffset]::UtcNow.ToString('o')}|ConvertTo-Json -Compress|Set-Content -LiteralPath $authenticatedMarker
        $confirmation=Read-Host 'Both approved accounts authenticated. Tell Codex; wait until admission is opened, then type RUN'
        if($confirmation -cne 'RUN'){throw 'Workload start was not confirmed'}
        Assert-ContinuationTime
    }
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=[IO.Path]::GetFullPath($NodePath)
    $info.WorkingDirectory=$root
    $info.UseShellExecute=$false
    $info.RedirectStandardInput=$true
    # Progress and safe errors go to this private terminal; tokens travel only on stdin.
    foreach($argument in @((Join-Path $root 'frontend/tools/load/remote-run.ts'),$target,(Join-Path $PSHOME 'pwsh.exe'),$ReportDirectory)) { $info.ArgumentList.Add($argument) }
    if($ResumeReport){$info.ArgumentList.Add([IO.Path]::GetFullPath($ResumeReport))}
    if($ApprovedSevenMinuteContinuation){
        if(-not $ResumeReport){throw 'Explicit continuation requires the verified report'}
        $windowStart=(Get-Content -LiteralPath (Join-Path $root '.tmp/m16-aws-start-time.txt') -Raw).Trim()
        $info.ArgumentList.Add($windowStart)
    }
    if($ApprovedTwentyMinuteContinuation){$info.ArgumentList.Add($windowStart);$info.ArgumentList.Add('20')}
    $process=[Diagnostics.Process]::Start($info)
    $process.StandardInput.WriteLine((ConvertTo-Json -InputObject @($sessions) -Compress))
    $process.StandardInput.Close()
    $sessions=@()
    $process.WaitForExit()
    if($process.ExitCode -ne 0) { throw 'Benchmark stopped; inspect the sanitized report. Do not rerun automatically.' }
    Write-Host 'Bounded workload finished. Operator backup/shutdown remains required.'
} catch {
    Write-Host 'Authentication or benchmark did not complete. No credentials are printed. Inform the operator; do not retry an uncertain benchmark.' -ForegroundColor Red
    exit 1
} finally {
    $sessions=@();$session=$null;$account=$null
    if(Test-Path -LiteralPath $authenticatedMarker){Remove-Item -LiteralPath $authenticatedMarker}
    if($process){$process.Dispose()}
}
