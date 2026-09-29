#Requires -Version 7.0
param([string]$StateFile="$PSScriptRoot/../.tmp/m15-aws-state.json")
$ErrorActionPreference='Stop'
. "$PSScriptRoot/aws-deployment-context.ps1"
$operator=Get-PairForgeOperatorConfig
Assert-PairForgeDeploymentIdentity
$StateFile=[IO.Path]::GetFullPath($StateFile)
$state=Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json -AsHashtable
if($state.Account -ne $operator.AccountId -or -not $state.AdminEndpointId) { throw 'Recorded administration endpoint required' }
$endpoints=Invoke-PairForgeAws @('ec2','describe-instance-connect-endpoints','--instance-connect-endpoint-ids',$state.AdminEndpointId)
$endpoint=$endpoints.InstanceConnectEndpoints[0]
$instances=Invoke-PairForgeAws @('ec2','describe-instances','--filters','Name=tag:Project,Values=PairForge','Name=instance-state-name,Values=pending,running,stopping,stopped')
if($endpoint.State -ne 'create-failed' -or @($endpoint.NetworkInterfaceIds).Count -ne 0 -or @($instances.Reservations).Count -ne 0 -or $endpoint.SubnetId -ne $state.AppSubnetId) {
    throw 'Recovery only removes the recorded failed empty endpoint before any hosts exist'
}
[void](Invoke-PairForgeAws @('ec2','delete-instance-connect-endpoint','--instance-connect-endpoint-id',$state.AdminEndpointId))
$state.FailedAdminEndpointId=$state.AdminEndpointId
$state.AdminEndpointId=$null
$state.AdminClientToken='pairforge-admin-'+[Guid]::NewGuid().ToString('N')
[IO.File]::WriteAllText("$StateFile.new",($state | ConvertTo-Json -Depth 12))
Move-Item -LiteralPath "$StateFile.new" -Destination $StateFile -Force
Write-Host 'Removed failed empty administration endpoint; recorded a fresh creation token. Wait for deletion before recreating it.'
