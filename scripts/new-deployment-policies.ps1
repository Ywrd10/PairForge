#Requires -Version 7.0
param([Parameter(Mandatory)][ValidatePattern('^\d{12}$')][string]$AccountId,
      [Parameter(Mandatory)][string]$Directory,
      [Parameter(Mandatory)][ValidatePattern('^ami-[0-9a-f]{17}$')][string]$ApprovedAmiId,
      [Parameter(Mandatory)][ValidatePattern('^subnet-[0-9a-f]{17}$')][string]$AdministrationSubnetId,
      [Parameter(Mandatory)][ValidatePattern('^sg-[0-9a-f]{17}$')][string]$AdministrationSecurityGroupId)
$ErrorActionPreference = 'Stop'
$region = 'us-east-1'
$ec2 = "arn:aws:ec2:${region}:${AccountId}"
$bucket = "arn:aws:s3:::pairforge-${AccountId}-${region}-backups"
$own = @{StringEquals=@{'aws:ResourceTag/Project'='PairForge'}}
$new = @{StringEquals=@{'aws:RequestTag/Project'='PairForge'}}
function Statement($Sid, $Actions, $Resources, $Condition = $null) {
    $s = [ordered]@{Sid=$Sid;Effect='Allow';Action=@($Actions);Resource=@($Resources)}
    if ($Condition) { $s.Condition=$Condition }
    return $s
}
function Save-Policy($Name, $Statements) {
    $document = @{Version='2012-10-17';Statement=@($Statements)}
    [IO.File]::WriteAllText((Join-Path $Directory "$Name.json"), ($document | ConvertTo-Json -Depth 15), [Text.UTF8Encoding]::new($false))
}
$Directory = [IO.Path]::GetFullPath($Directory)
if (Test-Path -LiteralPath $Directory) { throw 'Policy output directory already exists' }
[void][IO.Directory]::CreateDirectory($Directory)
$creation = @('CreateVpc','CreateSubnet','CreateRouteTable','CreateInternetGateway','CreateSecurityGroup','ImportKeyPair','AllocateAddress','CreateInstanceConnectEndpoint')
$network = @(
    Statement 'DescribeRegion' @('ec2:DescribeVpcs','ec2:DescribeVpcAttribute','ec2:DescribeSubnets','ec2:DescribeRouteTables','ec2:DescribeInternetGateways','ec2:DescribeSecurityGroups','ec2:DescribeSecurityGroupRules','ec2:DescribeInstances','ec2:DescribeInstanceStatus','ec2:DescribeInstanceTypes','ec2:DescribeInstanceTypeOfferings','ec2:DescribeInstanceCreditSpecifications','ec2:DescribeVolumes','ec2:DescribeImages','ec2:DescribeAvailabilityZones','ec2:DescribeAddresses','ec2:DescribeKeyPairs','ec2:DescribeNetworkInterfaces','ec2:DescribeVpcEndpoints','ec2:DescribeInstanceConnectEndpoints','ec2:DescribeManagedPrefixLists') '*' @{StringEquals=@{'aws:RequestedRegion'=$region}}
    Statement 'CreateTaggedNetwork' ($creation | ForEach-Object { "ec2:$_" }) "$ec2`:*/*" $new
    Statement 'CreateWithinOwnedNetwork' @('ec2:CreateSubnet','ec2:CreateRouteTable','ec2:CreateSecurityGroup','ec2:CreateInstanceConnectEndpoint') @("$ec2`:vpc/*","$ec2`:subnet/*","$ec2`:security-group/*") $own
    Statement 'CreateS3Gateway' 'ec2:CreateVpcEndpoint' "$ec2`:vpc-endpoint/*" @{StringEquals=@{'aws:RequestTag/Project'='PairForge';'ec2:VpceServiceName'='com.amazonaws.us-east-1.s3'}}
    Statement 'GatewayDependencies' 'ec2:CreateVpcEndpoint' @("$ec2`:vpc/*","$ec2`:route-table/*") $own
    Statement 'ManageOwnedNetwork' @('ec2:ModifyVpcAttribute','ec2:AttachInternetGateway','ec2:DetachInternetGateway','ec2:CreateRoute','ec2:DeleteRoute','ec2:AssociateRouteTable','ec2:DisassociateRouteTable','ec2:AuthorizeSecurityGroupIngress','ec2:AuthorizeSecurityGroupEgress','ec2:RevokeSecurityGroupIngress','ec2:RevokeSecurityGroupEgress','ec2:AssociateAddress','ec2:DisassociateAddress','ec2:ReleaseAddress','ec2:DeleteKeyPair','ec2:DeleteVpcEndpoints','ec2:DeleteInstanceConnectEndpoint','ec2:DeleteSecurityGroup','ec2:DeleteSubnet','ec2:DeleteRouteTable','ec2:DeleteInternetGateway','ec2:DeleteVpc') "$ec2`:*/*" $own
    Statement 'TagOnlyDuringApprovedCreation' 'ec2:CreateTags' "$ec2`:*/*" @{StringEquals=@{'ec2:CreateAction'=($creation+@('CreateVpcEndpoint','RunInstances'));'aws:RequestTag/Project'='PairForge'}}
)
Save-Policy 'network' $network
$compute = @(
    Statement 'LaunchApprovedSizes' 'ec2:RunInstances' "$ec2`:instance/*" @{StringEquals=@{'aws:RequestTag/Project'='PairForge';'ec2:InstanceType'=@('t3a.small','t3a.medium');'ec2:MetadataHttpTokens'='required';'ec2:Tenancy'='default'}}
    Statement 'LaunchEncryptedBoundedVolumes' 'ec2:RunInstances' "$ec2`:volume/*" @{StringEquals=@{'aws:RequestTag/Project'='PairForge';'ec2:VolumeType'='gp3'};Bool=@{'ec2:Encrypted'='true'};NumericLessThanEquals=@{'ec2:VolumeSize'=30;'ec2:VolumeIops'=3000;'ec2:VolumeThroughput'=125}}
    Statement 'LaunchTaggedInterface' 'ec2:RunInstances' "$ec2`:network-interface/*" $new
    Statement 'LaunchUsingOwnedNetwork' 'ec2:RunInstances' @("$ec2`:subnet/*","$ec2`:security-group/*","$ec2`:key-pair/*") $own
    # Pin the AMI after verifying its Canonical OwnerId through DescribeImages.
    # EC2's IAM owner context can use the 'amazon' alias for verified partners.
    Statement 'LaunchVerifiedUbuntuImage' 'ec2:RunInstances' "arn:aws:ec2:${region}::image/$ApprovedAmiId"
    Statement 'AdministrationInterfaceDependencies' 'ec2:CreateNetworkInterface' @("$ec2`:subnet/$AdministrationSubnetId","$ec2`:security-group/$AdministrationSecurityGroupId")
    # Creation requires authorization for BOTH the new interface and the exact
    # subnet/security group above. A new interface has no existing subnet context.
    Statement 'AdministrationInterfaceInApprovedSubnet' 'ec2:CreateNetworkInterface' "$ec2`:network-interface/*"
    # EIC's preflight evaluates a new interface with literal NetworkInterfaceID '*'.
    # StringEquals is deliberate: it does not match existing eni-* interface IDs.
    Statement 'AdministrationInterfaceCreationTags' 'ec2:CreateTags' "$ec2`:network-interface/*" @{StringEquals=@{'ec2:NetworkInterfaceID'='*'}}
    Statement 'OperateOwnedInstances' @('ec2:StartInstances','ec2:StopInstances','ec2:TerminateInstances','ec2:GetConsoleOutput') "$ec2`:instance/*" $own
    # MetadataHttpEndpoint describes CURRENT state. Attribute/HttpEndpoint checks
    # the requested change; restrict the request to this attribute alone.
    Statement 'DisableWorkerMetadataAfterBootstrap' 'ec2:ModifyInstanceMetadataOptions' "$ec2`:instance/*" @{StringEquals=@{'aws:ResourceTag/Project'='PairForge';'aws:ResourceTag/Name'='pairforge-worker';'ec2:Attribute/HttpEndpoint'='disabled'};'ForAllValues:StringEquals'=@{'ec2:Attribute'=@('HttpEndpoint')};Null=@{'ec2:Attribute'='false'}}
    Statement 'DeleteOwnedDisks' 'ec2:DeleteVolume' "$ec2`:volume/*" $own
    Statement 'PassOnlyBackupRole' 'iam:PassRole' "arn:aws:iam::${AccountId}:role/pairforge/pairforge-app-backup" @{StringEquals=@{'iam:PassedToService'='ec2.amazonaws.com'}}
    Statement 'ReadBackupProfile' 'iam:GetInstanceProfile' "arn:aws:iam::${AccountId}:instance-profile/pairforge/pairforge-app-backup"
    Statement 'PrivateSshOnly' 'ec2-instance-connect:OpenTunnel' "$ec2`:instance-connect-endpoint/*" @{StringEquals=@{'aws:ResourceTag/Project'='PairForge'};NumericEquals=@{'ec2-instance-connect:remotePort'=22};NumericLessThanEquals=@{'ec2-instance-connect:maxTunnelDuration'=3600}}
    Statement 'RequiredServiceLinkedRoles' 'iam:CreateServiceLinkedRole' @("arn:aws:iam::${AccountId}:role/aws-service-role/vpcorigin.cloudfront.amazonaws.com/AWSServiceRoleForCloudFrontVPCOrigin","arn:aws:iam::${AccountId}:role/aws-service-role/ec2-instance-connect.amazonaws.com/AWSServiceRoleForEc2InstanceConnect") @{StringEquals=@{'iam:AWSServiceName'=@('vpcorigin.cloudfront.amazonaws.com','ec2-instance-connect.amazonaws.com')}}
)
Save-Policy 'compute' $compute
$edge = @(
    Statement 'CreateTaggedEdge' @('cloudfront:CreateDistribution','cloudfront:CreateVpcOrigin') '*' $new
    Statement 'TagEdge' 'cloudfront:TagResource' @("arn:aws:cloudfront::${AccountId}:distribution/*","arn:aws:cloudfront::${AccountId}:vpcorigin/*") $new
    Statement 'ManageOwnedEdge' @('cloudfront:GetDistribution','cloudfront:GetDistributionConfig','cloudfront:UpdateDistribution','cloudfront:DeleteDistribution','cloudfront:CreateInvalidation','cloudfront:GetInvalidation','cloudfront:GetVpcOrigin','cloudfront:UpdateVpcOrigin','cloudfront:DeleteVpcOrigin','cloudfront:ListTagsForResource') @("arn:aws:cloudfront::${AccountId}:distribution/*","arn:aws:cloudfront::${AccountId}:vpcorigin/*") $own
    Statement 'ReadEdgePolicies' @('cloudfront:ListDistributions','cloudfront:ListVpcOrigins','cloudfront:ListCachePolicies','cloudfront:GetCachePolicy','cloudfront:ListOriginRequestPolicies','cloudfront:GetOriginRequestPolicy','cloudfront:ListResponseHeadersPolicies','cloudfront:GetResponseHeadersPolicy') '*'
    Statement 'BackupBucket' @('s3:CreateBucket','s3:GetBucketLocation','s3:GetBucketPolicy','s3:PutBucketPolicy','s3:DeleteBucketPolicy','s3:GetBucketPublicAccessBlock','s3:PutBucketPublicAccessBlock','s3:GetBucketOwnershipControls','s3:PutBucketOwnershipControls','s3:GetEncryptionConfiguration','s3:PutEncryptionConfiguration','s3:GetLifecycleConfiguration','s3:PutLifecycleConfiguration','s3:GetBucketTagging','s3:PutBucketTagging','s3:ListBucket','s3:DeleteBucket') $bucket
    Statement 'BackupAndRestoreObjects' @('s3:PutObject','s3:GetObject','s3:DeleteObject','s3:AbortMultipartUpload') "$bucket/*"
    Statement 'ApprovedBudgetOnly' @('budgets:ModifyBudget','budgets:ViewBudget') "arn:aws:budgets::${AccountId}:budget/PairForge-Monthly"
)
Save-Policy 'edge-storage-budget' $edge
Save-Policy 'app-backup' @(
    Statement 'WriteEncryptedBackupOnly' @('s3:PutObject','s3:AbortMultipartUpload') "$bucket/postgres/*" @{Bool=@{'aws:SecureTransport'='true'}}
)
Write-Host 'Wrote deployment policies. Validate actions, conditions and allowed/denied scenarios before attachment. No AWS changes made.'
