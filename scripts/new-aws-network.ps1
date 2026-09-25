#Requires -Version 7.0
param([string]$StateFile="$PSScriptRoot/../.tmp/m15-aws-state.json")
$ErrorActionPreference='Stop'
. "$PSScriptRoot/aws-deployment-context.ps1"
Assert-PairForgeDeploymentIdentity
$StateFile=[IO.Path]::GetFullPath($StateFile)
$state=if(Test-Path -LiteralPath $StateFile){Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json -AsHashtable}else{@{Account='298984481596';Region='us-east-1'}}
if($state.Account -ne '298984481596' -or $state.Region -ne 'us-east-1') { throw 'State belongs to a different deployment' }
function Save-State { [IO.File]::WriteAllText("$StateFile.new",($state | ConvertTo-Json -Depth 12)); Move-Item -LiteralPath "$StateFile.new" -Destination $StateFile -Force }
function Request($Service,$Action,$Body) {
    $file=[IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllText($file,($Body | ConvertTo-Json -Depth 15),[Text.UTF8Encoding]::new($false))
        return Invoke-PairForgeAws @($Service,$Action,'--cli-input-json',"file://$file")
    } finally { Remove-Item -LiteralPath $file -Force }
}
function Tags($Type,$Name) { ,@(@{ResourceType=$Type;Tags=@(@{Key='Project';Value='PairForge'},@{Key='Name';Value=$Name})}) }
$vpcs=Invoke-PairForgeAws @('ec2','describe-vpcs','--filters','Name=tag:Project,Values=PairForge')
if(-not $state.VpcId) {
    if(@($vpcs.Vpcs).Count -ne 0) { throw 'Owned VPC already exists without a recorded ID; reconcile state instead of creating another' }
    $zones=Invoke-PairForgeAws @('ec2','describe-availability-zones','--filters','Name=state,Values=available')
    # CloudFront VPC origins exclude physical zone use1-az3.
    $zone=$zones.AvailabilityZones | Where-Object { $_.ZoneId -ne 'use1-az3' -and $_.ZoneType -eq 'availability-zone' } | Sort-Object ZoneName | Select-Object -First 1
    if(-not $zone) { throw 'No supported zone available' }
    $state.Zone=$zone.ZoneName
    $state.VpcId=(Request ec2 create-vpc @{CidrBlock='10.42.0.0/16';TagSpecifications=(Tags vpc pairforge)}).Vpc.VpcId
    Save-State
} elseif(@($vpcs.Vpcs | Where-Object VpcId -eq $state.VpcId).Count -ne 1) { throw 'Recorded VPC is missing or not owned' }
[void](Request ec2 modify-vpc-attribute @{VpcId=$state.VpcId;EnableDnsSupport=@{Value=$true}})
[void](Request ec2 modify-vpc-attribute @{VpcId=$state.VpcId;EnableDnsHostnames=@{Value=$true}})
if(-not $state.InternetGatewayId) {
    $state.InternetGatewayId=(Request ec2 create-internet-gateway @{TagSpecifications=(Tags internet-gateway pairforge)}).InternetGateway.InternetGatewayId
    Save-State
}
$gateway=Invoke-PairForgeAws @('ec2','describe-internet-gateways','--internet-gateway-ids',$state.InternetGatewayId)
if(@($gateway.InternetGateways.Attachments | Where-Object VpcId -eq $state.VpcId).Count -eq 0) {
    [void](Request ec2 attach-internet-gateway @{InternetGatewayId=$state.InternetGatewayId;VpcId=$state.VpcId})
}
foreach($role in @('App','Worker')) {
    $subnetKey="${role}SubnetId"; $routeKey="${role}RouteTableId"
    if(-not $state[$subnetKey]) {
        $cidr=if($role -eq 'App'){'10.42.1.0/24'}else{'10.42.2.0/24'}
        $state[$subnetKey]=(Request ec2 create-subnet @{VpcId=$state.VpcId;CidrBlock=$cidr;AvailabilityZone=$state.Zone;TagSpecifications=(Tags subnet "pairforge-$($role.ToLower())")}).Subnet.SubnetId
        Save-State
    }
    if(-not $state[$routeKey]) {
        $state[$routeKey]=(Request ec2 create-route-table @{VpcId=$state.VpcId;TagSpecifications=(Tags route-table "pairforge-$($role.ToLower())")}).RouteTable.RouteTableId
        Save-State
    }
    $associationKey="${role}RouteAssociationId"
    if(-not $state[$associationKey]) {
        $state[$associationKey]=(Request ec2 associate-route-table @{SubnetId=$state[$subnetKey];RouteTableId=$state[$routeKey]}).AssociationId
        Save-State
    }
}
$routes=Invoke-PairForgeAws @('ec2','describe-route-tables','--route-table-ids',$state.WorkerRouteTableId)
if(@($routes.RouteTables.Routes | Where-Object DestinationCidrBlock -eq '0.0.0.0/0').Count -eq 0) {
    [void](Request ec2 create-route @{RouteTableId=$state.WorkerRouteTableId;DestinationCidrBlock='0.0.0.0/0';GatewayId=$state.InternetGatewayId})
}
foreach($role in @('App','Worker','Admin')) {
    $key="${role}SecurityGroupId"
    if(-not $state[$key]) {
        $state[$key]=(Request ec2 create-security-group @{VpcId=$state.VpcId;GroupName="pairforge-$($role.ToLower())";Description="PairForge $role boundary";TagSpecifications=(Tags security-group "pairforge-$($role.ToLower())")}).GroupId
        Save-State
    }
}
# Configure once, recording each rule to make interrupted runs recoverable without duplicates.
function Rule($Name,$GroupId,$Direction,$Permission) {
    if($state[$Name]) { return }
    $existing=Invoke-PairForgeAws @('ec2','describe-security-group-rules','--filters',"Name=group-id,Values=$GroupId")
    if(@($existing.SecurityGroupRules | Where-Object Description -eq $Name).Count -eq 0) {
        if($Permission.UserIdGroupPairs) { foreach($pair in $Permission.UserIdGroupPairs) { $pair.Description=$Name } }
        if($Permission.IpRanges) { foreach($range in $Permission.IpRanges) { $range.Description=$Name } }
        [void](Request ec2 "authorize-security-group-$Direction" @{GroupId=$GroupId;IpPermissions=@($Permission)})
    }
    $state[$Name]=$true; Save-State
}
foreach($role in @('App','Worker','Admin')) {
    $group=$state["${role}SecurityGroupId"]
    $rules=Invoke-PairForgeAws @('ec2','describe-security-groups','--group-ids',$group)
    $unrestricted=@($rules.SecurityGroups.IpPermissionsEgress | Where-Object { $_.IpProtocol -eq '-1' -and $_.IpRanges.CidrIp -contains '0.0.0.0/0' })
    if($unrestricted.Count) { [void](Request ec2 revoke-security-group-egress @{GroupId=$group;IpPermissions=$unrestricted}) }
    if($role -ne 'Admin') {
        foreach($port in @(80,443)) { Rule "$role-out-$port" $group egress @{IpProtocol='tcp';FromPort=$port;ToPort=$port;IpRanges=@(@{CidrIp='0.0.0.0/0'})} }
        Rule "$role-admin-ssh" $group ingress @{IpProtocol='tcp';FromPort=22;ToPort=22;UserIdGroupPairs=@(@{GroupId=$state.AdminSecurityGroupId})}
        Rule "Admin-$role-ssh" $state.AdminSecurityGroupId egress @{IpProtocol='tcp';FromPort=22;ToPort=22;UserIdGroupPairs=@(@{GroupId=$group})}
    }
}
foreach($port in @(5432,5671)) {
    Rule "App-worker-$port" $state.AppSecurityGroupId ingress @{IpProtocol='tcp';FromPort=$port;ToPort=$port;UserIdGroupPairs=@(@{GroupId=$state.WorkerSecurityGroupId})}
    Rule "Worker-data-$port" $state.WorkerSecurityGroupId egress @{IpProtocol='tcp';FromPort=$port;ToPort=$port;UserIdGroupPairs=@(@{GroupId=$state.AppSecurityGroupId})}
}
if(-not $state.S3EndpointId) {
    $state.S3EndpointId=(Request ec2 create-vpc-endpoint @{VpcId=$state.VpcId;VpcEndpointType='Gateway';ServiceName='com.amazonaws.us-east-1.s3';RouteTableIds=@($state.AppRouteTableId);ClientToken='pairforge-m15-s3-v1';TagSpecifications=(Tags vpc-endpoint pairforge-backups)}).VpcEndpoint.VpcEndpointId
    Save-State
}
if(-not $state.AdminEndpointId) {
    $token=if($state.AdminClientToken){$state.AdminClientToken}else{'pairforge-m15-admin-v1'}
    $state.AdminEndpointId=(Request ec2 create-instance-connect-endpoint @{SubnetId=$state.AppSubnetId;SecurityGroupIds=@($state.AdminSecurityGroupId);PreserveClientIp=$false;ClientToken=$token;TagSpecifications=(Tags instance-connect-endpoint pairforge-admin)}).InstanceConnectEndpoint.InstanceConnectEndpointId
    Save-State
}
Assert-PairForgeAdministrationReady $state
Write-Host "Network recorded and administration ready in $StateFile. App remains private, with no public application ingress. No EC2 instances created."
