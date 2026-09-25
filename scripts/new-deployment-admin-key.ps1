#Requires -Version 7.0
param([string]$Directory="$PSScriptRoot/../.tmp/m15-admin")
$ErrorActionPreference='Stop'
$Directory=[IO.Path]::GetFullPath($Directory)
if(Test-Path -LiteralPath $Directory) { throw 'Key directory exists; reuse the existing key rather than overwriting it' }
[void][IO.Directory]::CreateDirectory($Directory)
if($IsWindows) {
    $owner=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl=Get-Acl -LiteralPath $Directory
    $acl.SetAccessRuleProtection($true,$false); $acl.SetOwner($owner)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($owner,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
    Set-Acl -LiteralPath $Directory -AclObject $acl
} else { [IO.File]::SetUnixFileMode($Directory,[IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::UserExecute) }
$key=Join-Path $Directory 'id_ed25519'
$executable=(Get-Command ssh-keygen -CommandType Application | Select-Object -First 1).Source
$start=[Diagnostics.ProcessStartInfo]::new($executable)
$start.UseShellExecute=$false; $start.CreateNoWindow=$true
$start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
foreach($argument in @('-q','-t','ed25519','-N','','-C','pairforge-private-administration','-f',$key)) { $start.ArgumentList.Add($argument) }
$process=[Diagnostics.Process]::Start($start)
if(-not $process.WaitForExit(30000)) { $process.Kill($true); throw 'SSH key generation timed out' }
if($process.ExitCode -ne 0) { throw 'SSH key generation failed' }
Write-Host "Created private administration key in protected directory $Directory. Never copy it to either server or source control."
