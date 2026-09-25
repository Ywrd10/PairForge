#Requires -Version 7.0
param([Parameter(Mandatory)][ValidateSet('App','Worker')][string]$Role,
      [Parameter(Mandatory)][string]$LocalPath,
      [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$')][string]$RemoteName,
      [string]$StateFile="$PSScriptRoot/../.tmp/m15-aws-state.json",
      [string]$KeyDirectory="$PSScriptRoot/../.tmp/m15-admin")
$ErrorActionPreference='Stop'
. "$PSScriptRoot/aws-deployment-context.ps1"
Assert-PairForgeDeploymentIdentity
$state=Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json -AsHashtable
Assert-PairForgeAdministrationReady $state
$instance=(Invoke-PairForgeAws @('ec2','describe-instances','--instance-ids',$state["${Role}InstanceId"])).Reservations[0].Instances[0]
if($instance.State.Name -ne 'running' -or $instance.VpcId -ne $state.VpcId -or $instance.PrivateIpAddress -ne $state["${Role}PrivateIp"]) { throw 'Remote host identity mismatch' }
$LocalPath=[IO.Path]::GetFullPath($LocalPath)
if(-not (Test-Path -LiteralPath $LocalPath -PathType Leaf)) { throw 'Expected exact local file' }
$KeyDirectory=[IO.Path]::GetFullPath($KeyDirectory).Replace('\','/')
$aws=(Get-PairForgeAwsExecutable).Replace('\','/')
$proxy="`"$aws`" ec2-instance-connect open-tunnel --private-ip-address $($instance.PrivateIpAddress) --instance-connect-endpoint-id $($state.AdminEndpointId) --remote-port 22 --max-tunnel-duration 3600 --profile pairforge --region us-east-1"
& scp -i "$KeyDirectory/id_ed25519" -o "UserKnownHostsFile=$KeyDirectory/known_hosts" -o StrictHostKeyChecking=yes -o BatchMode=yes -o ConnectTimeout=20 -o "ProxyCommand=$proxy" -- $LocalPath "ubuntu@$($instance.PrivateIpAddress):$RemoteName"
if($LASTEXITCODE -ne 0) { throw "Private file transfer to $Role failed" }
Write-Host "Transferred $RemoteName to $Role over private authenticated SSH."
