#Requires -Version 7.0
param([Parameter(Mandatory)][ValidateSet('Open','Close')][string]$Mode,
      [string]$StateFile="$PSScriptRoot/../.tmp/m15-aws-state.json")
$ErrorActionPreference='Stop'
. "$PSScriptRoot/aws-deployment-context.ps1"
Assert-PairForgeDeploymentIdentity
$StateFile=[IO.Path]::GetFullPath($StateFile)
$state=Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json -AsHashtable
if($state.Account -ne '298984481596' -or -not $state.AppInstanceId) { throw 'Recorded approved application host required' }
function Save-State { [IO.File]::WriteAllText("$StateFile.new",($state | ConvertTo-Json -Depth 12)); Move-Item -LiteralPath "$StateFile.new" -Destination $StateFile -Force }
$record=Invoke-PairForgeAws @('ec2','describe-instances','--instance-ids',$state.AppInstanceId)
$instance=$record.Reservations[0].Instances[0]
if($instance.VpcId -ne $state.VpcId -or $instance.SubnetId -ne $state.AppSubnetId) { throw 'Application network identity mismatch' }
$tables=Invoke-PairForgeAws @('ec2','describe-route-tables','--route-table-ids',$state.AppRouteTableId)
if($tables.RouteTables[0].VpcId -ne $state.VpcId) { throw 'Application route-table identity mismatch' }
$routes=@($tables.RouteTables[0].Routes | Where-Object DestinationCidrBlock -eq '0.0.0.0/0')
if($routes.Count -gt 1 -or ($routes.Count -eq 1 -and $routes[0].GatewayId -ne $state.InternetGatewayId)) { throw 'Unexpected default route; refusing replacement' }
if($Mode -eq 'Open') {
    Assert-PairForgeAdministrationReady $state
    if(-not $state.MaintenanceAllocationId) {
        $existing=Invoke-PairForgeAws @('ec2','describe-addresses','--filters','Name=tag:Project,Values=PairForge')
        if(@($existing.Addresses).Count -or $instance.PublicIpAddress) { throw 'Unrecorded public address; reconcile before allocation' }
        $spec='ResourceType=elastic-ip,Tags=[{Key=Project,Value=PairForge},{Key=Name,Value=pairforge-bootstrap}]'
        $address=Invoke-PairForgeAws @('ec2','allocate-address','--domain','vpc','--tag-specifications',$spec)
        $state.MaintenanceAllocationId=$address.AllocationId; Save-State
    }
    $address=(Invoke-PairForgeAws @('ec2','describe-addresses','--allocation-ids',$state.MaintenanceAllocationId)).Addresses[0]
    if($address.InstanceId -and $address.InstanceId -ne $state.AppInstanceId) { throw 'Maintenance address belongs to another host' }
    if(-not $address.AssociationId) { [void](Invoke-PairForgeAws @('ec2','associate-address','--allocation-id',$state.MaintenanceAllocationId,'--instance-id',$state.AppInstanceId,'--no-allow-reassociation')) }
    if(-not $routes.Count) { [void](Invoke-PairForgeAws @('ec2','create-route','--route-table-id',$state.AppRouteTableId,'--destination-cidr-block','0.0.0.0/0','--gateway-id',$state.InternetGatewayId)) }
    Write-Host 'Temporary application outbound access opened. Inbound security groups unchanged. Close maintenance before opening the demo.'
} else {
    if($routes.Count) { [void](Invoke-PairForgeAws @('ec2','delete-route','--route-table-id',$state.AppRouteTableId,'--destination-cidr-block','0.0.0.0/0')) }
    if($state.MaintenanceAllocationId) {
        $address=(Invoke-PairForgeAws @('ec2','describe-addresses','--allocation-ids',$state.MaintenanceAllocationId)).Addresses[0]
        if($address.InstanceId -and $address.InstanceId -ne $state.AppInstanceId) { throw 'Maintenance address belongs to another host; refusing release' }
        if($address.AssociationId) { [void](Invoke-PairForgeAws @('ec2','disassociate-address','--association-id',$address.AssociationId)) }
        [void](Invoke-PairForgeAws @('ec2','release-address','--allocation-id',$state.MaintenanceAllocationId))
        $state.MaintenanceAllocationId=$null; Save-State
    }
    Write-Host 'Application internet default route removed and recorded maintenance address released.'
}
