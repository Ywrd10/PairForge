#Requires -Version 7.0
param([Parameter(Mandatory)][string]$PublicKeyFile,
      [string]$StateFile="$PSScriptRoot/../.tmp/m15-aws-state.json")
$ErrorActionPreference='Stop'
. "$PSScriptRoot/aws-deployment-context.ps1"
Assert-PairForgeDeploymentIdentity
$StateFile=[IO.Path]::GetFullPath($StateFile)
$state=Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json -AsHashtable
if($state.Account -ne '298984481596' -or $state.Region -ne 'us-east-1' -or -not $state.AdminEndpointId -or -not $state.S3EndpointId) { throw 'Approved network must be fully recorded first' }
Assert-PairForgeAdministrationReady $state
$publicKey=[IO.File]::ReadAllText([IO.Path]::GetFullPath($PublicKeyFile)).Trim()
if($publicKey -notmatch '^ssh-ed25519 [A-Za-z0-9+/=]+( .*)?$') { throw 'Expected an Ed25519 public key, never a private key' }
function Save-State { [IO.File]::WriteAllText("$StateFile.new",($state | ConvertTo-Json -Depth 12)); Move-Item -LiteralPath "$StateFile.new" -Destination $StateFile -Force }
function Request($Action,$Body) {
    $file=[IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllText($file,($Body | ConvertTo-Json -Depth 15),[Text.UTF8Encoding]::new($false))
        return Invoke-PairForgeAws @('ec2',$Action,'--cli-input-json',"file://$file")
    } finally { Remove-Item -LiteralPath $file -Force }
}
function Tags($Type,$Name) { @{ResourceType=$Type;Tags=@(@{Key='Project';Value='PairForge'},@{Key='Name';Value=$Name})} }
if(-not $state.KeyPairId) {
    $keys=Invoke-PairForgeAws @('ec2','describe-key-pairs','--filters','Name=key-name,Values=pairforge-admin')
    if(@($keys.KeyPairs).Count) { throw 'Existing key without recorded identity; reconcile rather than replace it' }
    $key=Request import-key-pair @{KeyName='pairforge-admin';PublicKeyMaterial=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($publicKey));TagSpecifications=@((Tags key-pair pairforge-admin))}
    $state.KeyPairId=$key.KeyPairId; Save-State
}
if(-not $state.AmiId) {
    $images=Invoke-PairForgeAws @('ec2','describe-images','--owners','099720109477','--filters','Name=name,Values=ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*','Name=state,Values=available','Name=architecture,Values=x86_64','Name=root-device-type,Values=ebs')
    $ami=$images.Images | Sort-Object CreationDate -Descending | Select-Object -First 1
    if(-not $ami -or $ami.OwnerId -ne '099720109477') { throw 'No verified Canonical Ubuntu 24.04 image' }
    $state.AmiId=$ami.ImageId; $state.RootDeviceName=$ami.RootDeviceName; Save-State
}
$userData=[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PSScriptRoot/../infra/deploy/cloud-init.yaml"))
foreach($role in @('App','Worker')) {
    $existing=Invoke-PairForgeAws @('ec2','describe-instances','--filters','Name=tag:Project,Values=PairForge','Name=instance-state-name,Values=pending,running,stopping,stopped')
    $instances=@($existing.Reservations | ForEach-Object { $_.Instances })
    if($instances.Count -gt 2) { throw 'More than two owned hosts exist; refusing changes' }
    $key="${role}InstanceId"
    if($state[$key]) {
        if(@($instances | Where-Object { $_.InstanceId -eq $state[$key] -and $_.VpcId -eq $state.VpcId }).Count -ne 1) { throw "Recorded $role host missing or moved" }
        continue
    }
    if($instances.Count -ge 2) { throw 'Refusing a third host' }
    $type=if($role -eq 'App'){'t3a.medium'}else{'t3a.small'}
    $size=if($role -eq 'App'){30}else{20}
    $request=@{
        ImageId=$state.AmiId;InstanceType=$type;MinCount=1;MaxCount=1;KeyName='pairforge-admin'
        ClientToken="pairforge-m15-$($role.ToLower())-v1";UserData=$userData
        CreditSpecification=@{CpuCredits='standard'};Monitoring=@{Enabled=$false}
        Placement=@{AvailabilityZone=$state.Zone;Tenancy='default'}
        InstanceInitiatedShutdownBehavior='stop'
        # Cloud-init needs IMDS for the initial SSH key. The worker has no IAM role;
        # disable its endpoint after bootstrap and verify that before worker startup.
        MetadataOptions=@{HttpTokens='required';HttpEndpoint='enabled';HttpPutResponseHopLimit=1;InstanceMetadataTags='disabled'}
        NetworkInterfaces=@(@{DeviceIndex=0;SubnetId=$state["${role}SubnetId"];Groups=@($state["${role}SecurityGroupId"]);AssociatePublicIpAddress=($role -eq 'Worker');DeleteOnTermination=$true})
        BlockDeviceMappings=@(@{DeviceName=$state.RootDeviceName;Ebs=@{VolumeType='gp3';VolumeSize=$size;Encrypted=$true;Iops=3000;Throughput=125;DeleteOnTermination=$false}})
        TagSpecifications=@((Tags instance "pairforge-$($role.ToLower())"),(Tags volume "pairforge-$($role.ToLower())"),(Tags network-interface "pairforge-$($role.ToLower())"))
    }
    if($role -eq 'App') { $request.IamInstanceProfile=@{Arn='arn:aws:iam::298984481596:instance-profile/pairforge/pairforge-app-backup'} }
    $created=Request run-instances $request
    $state[$key]=$created.Instances[0].InstanceId
    $state["${role}PrivateIp"]=$created.Instances[0].PrivateIpAddress
    Save-State
    Write-Host "Created $role $($state[$key]) ($type), encrypted ${size}GB gp3, standard CPU credits."
}
Write-Host 'Two-host identities recorded. No application has been exposed or execution enabled.'
