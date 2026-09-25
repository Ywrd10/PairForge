#Requires -Version 7.0
param([Parameter(Mandatory)][string]$Directory,
      [Parameter(Mandatory)][string]$AwsExecutable,
      [Parameter(Mandatory)][ValidatePattern('^\d{12}$')][string]$AccountId)
$ErrorActionPreference='Stop'
$policies=@('network','compute','edge-storage-budget') | ForEach-Object { Get-Content (Join-Path $Directory "$_.json") -Raw }
$prefix="arn:aws:ec2:us-east-1:${AccountId}"
$compute=Get-Content (Join-Path $Directory 'compute.json') -Raw | ConvertFrom-Json
$imageArn=($compute.Statement | Where-Object Sid -eq 'LaunchVerifiedUbuntuImage').Resource[0]
$administrationResources=($compute.Statement | Where-Object Sid -eq 'AdministrationInterfaceDependencies').Resource
if(-not $imageArn -or $administrationResources.Count -ne 2) { throw 'Use the finalized AMI-pinned deployment policy' }
$contexts=@{'aws:RequestTag/Project'='PairForge';'aws:ResourceTag/Project'='PairForge';'aws:RequestedRegion'='us-east-1';'ec2:InstanceType'='t3a.small';'ec2:MetadataHttpTokens'='required';'ec2:Tenancy'='default';'ec2:Encrypted'='true';'ec2:VolumeType'='gp3';'ec2:VolumeSize'='20';'ec2:VolumeIops'='3000';'ec2:VolumeThroughput'='125';'ec2:Owner'='099720109477';'ec2:VpceServiceName'='com.amazonaws.us-east-1.s3';'iam:PassedToService'='ec2.amazonaws.com'}
$cases=@(
    @('TaggedVpc','ec2:CreateVpc',"$prefix`:vpc/vpc-00000000000000001",'allowed',@{}),
    @('UntaggedVpc','ec2:CreateVpc',"$prefix`:vpc/vpc-00000000000000001",'implicitDeny',@{'aws:RequestTag/Project'='Other'}),
    @('ApprovedSmall','ec2:RunInstances',"$prefix`:instance/i-00000000000000001",'allowed',@{}),
    @('ApprovedMedium','ec2:RunInstances',"$prefix`:instance/i-00000000000000001",'allowed',@{'ec2:InstanceType'='t3a.medium'}),
    @('UnapprovedSize','ec2:RunInstances',"$prefix`:instance/i-00000000000000001",'implicitDeny',@{'ec2:InstanceType'='m5.24xlarge'}),
    @('WrongRegion','ec2:RunInstances',"arn:aws:ec2:us-west-2:${AccountId}:instance/i-00000000000000001",'implicitDeny',@{}),
    @('RequiredMetadataTokens','ec2:RunInstances',"$prefix`:instance/i-00000000000000001",'implicitDeny',@{'ec2:MetadataHttpTokens'='optional'}),
    @('EncryptedDisk','ec2:RunInstances',"$prefix`:volume/vol-00000000000000001",'allowed',@{}),
    @('NoPlainDisk','ec2:RunInstances',"$prefix`:volume/vol-00000000000000001",'implicitDeny',@{'ec2:Encrypted'='false'}),
    @('NoLargeDisk','ec2:RunInstances',"$prefix`:volume/vol-00000000000000001",'implicitDeny',@{'ec2:VolumeSize'='100'}),
    @('NoNatGateway','ec2:CreateNatGateway',"$prefix`:natgateway/nat-00000000000000001",'implicitDeny',@{}),
    @('NoManagedDatabase','rds:CreateDBInstance','*','implicitDeny',@{}),
    @('NoSelfEscalation','iam:AttachUserPolicy',"arn:aws:iam::${AccountId}:user/pairforge/pairforge-deployer",'implicitDeny',@{}),
    @('ExactEicServiceRole','iam:CreateServiceLinkedRole',"arn:aws:iam::${AccountId}:role/aws-service-role/ec2-instance-connect.amazonaws.com/AWSServiceRoleForEc2InstanceConnect",'allowed',@{'iam:AWSServiceName'='ec2-instance-connect.amazonaws.com'}),
    @('WrongCaseEicRole','iam:CreateServiceLinkedRole',"arn:aws:iam::${AccountId}:role/aws-service-role/ec2-instance-connect.amazonaws.com/AWSServiceRoleForEC2InstanceConnect",'implicitDeny',@{'iam:AWSServiceName'='ec2-instance-connect.amazonaws.com'}),
    @('NoResize','ec2:ModifyInstanceAttribute',"$prefix`:instance/i-00000000000000001",'implicitDeny',@{}),
    @('ApprovedRoleOnly','iam:PassRole',"arn:aws:iam::${AccountId}:role/pairforge/pairforge-app-backup",'allowed',@{}),
    @('NoOtherRole','iam:PassRole',"arn:aws:iam::${AccountId}:role/Administrator",'implicitDeny',@{}),
    @('VerifiedImage','ec2:RunInstances',$imageArn,'allowed',@{'ec2:Owner'='amazon'}),
    @('OtherImage','ec2:RunInstances','arn:aws:ec2:us-east-1::image/ami-00000000000000000','implicitDeny',@{'ec2:Owner'='amazon'}),
    @('NewAdministrationInterface','ec2:CreateNetworkInterface',"$prefix`:network-interface/eni-00000000000000001",'allowed',@{}),
    @('ApprovedAdministrationSubnet','ec2:CreateNetworkInterface',$administrationResources[0],'allowed',@{}),
    @('NoOtherSubnetInterface','ec2:CreateNetworkInterface',"$prefix`:subnet/subnet-00000000000000000",'implicitDeny',@{}),
    @('ApprovedBudget','budgets:ModifyBudget',"arn:aws:budgets::${AccountId}:budget/PairForge-Monthly",'allowed',@{}),
    @('NoOtherBucket','s3:PutObject','arn:aws:s3:::unrelated-bucket/object','implicitDeny',@{})
)
$identity=& $AwsExecutable sts get-caller-identity --profile pairforge --region us-east-1 --output json --no-cli-pager | ConvertFrom-Json
if($LASTEXITCODE -ne 0 -or $identity.Account -ne $AccountId) { throw 'Account verification failed' }
$inputPath=Join-Path $Directory 'simulation-input.json'
foreach($case in $cases) {
    $values=$contexts.Clone(); foreach($entry in $case[4].GetEnumerator()) { $values[$entry.Key]=$entry.Value }
    $entries=@($values.GetEnumerator() | ForEach-Object {
        $type=if($_.Key -in @('ec2:VolumeSize','ec2:VolumeIops','ec2:VolumeThroughput')) {'numeric'} elseif($_.Key -eq 'ec2:Encrypted') {'boolean'} else {'string'}
        @{ContextKeyName=$_.Key;ContextKeyValues=@($_.Value);ContextKeyType=$type}
    })
    @{PolicyInputList=$policies;ActionNames=@($case[1]);ResourceArns=@($case[2]);ContextEntries=$entries} | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $inputPath
    $result=& $AwsExecutable iam simulate-custom-policy --cli-input-json "file://$inputPath" --profile pairforge --region us-east-1 --output json --no-cli-pager | ConvertFrom-Json
    if($LASTEXITCODE -ne 0 -or $result.EvaluationResults.Count -ne 1 -or $result.EvaluationResults[0].EvalDecision -ne $case[3]) { throw "Policy check failed: $($case[0])" }
    Write-Host "PASS $($case[0])"
}
Write-Host "$($cases.Count) IAM simulation checks passed. Simulations do not replace real deployment checks or enforce an instance-count/spending cap."
