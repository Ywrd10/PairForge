#Requires -Version 7.4
param(
    [Parameter(Mandatory)][string]$NodePath,
    [string]$ReportDirectory="$PSScriptRoot/../.tmp/m16-aws",
    [string]$ResumeReport
)
$ErrorActionPreference='Stop'
$target='https://d3pq3na8h2es74.cloudfront.net'
$root=Split-Path $PSScriptRoot -Parent
$ReportDirectory=[IO.Path]::GetFullPath($ReportDirectory)
if(Test-Path (Join-Path $ReportDirectory 'report.json')) { throw 'Use a new report directory; never overwrite a benchmark or automatically rerun it' }
[void](New-Item -ItemType Directory -Force -Path $ReportDirectory)
$sessions=@()
$process=$null
try {
    Write-Host 'Enter only the two approved PairForge test accounts. Credentials stay in this process and are not written to disk.'
    foreach($index in 1..2) {
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
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=[IO.Path]::GetFullPath($NodePath)
    $info.WorkingDirectory=$root
    $info.UseShellExecute=$false
    $info.RedirectStandardInput=$true
    # Progress and safe errors go to this private terminal; tokens travel only on stdin.
    foreach($argument in @((Join-Path $root 'frontend/tools/load/remote-run.ts'),$target,(Join-Path $PSHOME 'pwsh.exe'),$ReportDirectory)) { $info.ArgumentList.Add($argument) }
    if($ResumeReport){$info.ArgumentList.Add([IO.Path]::GetFullPath($ResumeReport))}
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
} finally { $sessions=@();if($process){$process.Dispose()} }
