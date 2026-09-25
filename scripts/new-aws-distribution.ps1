#Requires -Version 7.0
param([string]$StateFile="$PSScriptRoot/../.tmp/m15-aws-state.json",
      [string]$PrivateDirectory="$PSScriptRoot/../.tmp/m15-production")
$ErrorActionPreference='Stop'
. "$PSScriptRoot/aws-deployment-context.ps1"
Assert-PairForgeDeploymentIdentity
$StateFile=[IO.Path]::GetFullPath($StateFile)
$PrivateDirectory=[IO.Path]::GetFullPath($PrivateDirectory)
$state=Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json -AsHashtable
if($state.Account -ne '298984481596' -or -not $state.VpcOriginId -or -not $state.CloudFrontSecurityGroupId) { throw 'Approved private origin and restricted ingress required' }
$origin=Invoke-PairForgeAws @('cloudfront','get-vpc-origin','--id',$state.VpcOriginId)
if($origin.VpcOrigin.Status -ne 'Deployed' -or $origin.VpcOrigin.VpcOriginEndpointConfig.Arn -ne "arn:aws:ec2:us-east-1:298984481596:instance/$($state.AppInstanceId)") { throw 'Exact VPC origin must finish deployment first' }
$instances=Invoke-PairForgeAws @('ec2','describe-instances','--instance-ids',$state.AppInstanceId)
$instance=$instances.Reservations[0].Instances[0]
if($instance.VpcId -ne $state.VpcId -or $instance.PrivateIpAddress -ne $state.AppPrivateIp -or $instance.PublicIpAddress -or -not $instance.PrivateDnsName) { throw 'Private application host identity mismatch' }
$listing=Invoke-PairForgeAws @('cloudfront','list-distributions')
$existing=@($listing.DistributionList.Items | Where-Object Comment -eq 'PairForge restricted portfolio demonstration')
if($existing.Count -gt 1 -or ($existing.Count -eq 1 -and $existing[0].Id -ne $state.DistributionId)) { throw 'Unrecorded PairForge distribution exists; reconcile before creating another' }
if($state.DistributionId) {
    if($existing.Count -ne 1 -or $existing[0].DomainName -ne $state.DistributionDomain) { throw 'Recorded distribution missing or changed' }
    Write-Host "Recorded HTTPS distribution $($state.DistributionId): $($existing[0].Status), https://$($state.DistributionDomain)"
    exit 0
}
$secretPath=Join-Path $PrivateDirectory 'origin-token'
if(-not (Test-Path -LiteralPath $PrivateDirectory -PathType Container)) { throw 'Private owner-only configuration directory required' }
if(Test-Path -LiteralPath $secretPath) {
    $token=[IO.File]::ReadAllText($secretPath).Trim()
    if($token -cnotmatch '^[a-f0-9]{64}$') { throw 'Existing origin token malformed' }
} else {
    $token=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    [IO.File]::WriteAllText($secretPath,"$token`n",[Text.UTF8Encoding]::new($false))
}
$behavior=@{
    TargetOriginId='pairforge-app';ViewerProtocolPolicy='https-only'
    AllowedMethods=@{Quantity=7;Items=@('GET','HEAD','OPTIONS','PUT','PATCH','POST','DELETE');CachedMethods=@{Quantity=2;Items=@('GET','HEAD')}}
    CachePolicyId='4135ea2d-6df8-44a3-9df3-4b5a84be39ad'
    OriginRequestPolicyId='33f36d7e-f396-46d9-90e0-52428a34d9dc'
    Compress=$true
}
$config=@{
    CallerReference='pairforge-m15-one-distribution-v1';Comment='PairForge restricted portfolio demonstration';Enabled=$true
    DefaultRootObject='index.html';IsIPV6Enabled=$true;HttpVersion='http2';PriceClass='PriceClass_100'
    Origins=@{Quantity=1;Items=@(@{
        Id='pairforge-app';DomainName=$instance.PrivateDnsName
        CustomHeaders=@{Quantity=1;Items=@(@{HeaderName='X-PairForge-Origin';HeaderValue=$token})}
        VpcOriginConfig=@{VpcOriginId=$state.VpcOriginId;OriginReadTimeout=30;OriginKeepaliveTimeout=5}
        ConnectionAttempts=1;ConnectionTimeout=3
    })}
    DefaultCacheBehavior=$behavior
    ViewerCertificate=@{CloudFrontDefaultCertificate=$true}
}
$request=@{DistributionConfigWithTags=@{DistributionConfig=$config;Tags=@{Items=@(@{Key='Project';Value='PairForge'},@{Key='Name';Value='pairforge'})}}}
$file=Join-Path $PrivateDirectory 'distribution-request.json'
try {
    [IO.File]::WriteAllText($file,($request | ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    # The complete distribution response includes the secret header. Query only
    # non-sensitive fields, and never print a get-distribution response.
    $created=Invoke-PairForgeAws @('cloudfront','create-distribution-with-tags','--cli-input-json',"file://$file",'--query','Distribution.{Id:Id,DomainName:DomainName,Status:Status}')
    if(-not $created.Id -or $created.DomainName -notmatch '^d[a-z0-9]+\.cloudfront\.net$') { throw 'CloudFront did not return a generated HTTPS domain' }
    $state.DistributionId=$created.Id
    $state.DistributionDomain=$created.DomainName
    [IO.File]::WriteAllText("$StateFile.new",($state | ConvertTo-Json -Depth 12))
    Move-Item -LiteralPath "$StateFile.new" -Destination $StateFile -Force
    Write-Host "Created restricted HTTPS distribution $($state.DistributionId): $($created.Status), https://$($state.DistributionDomain). No origin secret printed."
} finally { if(Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force } }
