#Requires -Version 7.0
param([Parameter(Mandatory)][ValidateSet('App','Worker')][string]$Role,
      [Parameter(Mandatory)][string]$Command,
      [string]$StateFile="$PSScriptRoot/../.tmp/m15-aws-state.json",
      [string]$KeyDirectory="$PSScriptRoot/../.tmp/m15-admin")
$ErrorActionPreference='Stop'
. "$PSScriptRoot/aws-deployment-context.ps1"
Assert-PairForgeDeploymentIdentity
$state=Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json -AsHashtable
Assert-PairForgeAdministrationReady $state
if(-not $state["${Role}InstanceId"] -or -not $state["${Role}PrivateIp"]) { throw 'No recorded host for this role' }
$instance=Invoke-PairForgeAws @('ec2','describe-instances','--instance-ids',$state["${Role}InstanceId"])
$hostRecord=$instance.Reservations[0].Instances[0]
if($hostRecord.State.Name -ne 'running' -or $hostRecord.PrivateIpAddress -ne $state["${Role}PrivateIp"] -or $hostRecord.VpcId -ne $state.VpcId) { throw 'Host identity or state does not match deployment' }
$KeyDirectory=[IO.Path]::GetFullPath($KeyDirectory).Replace('\','/')
$aws=(Get-PairForgeAwsExecutable).Replace('\','/')
$proxy="`"$aws`" ec2-instance-connect open-tunnel --private-ip-address $($state["${Role}PrivateIp"]) --instance-connect-endpoint-id $($state.AdminEndpointId) --remote-port 22 --max-tunnel-duration 3600 --profile pairforge --region us-east-1"
& ssh -i "$KeyDirectory/id_ed25519" -o "UserKnownHostsFile=$KeyDirectory/known_hosts" -o StrictHostKeyChecking=accept-new -o BatchMode=yes -o ConnectTimeout=20 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 -o "ProxyCommand=$proxy" "ubuntu@$($state["${Role}PrivateIp"])" $Command
if($LASTEXITCODE -ne 0) { throw "Remote $Role operation failed with exit $LASTEXITCODE" }
